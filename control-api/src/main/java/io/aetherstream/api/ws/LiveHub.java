package io.aetherstream.api.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aetherstream.api.LiveSources;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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

/**
 * One idle-until-connected timer dispatches single-flight polls to virtual threads.
 * Socket writes are isolated from both polling and other sessions, with a 2 s / 512 KiB limit.
 * A reconnect gets a fresh snapshot; incremental cursors belong to the live feed, not a browser.
 */
@Component
public class LiveHub extends TextWebSocketHandler {
    private final LiveSources sources;
    private final IncrementalResultRepository results;
    private final ObjectMapper mapper;
    private final ExecutionEvents executionEvents;
    private final FailureLog failures = new FailureLog();
    private final Set<Peer> sessions = ConcurrentHashMap.newKeySet();
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
    private final NewRowsTracker windows = new NewRowsTracker();
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
        startPolling();
        dispatch(() -> initialize(peer));
    }

    private void initialize(Peer peer) {
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
        TextMessage message = encode("snapshot", snapshot);
        if (message != null && send(peer, message)) peer.ready = true;
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
        if (sessions.stream().noneMatch(peer -> peer.ready) || !running.compareAndSet(false, true)) return;
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

    private TextMessage encode(String type, Object data) {
        try { return new TextMessage(mapper.writeValueAsString(new LiveMessage(type, System.currentTimeMillis(), data))); }
        catch (IOException error) { failures.failed("serialization", error); return null; }
    }

    private void broadcast(String type, Object data) {
        TextMessage message = encode(type, data);
        if (message == null) return;
        for (Peer peer : sessions) {
            if (peer.ready) dispatch(() -> send(peer, message));
        }
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
        synchronized (this) {
            if (sessions.isEmpty()) {
                for (ScheduledFuture<?> schedule : schedules) schedule.cancel(false);
                schedules.clear();
            }
        }
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

    private static final class Peer {
        final ConcurrentWebSocketSessionDecorator session;
        final AtomicLong sendingSince = new AtomicLong();
        volatile boolean ready;

        Peer(WebSocketSession session) {
            this.session = new ConcurrentWebSocketSessionDecorator(session, 2000, 512 * 1024);
        }
    }
}
