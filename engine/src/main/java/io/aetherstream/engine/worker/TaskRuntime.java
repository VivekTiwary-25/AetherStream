package io.aetherstream.engine.worker;

import com.fasterxml.jackson.databind.JsonNode;
import io.aetherstream.common.protocol.CheckpointAck;
import io.aetherstream.common.protocol.CheckpointManifest;
import io.aetherstream.common.protocol.Command;
import io.aetherstream.common.protocol.Protocol;
import io.aetherstream.common.protocol.WorkerSnapshot;
import io.aetherstream.compiler.ops.ListCollector;
import io.aetherstream.compiler.ops.PartitionPipeline;
import io.aetherstream.engine.storage.CheckpointStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one ASSIGN (contract 03 sections 1, 4, 5): source thread -> queue 1 -> operator thread -> queue 2 -> sink thread.
 * Full queues backpressure upstream. Two coalesced requests bound control memory; checkpoint offsets track queue
 * admission, while source-thread-only lag samples use consumer positions, including under backpressure.
 */
public final class TaskRuntime {
    public static final int QUEUE_CAPACITY = 1000;
    public static final int FLUSH_OUTPUTS = 200;
    public static final long FLUSH_INTERVAL_MS = 200;
    public static final long LAG_REFRESH_MS = 2000;
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(100);
    private static final Logger log = LoggerFactory.getLogger(TaskRuntime.class);

    public enum State { RESTORING, RUNNING, STOPPED }

    /** Called (from a runtime thread) when the runtime stops by itself: fenced or failed. */
    public interface Listener {
        void stoppedByItself(TaskRuntime runtime, String reason);
    }

    private final String workerId;
    private final Command.Assign assign;
    private final List<Integer> partitions;
    private final RecordSourceFactory sourceFactory;
    private final ResultSink sink;
    private final CheckpointStore store;
    private final CoordinatorClient coordinator;
    private final LongSupplier sinkDelayMs;
    private final Listener listener;
    private final int queueCapacity;
    private final LongSupplier nanoTime;

    private final ArrayBlockingQueue<Item> sourceQueue;
    private final ArrayBlockingQueue<Item> sinkQueue;
    // Guarded by pendingBarriers; retain only the two newest requests not yet in sourceQueue.
    private final TreeSet<Long> pendingBarriers = new TreeSet<>();
    private long highestCheckpointRequested;
    private final AtomicLong processedRecords = new AtomicLong();
    private final AtomicLong sinkBatches = new AtomicLong();
    private final List<Thread> threads = new ArrayList<>();

    private volatile State state = State.RESTORING;
    private volatile boolean running;
    private volatile boolean fenced;
    private volatile long lastCheckpointId;
    private volatile Map<Integer, Long> lag = Map.of();
    private volatile RecordSource source;
    private volatile boolean gracefulShutdown;
    private volatile long shutdownDeadline;
    private long lastLagNanos; // source-thread only

    // recordsPerSec EWMA (guarded by this)
    private long rateLastMs = -1;
    private long rateLastCount;
    private double rate;

    public TaskRuntime(String workerId, Command.Assign assign, RecordSourceFactory sourceFactory, ResultSink sink,
                       CheckpointStore store, CoordinatorClient coordinator, LongSupplier sinkDelayMs,
                       Listener listener) {
        this(workerId, assign, sourceFactory, sink, store, coordinator, sinkDelayMs, listener,
                QUEUE_CAPACITY, System::nanoTime);
    }

    TaskRuntime(String workerId, Command.Assign assign, RecordSourceFactory sourceFactory, ResultSink sink,
                CheckpointStore store, CoordinatorClient coordinator, LongSupplier sinkDelayMs,
                Listener listener, int queueCapacity, LongSupplier nanoTime) {
        if (queueCapacity < 1 || queueCapacity > QUEUE_CAPACITY) {
            throw new IllegalArgumentException("worker queue capacity must be between 1 and " + QUEUE_CAPACITY);
        }
        this.queueCapacity = queueCapacity;
        this.nanoTime = nanoTime;
        this.sourceQueue = new ArrayBlockingQueue<>(queueCapacity);
        this.sinkQueue = new ArrayBlockingQueue<>(queueCapacity);
        this.workerId = workerId;
        this.assign = assign;
        List<Integer> ps = new ArrayList<>(assign.partitions() == null ? List.of() : assign.partitions());
        Collections.sort(ps);
        this.partitions = List.copyOf(ps);
        this.sourceFactory = sourceFactory;
        this.sink = sink;
        this.store = store;
        this.coordinator = coordinator;
        this.sinkDelayMs = sinkDelayMs;
        this.listener = listener == null ? (r, why) -> { } : listener;
        // A checkpoint restored from counts as the last one this runtime knows about.
        this.lastCheckpointId = checkpointBaseline(assign);
        this.highestCheckpointRequested = checkpointBaseline(assign);
    }

    /**
     * Highest checkpoint id already known for this execution. A savepoint restore uses another execution's manifest
     * whose checkpoint id is unrelated to this execution's numbering (which starts again at 1), so it counts as 0;
     * otherwise TRIGGER_CHECKPOINT 1, 2, ... of the restored execution would be ignored as old.
     */
    static long checkpointBaseline(Command.Assign assign) {
        String key = assign.manifestKey();
        boolean ownExecution = key == null || key.startsWith("executions/" + assign.executionId() + "/");
        return ownExecution ? assign.restoreCheckpointId() : 0;
    }

    // ------------------------------------------------------------------ lifecycle

    public synchronized void start() {
        if (running || state == State.STOPPED) {
            return;
        }
        running = true;
        String tag = assign.executionId() + "-g" + assign.generation();
        threads.add(new Thread(this::sourceLoop, "source-" + tag));
        threads.add(new Thread(this::operatorLoop, "operator-" + tag));
        threads.add(new Thread(this::sinkLoop, "sink-" + tag));
        for (Thread t : threads) {
            t.setDaemon(true);
            t.start();
        }
        log.info("Runtime started: execution {} generation {} partitions {} restore {} offsets {}",
                assign.executionId(), assign.generation(), partitions, assign.restoreCheckpointId(),
                assign.startOffsets());
    }

    /** Cancels work; queued outputs are replayed from the last checkpoint. */
    public void stop() {
        stop(false);
    }

    /** Stops intake and finishes only the sink's current batch, with a shared 2.5 s deadline. */
    public void shutdown() {
        stop(true);
    }

    private void stop(boolean graceful) {
        List<Thread> toJoin;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2500);
        synchronized (this) {
            gracefulShutdown = graceful && state != State.STOPPED;
            shutdownDeadline = deadline;
            running = false;
            state = State.STOPPED;
            toJoin = List.copyOf(threads);
        }
        RecordSource active = source;
        if (active != null) {
            active.wakeup();
        }
        for (int i = 0; i < toJoin.size(); i++) {
            Thread t = toJoin.get(i);
            if (t != Thread.currentThread() && !(gracefulShutdown && i == 2)) {
                t.interrupt();
            }
        }
        // The sink must not be interrupted during its in-flight transaction.
        for (Thread t : toJoin.reversed()) {
            if (t != Thread.currentThread()) {
                try {
                    long remaining = deadline - System.nanoTime();
                    if (remaining > 0) {
                        TimeUnit.NANOSECONDS.timedJoin(t, remaining);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (t.isAlive()) {
                    t.interrupt();
                }
            }
        }
        sourceQueue.clear();
        sinkQueue.clear();
        synchronized (pendingBarriers) {
            pendingBarriers.clear();
        }
    }

    /** Stops from inside a runtime thread (fenced / fatal error) and tells the listener. */
    private void stopByItself(String reason) {
        synchronized (this) {
            if (state == State.STOPPED) {
                return;
            }
            running = false;
            state = State.STOPPED;
        }
        for (Thread t : threads) {
            if (t != Thread.currentThread()) {
                t.interrupt();
            }
        }
        sourceQueue.clear();
        sinkQueue.clear();
        log.warn("Runtime execution {} generation {} stopped: {}", assign.executionId(), assign.generation(), reason);
        listener.stoppedByItself(this, reason);
    }

    /** Coalesces requests without blocking the heartbeat thread on a full data queue. */
    public void triggerCheckpoint(long checkpointId) {
        synchronized (pendingBarriers) {
            if (running && checkpointId > highestCheckpointRequested) {
                highestCheckpointRequested = checkpointId;
                if (pendingBarriers.size() == 2) {
                    pendingBarriers.pollFirst();
                }
                pendingBarriers.add(checkpointId);
            }
        }
    }

    int pendingCheckpointCount() {
        synchronized (pendingBarriers) {
            return pendingBarriers.size();
        }
    }

    // ------------------------------------------------------------------ source thread

    private void sourceLoop() {
        RecordSource localSource = null;
        try {
            localSource = sourceFactory.create(assign.plan().topic());
            source = localSource;
            if (!running) {
                return;
            }
            localSource.assign(partitions, assign.startOffsets() == null ? Map.of() : assign.startOffsets());
            Map<Integer, Long> start = assign.startOffsets() == null ? Map.of() : assign.startOffsets();
            Map<Integer, Long> next = new TreeMap<>();
            for (int p : partitions) {
                next.put(p, start.getOrDefault(p, 0L));
            }
            while (running) {
                putPendingBarriers(next);
                refreshLagIfDue();
                for (RecordSource.SourceRecord r : localSource.poll(POLL_TIMEOUT)) {
                    if (!running) {
                        break;
                    }
                    putPendingBarriers(next);
                    if (!next.containsKey(r.partition())) {
                        continue; // not ours
                    }
                    Item.RecordItem item = new Item.RecordItem(r.partition(), r.offset(), r.value());
                    while (running && !sourceQueue.offer(item, 50, TimeUnit.MILLISECONDS)) {
                        refreshLagIfDue();
                    }
                    if (!running) {
                        break;
                    }
                    next.put(r.partition(), r.offset() + 1);
                }
            }
        } catch (InterruptedException e) {
            // stopping
        } catch (Throwable e) {
            if (running) {
                log.error("Source thread failed", e);
                stopByItself("source failed: " + e);
            }
        } finally {
            if (localSource != null) {
                // Kafka close must run on the consumer thread, without a leftover interrupt flag.
                Thread.interrupted();
                try {
                    localSource.close();
                } catch (RuntimeException e) {
                    log.debug("closing source failed", e);
                }
            }
            source = null;
        }
    }

    private void putPendingBarriers(Map<Integer, Long> next) throws InterruptedException {
        while (running) {
            synchronized (pendingBarriers) {
                if (pendingBarriers.isEmpty()) {
                    return;
                }
                if (sourceQueue.remainingCapacity() > 0) {
                    long id = pendingBarriers.pollFirst();
                    sourceQueue.add(new Item.BarrierItem(id, Map.copyOf(next), null));
                    continue;
                }
            }
            refreshLagIfDue();
            Thread.sleep(10);
        }
    }

    private void refreshLagIfDue() {
        long now = System.nanoTime();
        if (lastLagNanos != 0 && now - lastLagNanos < TimeUnit.MILLISECONDS.toNanos(LAG_REFRESH_MS)) {
            return;
        }
        lastLagNanos = now;
        try {
            Map<Integer, Long> end = source.endOffsets();
            Map<Integer, Long> next = source.nextOffsets();
            lag = calculateLag(end, next);
        } catch (RuntimeException e) {
            if (running) {
                log.debug("lag refresh failed", e);
            }
        }
    }

    /** Only sampled assigned positions contribute; a stale end offset must never yield negative lag. */
    static Map<Integer, Long> calculateLag(Map<Integer, Long> end, Map<Integer, Long> next) {
        Map<Integer, Long> lag = new TreeMap<>();
        next.forEach((partition, position) -> {
            Long endOffset = end.get(partition);
            if (endOffset != null) {
                lag.put(partition, Math.max(0, endOffset - position));
            }
        });
        return Collections.unmodifiableMap(lag);
    }

    // ------------------------------------------------------------------ operator thread

    private void operatorLoop() {
        try {
            Map<Integer, PartitionPipeline> pipelines = loadPipelines();
            synchronized (this) {
                if (!running) {
                    return;
                }
                state = State.RUNNING;
            }
            while (running) {
                Item item = sourceQueue.take();
                if (item instanceof Item.RecordItem r) {
                    PartitionPipeline pipeline = pipelines.get(r.partition());
                    ListCollector collector = new ListCollector();
                    pipeline.process(r.offset(), r.value(), collector);
                    List<SinkRow> rows = new ArrayList<>(collector.outputs().size());
                    for (ListCollector.Output o : collector.outputs()) {
                        rows.add(new SinkRow(r.partition(), r.offset(), o));
                    }
                    sinkQueue.put(new Item.OutputBatch(r.partition(), r.offset(), rows));
                    processedRecords.incrementAndGet();
                } else if (item instanceof Item.BarrierItem b) {
                    Map<Integer, JsonNode> snapshots = new TreeMap<>();
                    for (Map.Entry<Integer, PartitionPipeline> e : pipelines.entrySet()) {
                        snapshots.put(e.getKey(), e.getValue().snapshot());
                    }
                    sinkQueue.put(new Item.BarrierItem(b.checkpointId(), b.offsets(), snapshots));
                }
            }
        } catch (InterruptedException e) {
            // stopping
        } catch (Throwable e) {
            if (running) {
                log.error("Operator thread failed", e);
                stopByItself("operator failed: " + e);
            }
        }
    }

    /** Fresh pipelines for checkpoint 0, else restored from the worker snapshots referenced by the manifest. */
    private Map<Integer, PartitionPipeline> loadPipelines() {
        Map<Integer, PartitionPipeline> pipelines = new TreeMap<>();
        if (assign.restoreCheckpointId() == 0) {
            for (int p : partitions) {
                pipelines.put(p, new PartitionPipeline(assign.plan(), p));
            }
            return pipelines;
        }
        String manifestKey = assign.manifestKey() != null ? assign.manifestKey()
                : WorkerKeys.manifestKey(assign.executionId(), assign.restoreCheckpointId());
        CheckpointManifest manifest = store.getJson(manifestKey, CheckpointManifest.class);
        if (manifest == null) {
            throw new IllegalStateException("manifest " + manifestKey + " not found");
        }
        Map<String, WorkerSnapshot> files = new HashMap<>();
        for (int p : partitions) {
            CheckpointManifest.PartitionEntry entry = manifest.partitions().get(p);
            if (entry == null) {
                throw new IllegalStateException("manifest " + manifestKey + " has no partition " + p);
            }
            WorkerSnapshot snapshot = files.computeIfAbsent(entry.objectKey(),
                    k -> store.getJson(k, WorkerSnapshot.class));
            WorkerSnapshot.PartitionState ps = snapshot == null ? null : snapshot.partitions().get(p);
            if (ps == null) {
                throw new IllegalStateException("snapshot " + entry.objectKey() + " has no partition " + p);
            }
            pipelines.put(p, PartitionPipeline.restore(assign.plan(), p, ps.state()));
        }
        return pipelines;
    }

    // ------------------------------------------------------------------ sink thread

    private void sinkLoop() {
        List<SinkRow> pending = new ArrayList<>();
        long lastFlush = System.currentTimeMillis();
        try {
            while (running) {
                long wait = Math.max(1, FLUSH_INTERVAL_MS - (System.currentTimeMillis() - lastFlush));
                Item item = sinkQueue.poll(wait, TimeUnit.MILLISECONDS);
                if (item != null) {
                    long delay = sinkDelayMs.getAsLong();
                    if (delay > 0) {
                        long until = nanoTime.getAsLong() + TimeUnit.MILLISECONDS.toNanos(delay);
                        while (running && nanoTime.getAsLong() < until) {
                            Thread.sleep(Math.min(50, Math.max(1,
                                    TimeUnit.NANOSECONDS.toMillis(until - nanoTime.getAsLong()))));
                        }
                    }
                    if (item instanceof Item.OutputBatch batch) {
                        pending.addAll(batch.rows());
                        if (pending.size() >= FLUSH_OUTPUTS) {
                            if (!flush(pending)) {
                                return;
                            }
                            lastFlush = System.currentTimeMillis();
                        }
                    } else if (item instanceof Item.BarrierItem barrier) {
                        if (!flush(pending)) {
                            return;
                        }
                        lastFlush = System.currentTimeMillis();
                        if (running) {
                            completeCheckpoint(barrier);
                        }
                    }
                }
                if (!pending.isEmpty() && System.currentTimeMillis() - lastFlush >= FLUSH_INTERVAL_MS) {
                    if (!flush(pending)) {
                        return;
                    }
                }
                if (pending.isEmpty() && System.currentTimeMillis() - lastFlush >= FLUSH_INTERVAL_MS) {
                    lastFlush = System.currentTimeMillis();
                }
            }
            if (gracefulShutdown && !pending.isEmpty()) {
                flush(pending);
            }
        } catch (InterruptedException e) {
            // stopping: queued and pending outputs are discarded (replayed from the last checkpoint)
        } catch (Throwable e) {
            if (running) {
                log.error("Sink thread failed", e);
                stopByItself("sink failed: " + e);
            }
        }
    }

    /** One fenced transaction; retries transient failures. Returns false when fenced (runtime is then stopped). */
    private boolean flush(List<SinkRow> pending) throws InterruptedException {
        while (running || (gracefulShutdown && System.nanoTime() < shutdownDeadline)) {
            boolean ok;
            try {
                ok = sink.write(assign.executionId(), assign.generation(), List.copyOf(pending));
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                if (!running) {
                    return false;
                }
                log.warn("Sink write of {} rows failed, retrying in 1 s: {}", pending.size(), e.toString());
                Thread.sleep(1000);
                continue;
            }
            if (!ok) {
                fenced = true;
                pending.clear();
                stopByItself("fenced: generation " + assign.generation() + " is no longer current");
                return false;
            }
            sinkBatches.incrementAndGet();
            pending.clear();
            return true;
        }
        return false;
    }

    private void completeCheckpoint(Item.BarrierItem barrier) {
        Map<Integer, WorkerSnapshot.PartitionState> parts = new TreeMap<>();
        for (int p : partitions) {
            parts.put(p, new WorkerSnapshot.PartitionState(barrier.offsets().get(p), barrier.snapshots().get(p)));
        }
        String key = WorkerKeys.workerSnapshotKey(assign.executionId(), barrier.checkpointId(), workerId);
        try {
            store.putJson(key, new WorkerSnapshot(barrier.checkpointId(), assign.generation(), workerId, parts));
        } catch (RuntimeException e) {
            log.warn("Upload of checkpoint {} failed, not acking: {}", barrier.checkpointId(), e.toString());
            return;
        }
        CheckpointAck ack = new CheckpointAck(Protocol.VERSION, workerId, assign.executionId(), assign.generation(),
                barrier.checkpointId(), new TreeMap<>(barrier.offsets()), key);
        try {
            var response = coordinator.ack(ack);
            if (response != null && !response.accepted()) {
                log.info("Checkpoint {} ack rejected: {}", barrier.checkpointId(), response.reason());
            }
        } catch (Exception e) {
            log.warn("Ack of checkpoint {} failed: {}", barrier.checkpointId(), e.toString());
            return;
        }
        lastCheckpointId = barrier.checkpointId();
    }

    // ------------------------------------------------------------------ status

    public String executionId() { return assign.executionId(); }
    public long generation() { return assign.generation(); }
    public Command.Assign assignCommand() { return assign; }
    public List<Integer> partitions() { return partitions; }
    public State state() { return state; }
    public boolean isStopped() { return state == State.STOPPED; }
    public boolean isFenced() { return fenced; }
    public int sourceQueueSize() { return sourceQueue.size(); }
    public int sinkQueueSize() { return sinkQueue.size(); }
    public long processedRecords() { return processedRecords.get(); }
    public long lastCheckpointId() { return lastCheckpointId; }
    public long sinkBatchesTotal() { return sinkBatches.get(); }
    public Map<Integer, Long> lag() { return lag; }

    /** True when either queue is at least 90% full. */
    public boolean backpressured() {
        int threshold = (queueCapacity * 9 + 9) / 10;
        return sourceQueue.size() >= threshold || sinkQueue.size() >= threshold;
    }

    /** Records per second, exponentially weighted with a 1 s time constant; updated on every call. */
    public synchronized double recordsPerSec() {
        long now = System.currentTimeMillis();
        long count = processedRecords.get();
        if (rateLastMs < 0) {
            rateLastMs = now;
            rateLastCount = count;
            return 0;
        }
        long dt = now - rateLastMs;
        if (dt <= 0) {
            return rate;
        }
        double instant = (count - rateLastCount) * 1000.0 / dt;
        double alpha = 1 - Math.exp(-dt / 1000.0);
        rate = rate + alpha * (instant - rate);
        rateLastMs = now;
        rateLastCount = count;
        return rate;
    }
}
