package io.aetherstream.api.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aetherstream.api.LiveSources;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * One idle-until-connected timer dispatches single-flight polls to virtual threads.
 * Socket writes are isolated from both polling and other sessions, with a 2 s / 512 KiB limit.
 * Reconnects replay the retained feed before a fresh snapshot, or resync when replay exceeds half
 * the send budget (reserving room for the snapshot/live cutover); polling survives disconnects.
 */
@Component
public class LiveHub extends TextWebSocketHandler {
    private final LiveSources sources;
    private final IncrementalResultRepository results;
    private final ObjectMapper mapper;
    private final ExecutionEvents executionEvents;
    private final FailureLog failures = new FailureLog();
    private final Set<Peer> sessions = ConcurrentHashMap.newKeySet();
    private static final int HISTORY_LIMIT = 500;
    private static final int SEND_BUFFER_LIMIT = 512 * 1024;
    private final Object publication = new Object();
    private final ArrayDeque<Retained> history = new ArrayDeque<>(HISTORY_LIMIT);
    private long sequence;
    private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, runnable -> {
        Thread thread = new Thread(runnable, "live-poll-timer");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService io = Executors.newVirtualThreadPerTaskExecutor();
    private final List<ScheduledFuture<?>> schedules = new ArrayList<>();
    private final AtomicBoolean trucksRunning = new AtomicBoolean();
    private final AtomicBoolean clusterRunning = new AtomicBoolean();
    private final AtomicBoolean resultsRunning = new AtomicBoolean();
    private final NewRowsTracker windows = new NewRowsTracker(true);
    private final NewRowsTracker alerts = new NewRowsTracker();
    private final NewRowsTracker deadLetters = new NewRowsTracker();
    private volatile JsonNode latestCluster;
    private String resultExecutionId;
    private IncrementalResultRepository.Counts lastCounts;
    private long lastCountTime;

    public LiveHub(LiveSources sources, IncrementalResultRepository results, ObjectMapper mapper) {
        this.sources = sources;
        this.results = results;
        this.mapper = mapper;
        this.executionEvents = new ExecutionEvents(mapper);
        scheduler.setRemoveOnCancelPolicy(true);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Peer peer = new Peer(session);
        sessions.add(peer);
        Long since;
        try {
            String value = session.getUri() == null ? null : UriComponentsBuilder.fromUri(session.getUri())
                    .build().getQueryParams().getFirst("since");
            since = value == null ? null : Long.parseLong(value);
            if (since != null && since < 0) throw new IllegalArgumentException("negative since");
        } catch (IllegalArgumentException error) {
            remove(peer, CloseStatus.BAD_DATA);
            return;
        }
        startPolling();
        dispatch(() -> initialize(peer, since));
    }

    private void initialize(Peer peer, Long since) {
        CompletableFuture<JsonNode> cluster = async("coordinator", sources::cluster);
        CompletableFuture<JsonNode> execution = async("postgres", sources::execution);
        CompletableFuture<JsonNode> simulator = async("simulator", sources::simulator);
        CompletableFuture<String> executionId = async("postgres", sources::currentExecutionId);
        JsonNode state = cluster.join();
        String id = executionId.join();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("execution", mergeExecution(execution.join(), state));
        snapshot.put("workers", state == null ? null : state.get("workers"));
        snapshot.put("summary", safe("postgres", () -> sources.summary(id, rate(state))));
        snapshot.put("simulator", simulator.join());
        synchronized (publication) {
            if (!sessions.contains(peer)) return;
            if (since != null) {
                long replayBytes = 0;
                for (Retained retained : history) {
                    if (retained.seq() > since) replayBytes += retained.message().getPayloadLength();
                }
                if (since > sequence || (!history.isEmpty() && since < history.getFirst().seq() - 1)
                        || replayBytes > SEND_BUFFER_LIMIT / 2) {
                    Retained resync = encode("resync", null);
                    if (resync != null) enqueue(peer, resync.message());
                } else {
                    for (Retained retained : history) {
                        if (retained.seq() > since) enqueue(peer, retained.message());
                    }
                }
            }
            Retained message = encode("snapshot", snapshot);
            if (message != null) {
                enqueue(peer, message.message());
                peer.ready = true;
            }
        }
    }

    private <T> CompletableFuture<T> async(String upstream, Supplier<T> action) {
        return CompletableFuture.supplyAsync(() -> safe(upstream, action), io);
    }

    private synchronized void startPolling() {
        if (!schedules.isEmpty() || scheduler.isShutdown()) return;
        schedules.add(scheduler.scheduleAtFixedRate(() -> poll(trucksRunning, this::pollTrucks), 250, 250, TimeUnit.MILLISECONDS));
        schedules.add(scheduler.scheduleAtFixedRate(() -> poll(clusterRunning, this::pollCluster), 1000, 1000, TimeUnit.MILLISECONDS));
        schedules.add(scheduler.scheduleAtFixedRate(() -> poll(resultsRunning, this::pollResults), 1000, 1000, TimeUnit.MILLISECONDS));
        schedules.add(scheduler.scheduleAtFixedRate(this::expireSlowSessions, 250, 250, TimeUnit.MILLISECONDS));
    }

    private void poll(AtomicBoolean running, Runnable action) {
        if (!running.compareAndSet(false, true)) return;
        dispatch(() -> {
            try { action.run(); }
            catch (RuntimeException error) { failures.failed("poller", error); }
            finally { running.set(false); }
        });
    }

    private void pollTrucks() {
        JsonNode state = safe("simulator", sources::simulator);
        if (state != null) {
            ObjectNode data = mapper.createObjectNode();
            data.set("simTime", state.get("simTime"));
            data.set("trucks", state.get("trucks"));
            broadcast("trucks", data);
        }
    }

    private void pollCluster() {
        JsonNode state = safe("coordinator", sources::cluster);
        latestCluster = state;
        JsonNode execution = safe("postgres", sources::execution);
        if (state != null) broadcast("workers", state.get("workers"));
        JsonNode workers = state == null ? null : state.get("workers");
        for (ObjectNode event : executionEvents.observe(mergeExecution(execution, state), workers)) {
            broadcast("execution", event);
        }
    }

    private JsonNode mergeExecution(JsonNode execution, JsonNode cluster) {
        if (execution == null || execution.isNull()) return null;
        JsonNode current = cluster == null ? null : cluster.get("execution");
        if (current == null || !current.isObject() || !execution.path("id").equals(current.path("id"))) return execution;
        ObjectNode merged = execution.deepCopy();
        merged.setAll((ObjectNode) current);
        return merged;
    }

    private void pollResults() {
        // A failed current-id query must not reset cursors or emit rows from a different execution.
        String id;
        try { id = sources.currentExecutionId(); }
        catch (RuntimeException error) { failures.failed("postgres", error); return; }
        if (!Objects.equals(id, resultExecutionId)) {
            resultExecutionId = id;
            windows.reset();
            alerts.reset();
            deadLetters.reset();
            lastCounts = null;
        }
        if (id != null) {
            newRows("windows", windows, () -> results.windows(id, windows.since()));
            newRows("alerts", alerts, () -> results.alerts(id, alerts.since()));
            newRows("deadLetters", deadLetters, () -> results.deadLetters(id, deadLetters.since()));
        }
        IncrementalResultRepository.Counts counts = safe("postgres", () -> id == null
                ? new IncrementalResultRepository.Counts(0, 0) : results.counts(id));
        if (counts == null) return;
        long now = System.nanoTime();
        double seconds = (now - lastCountTime) / 1_000_000_000.0;
        double rejected = lastCounts == null ? 0 : Math.max(0, counts.rejected() - lastCounts.rejected()) / seconds;
        double late = lastCounts == null ? 0 : Math.max(0, counts.late() - lastCounts.late()) / seconds;
        lastCounts = counts;
        lastCountTime = now;
        JsonNode cluster = latestCluster;
        if (cluster == null) return;
        Map<String, Double> partitions = new LinkedHashMap<>();
        for (JsonNode worker : cluster.path("workers")) {
            JsonNode owned = worker.path("partitions");
            if (!worker.path("alive").asBoolean() || owned.isEmpty()) continue;
            double share = worker.path("recordsPerSec").asDouble() / owned.size();
            for (JsonNode partition : owned) partitions.merge(partition.asText(), share, Double::sum);
        }
        broadcast("flow", Map.of("eventsPerSec", rate(cluster), "byPartition", partitions,
                "rejectedPerSec", rejected, "latePerSec", late));
    }

    private void newRows(String type, NewRowsTracker tracker, Supplier<List<IncrementalResultRepository.Row>> query) {
        List<IncrementalResultRepository.Row> rows = safe("postgres", query);
        if (rows == null) return;
        List<IncrementalResultRepository.Row> fresh = tracker.fresh(rows);
        if (!fresh.isEmpty()) broadcast(type, fresh.stream().map(IncrementalResultRepository.Row::data).toList());
    }

    private static double rate(JsonNode cluster) {
        double rate = 0;
        if (cluster != null) {
            for (JsonNode worker : cluster.path("workers")) {
                if (worker.path("alive").asBoolean()) rate += worker.path("recordsPerSec").asDouble();
            }
        }
        return rate;
    }

    private <T> T safe(String upstream, Supplier<T> action) {
        try { return action.get(); }
        catch (RuntimeException error) { failures.failed(upstream, error); return null; }
    }

    // Called only under publication: assigning seq, retaining and queuing share one order.
    private Retained encode(String type, Object data) {
        try {
            long next = sequence + 1;
            TextMessage message = new TextMessage(mapper.writeValueAsString(
                    new LiveMessage(type, System.currentTimeMillis(), data, next)));
            sequence = next;
            Retained retained = new Retained(next, message);
            if (history.size() == HISTORY_LIMIT) history.removeFirst();
            history.addLast(retained);
            return retained;
        } catch (IOException error) {
            failures.failed("serialization", error);
            return null;
        }
    }

    private void broadcast(String type, Object data) {
        synchronized (publication) {
            Retained retained = encode(type, data);
            if (retained == null) return;
            for (Peer peer : sessions) {
                if (peer.ready) enqueue(peer, retained.message());
            }
        }
    }

    private void enqueue(Peer peer, TextMessage message) {
        if (!sessions.contains(peer)) return;
        if (peer.pendingBytes.addAndGet(message.getPayloadLength()) > SEND_BUFFER_LIMIT) {
            remove(peer, CloseStatus.SESSION_NOT_RELIABLE);
            return;
        }
        peer.pending.add(message);
        if (peer.draining.compareAndSet(false, true)) dispatch(() -> drain(peer));
    }

    private void drain(Peer peer) {
        do {
            try {
                TextMessage message;
                while (sessions.contains(peer) && (message = peer.pending.poll()) != null) {
                    boolean sent = send(peer, message);
                    peer.pendingBytes.addAndGet(-message.getPayloadLength());
                    if (!sent) return;
                }
            } finally {
                peer.draining.set(false);
            }
        } while (sessions.contains(peer) && !peer.pending.isEmpty()
                && peer.draining.compareAndSet(false, true));
    }

    private boolean send(Peer peer, TextMessage message) {
        if (!sessions.contains(peer) || !peer.session.isOpen()) return false;
        long started = System.nanoTime();
        boolean writer = peer.sendingSince.compareAndSet(0, started);
        try {
            peer.session.sendMessage(message);
            return sessions.contains(peer);
        } catch (IOException | RuntimeException error) {
            failures.failed("session", error);
            remove(peer, CloseStatus.SESSION_NOT_RELIABLE);
            return false;
        } finally {
            if (writer) peer.sendingSince.compareAndSet(started, 0);
        }
    }

    private void expireSlowSessions() {
        long now = System.nanoTime();
        for (Peer peer : sessions) {
            long started = peer.sendingSince.get();
            if (started != 0 && now - started >= TimeUnit.SECONDS.toNanos(2)) {
                remove(peer, CloseStatus.SESSION_NOT_RELIABLE);
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) { remove(session); }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) { remove(session); }

    private void remove(WebSocketSession session) {
        for (Peer peer : sessions) {
            if (peer.session.getId().equals(session.getId())) remove(peer, CloseStatus.NORMAL);
        }
    }

    private void remove(Peer peer, CloseStatus status) {
        if (!sessions.remove(peer)) return;
        peer.pending.clear();
        dispatch(() -> {
            try { peer.session.close(status); }
            catch (IOException | RuntimeException error) { failures.failed("session", error); }
        });
    }

    private void dispatch(Runnable action) {
        try { io.execute(action); }
        catch (RejectedExecutionException error) {
            if (!io.isShutdown()) failures.failed("dispatch", error);
        }
    }

    @PreDestroy
    public void close() {
        scheduler.shutdownNow();
        for (Peer peer : sessions) remove(peer, CloseStatus.GOING_AWAY);
        io.shutdown();
    }

    private record Retained(long seq, TextMessage message) {}

    private static final class Peer {
        final ConcurrentWebSocketSessionDecorator session;
        final AtomicLong sendingSince = new AtomicLong();
        final ConcurrentLinkedQueue<TextMessage> pending = new ConcurrentLinkedQueue<>();
        final AtomicLong pendingBytes = new AtomicLong();
        final AtomicBoolean draining = new AtomicBoolean();
        volatile boolean ready;

        Peer(WebSocketSession session) {
            this.session = new ConcurrentWebSocketSessionDecorator(session, 2000, SEND_BUFFER_LIMIT);
        }
    }
}
