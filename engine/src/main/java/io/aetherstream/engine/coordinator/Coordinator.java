package io.aetherstream.engine.coordinator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aetherstream.common.Json;
import io.aetherstream.common.api.ExecutionInfo;
import io.aetherstream.common.pipeline.ExecutionPlan;
import io.aetherstream.common.pipeline.PlannedOperator;
import io.aetherstream.common.protocol.AckResponse;
import io.aetherstream.common.protocol.CheckpointAck;
import io.aetherstream.common.protocol.CheckpointManifest;
import io.aetherstream.common.protocol.ClusterState;
import io.aetherstream.common.protocol.Command;
import io.aetherstream.common.protocol.HeartbeatResponse;
import io.aetherstream.common.protocol.Protocol;
import io.aetherstream.common.protocol.RegisterRequest;
import io.aetherstream.common.protocol.RegisterResponse;
import io.aetherstream.common.protocol.StartExecutionRequest;
import io.aetherstream.common.protocol.WorkerStatus;
import io.aetherstream.engine.storage.CheckpointStore;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.TreeMap;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinator logic (contract 03): membership, assignment + generation fencing, aligned checkpoint coordination,
 * failure detection and recovery. All shared state is guarded by this object's monitor (every public method is
 * synchronized). {@link #tick()} is driven every 250 ms by CoordinatorMain (or manually by tests with a fake clock).
 */
public class Coordinator {
    public static final long HEARTBEAT_INTERVAL_MS = 1000;
    public static final long HEARTBEAT_TIMEOUT_MS = 4000;
    public static final long CHECKPOINT_TIMEOUT_MS = 30_000;
    public static final long DEAD_WORKER_RETENTION_MS = 60_000;
    public static final long SAVEPOINT_TIMEOUT_MS = 20_000;

    public static final String RUNNING = "RUNNING";
    public static final String RECOVERING = "RECOVERING";
    public static final String STOPPED = "STOPPED";

    private static final Logger log = LoggerFactory.getLogger(Coordinator.class);

    private final ExecutionRepository repo;
    private final CheckpointStore store;
    private final OffsetsLookup offsetsLookup;
    private final Clock clock;
    private final long checkpointIntervalMs;
    private final boolean autoRebalance;
    private final Random random = new Random();
    private static final long REBALANCE_STABILITY_MS = 5_000;
    private static final long REBALANCE_RATE_LIMIT_MS = 30_000;
    private static final long REBALANCE_NEVER = Long.MIN_VALUE;


    /** Sorted by workerId (assignment order). */
    private final TreeMap<String, WorkerEntry> workers = new TreeMap<>();
    /** The current (or last) execution; null before the first start. */
    private ExecState exec;
    private long sinkDelayMs;

    private static final class WorkerEntry {
        final String id;
        final String host;
        long rebalanceCandidateSinceMs = REBALANCE_NEVER;
        long lastHeartbeatMs;
        boolean alive = true;
        long deadSinceMs;
        WorkerStatus status;
        final ArrayDeque<Command> queue = new ArrayDeque<>();

        WorkerEntry(String id, String host, long now) {
            this.id = id;
            this.host = host;
            this.lastHeartbeatMs = now;
        }
    }

    private static final class PendingCheckpoint {
        final long id;
        final long generation;
        final long startedMs;
        final TreeMap<Integer, CheckpointManifest.PartitionEntry> acked = new TreeMap<>();

        PendingCheckpoint(long id, long generation, long startedMs) {
            this.id = id;
            this.generation = generation;
            this.startedMs = startedMs;
        }
    }

    private static final class ExecState {
        final String id;
        final String pipelineId;
        final int pipelineVersion;
        final ExecutionPlan plan;
        final String createdAt;
        String state;
        long generation;
        Long restoredFrom;
        /** workerId -> partitions of the current generation (empty = not assigned). */
        TreeMap<String, List<Integer>> assignment = new TreeMap<>();
        /** workerId -> ASSIGN of the current assignment and when it was last queued (for lost-response resends). */
        Map<String, Command.Assign> assignCommands = new TreeMap<>();
        Map<String, Long> assignSentMs = new TreeMap<>();
        /** workerId -> first heartbeat time at which it reported IDLE in the current generation. */
        Map<String, Long> idleSinceMs = new TreeMap<>();
        /** Checkpoint 0 offsets, used for the very first assignment. */
        Map<Integer, Long> startOffsets = Map.of();
        long initialRestoreCheckpointId;
        String initialManifestKey;
        boolean assignedOnce;
        /** Coordinator restart: do not recover before this time (one heartbeat timeout after boot). */
        Long recoverAtMs;
        Long recoveryStartedMs;
        PendingCheckpoint pending;
        long nextCheckpointId = 1;
        long lastTriggerMs;
        Long lastCompleted;
        Long lastDurationMs;
        boolean rebalanceRequested;
        boolean rebalanceInProgress;
        long lastRebalanceMs = REBALANCE_NEVER;
        long checkpointsCompleted;
        long checkpointsAborted;
        long recoveriesCompleted;
        long lastRecoveryDurationMs;


        ExecState(String id, String pipelineId, int pipelineVersion, ExecutionPlan plan, String createdAt) {
            this.id = id;
            this.pipelineId = pipelineId;
            this.pipelineVersion = pipelineVersion;
            this.plan = plan;
            this.createdAt = createdAt;
        }

        boolean active() {
            return RUNNING.equals(state) || RECOVERING.equals(state);
        }
    }

    public Coordinator(ExecutionRepository repo, CheckpointStore store, OffsetsLookup offsetsLookup, Clock clock,
                       long checkpointIntervalMs) {
        this(repo, store, offsetsLookup, clock, checkpointIntervalMs,
                Boolean.parseBoolean(System.getenv().getOrDefault("AUTO_REBALANCE", "true")));
    }

    Coordinator(ExecutionRepository repo, CheckpointStore store, OffsetsLookup offsetsLookup, Clock clock,
               long checkpointIntervalMs, boolean autoRebalance) {
        this.repo = repo;
        this.store = store;
        this.offsetsLookup = offsetsLookup;
        this.clock = clock;
        this.checkpointIntervalMs = checkpointIntervalMs;
        this.autoRebalance = autoRebalance;
    }

    public static String manifestKey(String executionId, long checkpointId) {
        return "executions/" + executionId + "/checkpoints/" + checkpointId + "/manifest.json";
    }

    // ------------------------------------------------------------------ boot

    /**
     * Coordinator restart (contract 03 section 6): load executions in RUNNING/RECOVERING; the newest one is recovered
     * one heartbeat timeout from now, once workers have re-registered. Older ones are stopped (one execution at a time).
     */
    public synchronized void loadActiveExecutions() {
        List<ExecutionRecord> active = repo.findActive();
        if (active.isEmpty()) {
            return;
        }
        long now = clock.nowMs();
        for (int i = 0; i < active.size() - 1; i++) {
            ExecutionRecord old = active.get(i);
            repo.abortPendingCheckpoints(old.id());
            repo.updateState(old.id(), STOPPED);
            audit("execution.stopped", detail("executionId", old.id(), "reason", "superseded at coordinator restart"));
        }
        ExecutionRecord r = active.get(active.size() - 1);
        ExecState e = new ExecState(r.id(), r.pipelineId(), r.pipelineVersion(), r.plan(),
                Instant.ofEpochMilli(r.createdAtMs()).toString());
        e.state = r.state();
        e.generation = r.generation();
        e.restoredFrom = r.restoredFromCheckpoint();
        e.assignedOnce = true;
        e.recoverAtMs = now + HEARTBEAT_TIMEOUT_MS;
        e.nextCheckpointId = repo.maxCheckpointId(r.id()) + 1;
        repo.latestCompleted(r.id()).ifPresent(c -> {
            e.lastCompleted = c.checkpointId();
            e.lastDurationMs = c.durationMs();
        });
        exec = e;
        log.info("Loaded execution {} (state {}, generation {}); recovering after workers re-register",
                e.id, e.state, e.generation);
    }

    // ------------------------------------------------------------------ worker protocol

    public synchronized RegisterResponse register(RegisterRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("missing body");
        }
        checkProtocol(req.protocolVersion());
        long now = clock.nowMs();
        String id = req.workerId() == null || req.workerId().isBlank() ? newWorkerId() : req.workerId();
        WorkerEntry old = workers.get(id);
        boolean lostWork = old != null && old.alive && ownsPartitions(id);
        WorkerEntry w = new WorkerEntry(id, req.host(), now);
        workers.put(id, w);
        if (sinkDelayMs != 0) {
            w.queue.add(new Command.SetSinkDelay(sinkDelayMs));
        }
        log.info("Worker {} registered (host {})", id, req.host());
        if (!lostWork) {
            markRebalanceCandidate(w, now);
        }
        if (lostWork) {
            // Same id re-registered while it owned partitions: its in-memory state is gone, treat as a failure.
            audit("worker.dead", detail("workerId", id, "executionId", exec.id,
                    "partitions", exec.assignment.get(id), "reason", "re-registered"));
            beginRecovery(now);
        } else {
            maybeAssign(now);
        }
        return new RegisterResponse(id, HEARTBEAT_INTERVAL_MS, HEARTBEAT_TIMEOUT_MS);
    }

    public synchronized HeartbeatResponse heartbeat(String workerId, WorkerStatus status) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("missing workerId");
        }
        if (status != null) {
            checkProtocol(status.protocolVersion());
        }
        long now = clock.nowMs();
        WorkerEntry w = workers.get(workerId);
        if (w == null || !w.alive) {
            // Unknown (coordinator restarted) or previously declared dead: (re)admit as a fresh member.
            w = new WorkerEntry(workerId, w == null ? null : w.host, now);
            workers.put(workerId, w);
            if (sinkDelayMs != 0) {
                w.queue.add(new Command.SetSinkDelay(sinkDelayMs));
            }
            log.info("Worker {} (re)admitted by heartbeat", workerId);
            markRebalanceCandidate(w, now);
        }
        w.lastHeartbeatMs = now;
        w.status = status;
        if (status != null && status.executionId() != null && !"IDLE".equals(status.state())
                && !isCurrentMember(workerId, status.executionId())) {
            // Worker still runs work that is no longer assigned to it (stale generation / old execution): stop it.
            Command cancel = new Command.Cancel(status.executionId(), status.generation());
            if (!w.queue.contains(cancel)) {
                w.queue.add(cancel);
            }
        }
        maybeResendAssign(w, status, now);
        maybeAssign(now);
        checkRecoveryComplete(now);
        maybeScheduleAutoRebalance(now);
        List<Command> commands = new ArrayList<>(w.queue);
        w.queue.clear();
        return new HeartbeatResponse(commands);
    }

    public synchronized AckResponse ack(CheckpointAck ack) {
        if (ack == null) {
            throw new IllegalArgumentException("missing body");
        }
        checkProtocol(ack.protocolVersion());
        ExecState e = exec;
        if (e == null || !e.id.equals(ack.executionId())) {
            return new AckResponse(false, "unknown execution");
        }
        if (ack.generation() != e.generation) {
            return new AckResponse(false, "stale generation " + ack.generation() + " (current " + e.generation + ")");
        }
        PendingCheckpoint p = e.pending;
        if (p == null || p.id != ack.checkpointId()) {
            if (e.lastCompleted != null && ack.checkpointId() <= e.lastCompleted) {
                return new AckResponse(true, "already completed");
            }
            return new AckResponse(false, "checkpoint " + ack.checkpointId() + " is not pending");
        }
        List<Integer> owned = e.assignment.get(ack.workerId());
        if (owned == null) {
            return new AckResponse(false, "worker " + ack.workerId() + " is not assigned in generation " + e.generation);
        }
        if (ack.partitions() != null) {
            for (Map.Entry<Integer, Long> entry : ack.partitions().entrySet()) {
                if (owned.contains(entry.getKey()) && entry.getValue() != null) {
                    p.acked.put(entry.getKey(), new CheckpointManifest.PartitionEntry(entry.getValue(), ack.objectKey()));
                } else {
                    log.warn("Ignoring partition {} in ack from {}: not assigned to it", entry.getKey(), ack.workerId());
                }
            }
        }
        if (p.acked.size() == e.plan.partitions()) {
            completeCheckpoint(e, p, clock.nowMs());
        }
        return new AckResponse(true, null);
    }

    // ------------------------------------------------------------------ control endpoints

    public ExecutionInfo startExecution(StartExecutionRequest req) {
        return startExecutionFromSavepoint(req == null ? null
                : new StartFromSavepointRequest(req.executionId(), req.plan(), null));
    }

    public ExecutionInfo startExecutionFromSavepoint(StartFromSavepointRequest req) {
        if (req == null || req.executionId() == null || req.executionId().isBlank() || req.plan() == null) {
            throw new IllegalArgumentException("executionId and plan are required");
        }
        ExecutionPlan plan = req.plan();
        if (plan.partitions() <= 0 || plan.topic() == null) {
            throw new IllegalArgumentException("plan needs a topic and partitions > 0");
        }
        RestoredSavepoint restored;
        synchronized (this) {
            if (exec != null && exec.id.equals(req.executionId())) {
                return info(exec);
            }
            // Validate before Kafka lookup or stopping the current execution.
            restored = req.fromSavepoint() == null ? null : compatibleSavepoint(req.fromSavepoint(), plan);
        }
        // Kafka lookup outside the lock (it may take a while).
        Map<Integer, Long> looked = restored == null
                ? offsetsLookup.offsets(plan.topic(), plan.partitions(), plan.startFrom())
                : manifestOffsets(restored.manifest());
        Map<Integer, Long> offsets = new TreeMap<>();
        for (int p = 0; p < plan.partitions(); p++) {
            Long o = looked == null ? null : looked.get(p);
            offsets.put(p, o == null ? 0L : o);
        }
        synchronized (this) {
            if (exec != null && exec.id.equals(req.executionId())) {
                return info(exec);
            }
            long now = clock.nowMs();
            if (exec != null && exec.active()) {
                stopInternal(exec, "superseded by " + req.executionId());
            }
            ExecState e = new ExecState(req.executionId(), plan.pipelineId(), plan.pipelineVersion(), plan,
                    Instant.ofEpochMilli(now).toString());
            e.state = RUNNING;
            e.generation = 1;
            e.startOffsets = offsets;
            if (restored != null) {
                e.initialRestoreCheckpointId = restored.manifest().checkpointId();
                e.initialManifestKey = restored.key();
                e.restoredFrom = e.initialRestoreCheckpointId;
            }
            e.lastCompleted = 0L;
            e.lastDurationMs = 0L;
            e.lastTriggerMs = now;
            repo.insertExecution(new ExecutionRecord(e.id, e.pipelineId, e.pipelineVersion, plan, RUNNING, 1,
                    e.restoredFrom, now));
            repo.insertCheckpoint(new CheckpointRecord(e.id, 0, 1, CheckpointRecord.COMPLETED, e.initialManifestKey, offsets, now,
                    now, 0L));
            audit("execution.started", detail("executionId", e.id, "pipelineId", e.pipelineId,
                    "generation", 1L, "checkpoint0Offsets", offsets));
            exec = e;
            log.info("Execution {} started (pipeline {} v{}, {} partitions)", e.id, e.pipelineId, e.pipelineVersion,
                    plan.partitions());
            maybeAssign(now);
            return info(e);
        }
    }

    public synchronized ExecutionInfo stopExecution(String executionId) {
        ExecState e = requireExecution(executionId);
        if (e.active()) {
            stopInternal(e, "stopped by request");
        }
        return info(e);
    }

    /** Triggers a checkpoint now (or returns the id of the one already pending). */
    public synchronized long triggerCheckpointNow(String executionId) {
        ExecState e = requireExecution(executionId);
        if (!RUNNING.equals(e.state) || e.assignment.isEmpty()) {
            throw new IllegalStateException("execution " + executionId + " is " + e.state
                    + (e.assignment.isEmpty() ? " and has no assigned workers" : ""));
        }
        if (e.pending != null) {
            return e.pending.id;
        }
        return triggerCheckpoint(e, clock.nowMs());
    }

    /**
     * Waits at most 20 seconds for the aligned checkpoint, releasing the monitor so heartbeat/ack requests can proceed.
     * The copied manifest retains contract 03 fields and adds the immutable plan used for compatibility checks.
     * Worker snapshot objects referenced by it must be retained with the savepoint.
     */
    public synchronized SavepointInfo createSavepoint(String executionId) throws InterruptedException {
        ExecState e = requireExecution(executionId);
        long checkpointId = triggerCheckpointNow(executionId);
        long started = clock.nowMs();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SAVEPOINT_TIMEOUT_MS);
        CheckpointRecord cp;
        while (true) {
            cp = repo.findCheckpoint(executionId, checkpointId).orElseThrow();
            if (CheckpointRecord.COMPLETED.equals(cp.state())) {
                break;
            }
            if (!CheckpointRecord.PENDING.equals(cp.state())) {
                throw new IllegalStateException("savepoint checkpoint " + checkpointId + " was " + cp.state());
            }
            long remaining = Math.min(SAVEPOINT_TIMEOUT_MS - (clock.nowMs() - started),
                    TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
            if (remaining <= 0) {
                throw new IllegalStateException("savepoint checkpoint " + checkpointId + " did not complete within 20 seconds");
            }
            wait(remaining);
        }
        String id = "sp-" + executionId + "-" + checkpointId;
        for (SavepointInfo existing : repo.listSavepoints()) {
            if (existing.savepointId().equals(id)) {
                return existing;
            }
        }
        ObjectNode manifest = store.getJson(cp.manifestKey(), ObjectNode.class);
        if (manifest == null) {
            throw new IllegalStateException("completed checkpoint manifest is missing");
        }
        manifest.set("plan", Json.MAPPER.valueToTree(e.plan));
        SavepointInfo saved = new SavepointInfo(id, executionId, checkpointId, e.pipelineId, e.pipelineVersion,
                Protocol.VERSION, e.plan.operators().stream().map(PlannedOperator::id).toList(),
                Instant.ofEpochMilli(clock.nowMs()).toString());
        store.putJson(saved.manifestKey(), manifest);
        audit("savepoint.created", detail("savepointId", id, "executionId", executionId,
                "checkpointId", checkpointId, "pipelineId", saved.pipelineId(), "pipelineVersion", saved.pipelineVersion(),
                "protocolVersion", saved.protocolVersion(), "operatorIds", saved.operatorIds(), "createdAt", saved.createdAt()));
        return saved;
    }

    public synchronized List<SavepointInfo> listSavepoints() {
        return repo.listSavepoints();
    }

    private record RestoredSavepoint(CheckpointManifest manifest, String key) {}

    private RestoredSavepoint compatibleSavepoint(String id, ExecutionPlan target) {
        SavepointInfo saved = repo.listSavepoints().stream().filter(s -> s.savepointId().equals(id)).findFirst()
                .orElseThrow(() -> new NoSuchElementException("unknown savepoint " + id));
        CheckpointRecord cp = repo.findCheckpoint(saved.executionId(), saved.checkpointId()).orElseThrow();
        if (!CheckpointRecord.COMPLETED.equals(cp.state())) {
            throw new IllegalStateException("savepoint checkpoint is not COMPLETED");
        }
        JsonNode json = store.getJson(saved.manifestKey(), JsonNode.class);
        if (json == null || !json.hasNonNull("plan")) {
            throw new IllegalStateException("savepoint manifest or saved plan is missing");
        }
        CheckpointManifest manifest = Json.MAPPER.convertValue(json, CheckpointManifest.class);
        ExecutionPlan source = Json.MAPPER.convertValue(json.get("plan"), ExecutionPlan.class);
        if (!Objects.equals(manifest.pipelineId(), target.pipelineId())) {
            throw new IncompatibleSavepointException("pipelineId differs: savepoint uses " + manifest.pipelineId()
                    + " but requested pipeline is " + target.pipelineId());
        }
        if (manifest.protocolVersion() != Protocol.VERSION) {
            throw new IncompatibleSavepointException("protocolVersion differs: savepoint uses " + manifest.protocolVersion()
                    + " but coordinator uses " + Protocol.VERSION);
        }
        if (source.partitions() != target.partitions()) {
            throw new IncompatibleSavepointException("partition count differs: savepoint has " + source.partitions()
                    + " but requested plan has " + target.partitions());
        }
        if (!Objects.equals(source.topic(), target.topic())) {
            throw new IncompatibleSavepointException("source topic differs: saved offsets belong to " + source.topic());
        }
        Map<String, PlannedOperator> previous = new TreeMap<>();
        Map<String, PlannedOperator> next = new TreeMap<>();
        source.operators().forEach(op -> previous.put(op.id(), op));
        target.operators().forEach(op -> next.put(op.id(), op));
        for (String operatorId : previous.keySet()) {
            if (!next.containsKey(operatorId)) {
                throw new IncompatibleSavepointException("operator '" + operatorId + "' was removed");
            }
        }
        for (String operatorId : next.keySet()) {
            if (!previous.containsKey(operatorId)) {
                throw new IncompatibleSavepointException("operator '" + operatorId + "' was added");
            }
            PlannedOperator before = previous.get(operatorId);
            PlannedOperator after = next.get(operatorId);
            if (stateful(before) || stateful(after)) {
                if (!Objects.equals(before.type(), after.type())) {
                    throw new IncompatibleSavepointException("stateful operator '" + operatorId + "' type changed from "
                            + before.type() + " to " + after.type());
                }
                if (!sameConfig(before.config(), after.config())) {
                    throw new IncompatibleSavepointException("stateful " + before.type() + " operator '" + operatorId
                            + "' configuration changed: saved " + before.config() + ", requested " + after.config());
                }
            }
        }
        if (manifest.partitions() == null || manifest.partitions().size() != target.partitions()) {
            throw new IllegalStateException("savepoint manifest does not cover all partitions");
        }
        for (int p = 0; p < target.partitions(); p++) {
            if (manifest.partitions().get(p) == null) {
                throw new IllegalStateException("savepoint manifest is missing partition " + p);
            }
        }
        return new RestoredSavepoint(manifest, saved.manifestKey());
    }

    private static boolean sameConfig(JsonNode before, JsonNode after) {
        // JSON round trips can change IntNode/LongNode/DoubleNode without changing the numeric configuration.
        return before == null ? after == null : after != null && before.equals((left, right) ->
                left.isNumber() && right.isNumber() ? left.decimalValue().compareTo(right.decimalValue())
                        : left.equals(right) ? 0 : 1, after);
    }

    private static boolean stateful(PlannedOperator op) {
        return "window".equals(op.type()) || "rule".equals(op.type());
    }

    private static Map<Integer, Long> manifestOffsets(CheckpointManifest manifest) {
        Map<Integer, Long> offsets = new TreeMap<>();
        manifest.partitions().forEach((p, entry) -> offsets.put(p, entry.nextOffset()));
        return offsets;
    }

    /** Chaos: KILL to the given worker, or (null) to the live worker owning the most partitions. */
    public synchronized String killWorker(String workerId) {
        WorkerEntry target;
        if (workerId == null || workerId.isBlank()) {
            target = null;
            int best = -1;
            for (WorkerEntry w : workers.values()) {
                if (!w.alive) {
                    continue;
                }
                int owned = ownedPartitions(w.id).size();
                if (owned > best) {
                    best = owned;
                    target = w;
                }
            }
            if (target == null) {
                throw new IllegalStateException("no live worker to kill");
            }
        } else {
            target = workers.get(workerId);
            if (target == null || !target.alive) {
                throw new NoSuchElementException("no live worker " + workerId);
            }
        }
        target.queue.add(new Command.Kill());
        audit("chaos.kill", detail("workerId", target.id));
        log.info("Chaos: KILL queued for worker {}", target.id);
        return target.id;
    }

    /** Chaos: SET_SINK_DELAY to every live worker; remembered for workers that register later. */
    public synchronized long setSinkDelay(long delayMs) {
        if (delayMs < 0) {
            throw new IllegalArgumentException("delayMs must be >= 0");
        }
        sinkDelayMs = delayMs;
        for (WorkerEntry w : workers.values()) {
            if (w.alive) {
                w.queue.removeIf(c -> c instanceof Command.SetSinkDelay);
                w.queue.add(new Command.SetSinkDelay(delayMs));
            }
        }
        audit("chaos.sinkDelay", detail("delayMs", delayMs));
        return delayMs;
    }

    public synchronized ClusterState clusterState() {
        ClusterState.ExecutionSummary summary = null;
        if (exec != null) {
            summary = new ClusterState.ExecutionSummary(exec.id, exec.pipelineId, exec.state, exec.generation,
                    exec.restoredFrom, exec.lastCompleted, exec.lastDurationMs);
        }
        List<ClusterState.WorkerView> views = new ArrayList<>();
        for (WorkerEntry w : workers.values()) {
            WorkerStatus s = w.status;
            String state = !w.alive ? "DEAD" : s == null || s.state() == null ? "IDLE" : s.state();
            views.add(new ClusterState.WorkerView(w.id, w.alive, state, ownedPartitions(w.id),
                    s == null ? null : s.sourceQueue(), s == null ? null : s.sinkQueue(),
                    s != null && s.backpressured(), s == null ? 0.0 : s.recordsPerSec(), w.lastHeartbeatMs));
        }
        return new ClusterState(summary, views, sinkDelayMs);
    }

    public synchronized ExecutionInfo executionInfo(String executionId) {
        return info(requireExecution(executionId));
    }
    public synchronized long requestRebalance(String executionId) {
        ExecState e = requireExecution(executionId);
        long now = clock.nowMs();
        if (!RUNNING.equals(e.state)) {
            throw new IllegalStateException("execution " + executionId + " is " + e.state);
        }
        if (!rebalanceNeeded(e) || !allAssignedRunning(e)) {
            throw new IllegalStateException("execution " + executionId + " does not need a rebalance");
        }
        if (rateLimited(e, now)) {
            throw new IllegalStateException("execution " + executionId + " was rebalanced less than 30 s ago");
        }
        return scheduleRebalance(e, now);
    }

    /** Test probe: the longest command queue of any known worker (queues must stay bounded). */
    synchronized int maxQueuedCommands() {
        int max = 0;
        for (WorkerEntry w : workers.values()) {
            max = Math.max(max, w.queue.size());
        }
        return max;
    }

    public synchronized String metricsText() {
        ExecState e = exec;
        StringBuilder out = new StringBuilder(512);
        out.append("# TYPE aether_worker_records_per_sec gauge\n")
                .append("# TYPE aether_worker_queue_size gauge\n")
                .append("# TYPE aether_worker_backpressured gauge\n");
        metric(out, "aether_workers_alive", "gauge", workers.values().stream().filter(w -> w.alive).count());
        metric(out, "aether_execution_generation", "gauge", e == null ? 0 : e.generation);
        metric(out, "aether_checkpoints_completed_total", "counter", e == null ? 0 : e.checkpointsCompleted);
        metric(out, "aether_checkpoints_aborted_total", "counter", e == null ? 0 : e.checkpointsAborted);
        metric(out, "aether_last_checkpoint_duration_ms", "gauge",
                e == null || e.lastDurationMs == null ? 0 : e.lastDurationMs);
        metric(out, "aether_recoveries_total", "counter", e == null ? 0 : e.recoveriesCompleted);
        metric(out, "aether_last_recovery_duration_ms", "gauge", e == null ? 0 : e.lastRecoveryDurationMs);
        for (WorkerEntry w : workers.values()) {
            WorkerStatus s = w.status;
            sample(out, "aether_worker_records_per_sec", "worker", w.id, null, null,
                    s == null ? 0 : s.recordsPerSec());
            sample(out, "aether_worker_queue_size", "worker", w.id, "queue", "source",
                    s == null || s.sourceQueue() == null ? 0 : s.sourceQueue().size());
            sample(out, "aether_worker_queue_size", "worker", w.id, "queue", "sink",
                    s == null || s.sinkQueue() == null ? 0 : s.sinkQueue().size());
            sample(out, "aether_worker_backpressured", "worker", w.id, null, null,
                    s != null && s.backpressured() ? 1 : 0);
        }
        return out.toString();
    }

    private static void metric(StringBuilder out, String name, String type, long value) {
        out.append("# TYPE ").append(name).append(' ').append(type).append('\n')
                .append(name).append(' ').append(value).append('\n');
    }

    private static void sample(StringBuilder out, String name, String label, String value, String label2,
                               String value2, double sample) {
        out.append(name).append('{').append(label).append("=\"").append(prometheusEscape(value)).append('"');
        if (label2 != null) {
            out.append(',').append(label2).append("=\"").append(prometheusEscape(value2)).append('"');
        }
        out.append("} ").append(sample).append('\n');
    }

    private static String prometheusEscape(String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"");
    }

    // ------------------------------------------------------------------ periodic driver

    /** One scheduler tick: failure detection, assignment, recovery completion, checkpoint timeout and trigger. */
    public synchronized void tick() {
        long now = clock.nowMs();
        detectFailures(now);
        maybeAssign(now);
        checkRecoveryComplete(now);
        checkCheckpointTimeout(now);
        maybeTriggerCheckpoint(now);
        maybeScheduleAutoRebalance(now);
        pruneDeadWorkers(now);
        notifyAll();
    }

    // ------------------------------------------------------------------ internals (caller holds the lock)

    private void detectFailures(long now) {
        boolean affected = false;
        for (WorkerEntry w : workers.values()) {
            if (w.alive && now - w.lastHeartbeatMs >= HEARTBEAT_TIMEOUT_MS) {
                w.alive = false;
                w.deadSinceMs = now;
                w.queue.clear();
                List<Integer> owned = exec != null && exec.active() ? exec.assignment.get(w.id) : null;
                audit("worker.dead", detail("workerId", w.id, "executionId", exec == null ? null : exec.id,
                        "partitions", owned, "lastHeartbeatMs", w.lastHeartbeatMs));
                log.warn("Worker {} declared DEAD (no heartbeat for {} ms)", w.id, now - w.lastHeartbeatMs);
                if (owned != null) {
                    affected = true;
                }
            }
        }
        if (!affected && exec != null && exec.active()) {
            // Self-heal: an assigned worker that is dead or gone (a previous tick failed half-way, e.g. a DB error
            // inside beginRecovery) must still trigger recovery, otherwise the execution waits for it forever.
            for (String id : exec.assignment.keySet()) {
                WorkerEntry w = workers.get(id);
                if (w == null || !w.alive) {
                    affected = true;
                    break;
                }
            }
        }
        if (affected) {
            beginRecovery(now);
        }
    }

    /** A worker owning partitions failed: RECOVERING, abort pending checkpoints, reassign when workers are available. */
    private void beginRecovery(long now) {
        ExecState e = exec;
        if (!RECOVERING.equals(e.state)) {
            e.state = RECOVERING;
            repo.updateState(e.id, RECOVERING);
        }
        e.rebalanceRequested = false;
        e.rebalanceInProgress = false;
        if (e.recoveryStartedMs == null) {
            e.recoveryStartedMs = now;
        }
        abortPending(e);
        e.assignment = new TreeMap<>();
        e.assignedOnce = true;
        e.recoverAtMs = null;
        maybeAssign(now);
    }

    /** Assigns an active execution that has no assignment, if live workers exist (and the restart grace has passed). */
    private void maybeAssign(long now) {
        ExecState e = exec;
        if (e == null || !e.active() || !e.assignment.isEmpty()) {
            return;
        }
        if (e.recoverAtMs != null && now < e.recoverAtMs) {
            return;
        }
        List<String> live = liveWorkerIds();
        if (live.isEmpty()) {
            return;
        }
        if (!e.assignedOnce) {
            assign(e, live, e.initialRestoreCheckpointId, e.initialManifestKey, e.startOffsets, now);
            log.info("Execution {} assigned (generation {}): {}", e.id, e.generation, e.assignment);
        } else {
            try {
                recoverAssign(e, live, now);
            } catch (RuntimeException ex) {
                // Nothing was assigned (assignment still empty): the next tick retries.
                log.error("Recovery of execution {} failed; will retry", e.id, ex);
            }
        }
    }

    /** Contract 03 section 6: latest COMPLETED checkpoint, bump generation, CANCEL old + ASSIGN new. */
    private void recoverAssign(ExecState e, List<String> live, long now) {
        e.recoverAtMs = null;
        if (!RECOVERING.equals(e.state)) {
            e.state = RECOVERING;
            repo.updateState(e.id, RECOVERING);
        }
        if (e.recoveryStartedMs == null) {
            e.recoveryStartedMs = now;
        }
        abortPending(e);
        CheckpointRecord cp = repo.latestCompleted(e.id)
                .orElseThrow(() -> new IllegalStateException("execution " + e.id + " has no COMPLETED checkpoint"));
        Map<Integer, Long> startOffsets = new TreeMap<>();
        String manifestKey = null;
        long restoreCheckpointId = cp.checkpointId();
        if (cp.manifestKey() == null && cp.checkpointId() == 0) {
            if (cp.sourceOffsets() != null) {
                startOffsets.putAll(cp.sourceOffsets());
            }
        } else {
            manifestKey = cp.manifestKey() != null ? cp.manifestKey() : manifestKey(e.id, cp.checkpointId());
            CheckpointManifest manifest = store.getJson(manifestKey, CheckpointManifest.class);
            if (manifest == null) {
                throw new IllegalStateException("manifest " + manifestKey + " of COMPLETED checkpoint is missing");
            }
            manifest.partitions().forEach((p, entry) -> startOffsets.put(p, entry.nextOffset()));
            restoreCheckpointId = manifest.checkpointId();
        }
        long oldGeneration = e.generation;
        long newGeneration = repo.bumpGeneration(e.id);
        // Memory must follow the DB fencing token at once: if a later repo call fails, a lagging in-memory
        // generation would hand out ASSIGNs whose sink transactions are all rejected as fenced.
        e.generation = newGeneration;
        e.restoredFrom = restoreCheckpointId;
        repo.setRestoredFrom(e.id, restoreCheckpointId);
        for (String id : live) {
            WorkerEntry w = workers.get(id);
            w.queue.removeIf(c -> isForExecution(c, e.id));
            w.queue.add(new Command.Cancel(e.id, oldGeneration));
        }
        assign(e, live, restoreCheckpointId, manifestKey, startOffsets, now);
        audit("execution.recovering", detail("executionId", e.id, "checkpointId", cp.checkpointId(),
                "generation", newGeneration, "previousGeneration", oldGeneration, "assignment", e.assignment));
        log.warn("Execution {} RECOVERING from checkpoint {} with generation {}: {}", e.id, cp.checkpointId(),
                newGeneration, e.assignment);
    }

    /** Round-robin partitions 0..N-1 over the sorted live workers and queue ASSIGN commands. */
    private void assign(ExecState e, List<String> live, long checkpointId, String manifestKey,
                        Map<Integer, Long> offsets, long now) {
        TreeMap<String, List<Integer>> a = new TreeMap<>();
        for (int p = 0; p < e.plan.partitions(); p++) {
            a.computeIfAbsent(live.get(p % live.size()), k -> new ArrayList<>()).add(p);
        }
        e.assignment = a;
        e.assignedOnce = true;
        e.lastTriggerMs = now;
        e.assignCommands = new TreeMap<>();
        e.assignSentMs = new TreeMap<>();
        e.idleSinceMs = new TreeMap<>();
        for (Map.Entry<String, List<Integer>> entry : a.entrySet()) {
            Map<Integer, Long> own = new TreeMap<>();
            for (Integer p : entry.getValue()) {
                own.put(p, offsets.getOrDefault(p, 0L));
            }
            Command.Assign cmd = new Command.Assign(e.id, e.generation, e.plan,
                    List.copyOf(entry.getValue()), checkpointId, manifestKey, own);
            workers.get(entry.getKey()).queue.add(cmd);
            e.assignCommands.put(entry.getKey(), cmd);
            e.assignSentMs.put(entry.getKey(), now);
        }
    }

    private void checkRecoveryComplete(long now) {
        ExecState e = exec;
        if (e == null || !RECOVERING.equals(e.state) || e.assignment.isEmpty() || !allAssignedRunning(e)) {
            return;
        }
        e.state = RUNNING;
        repo.updateState(e.id, RUNNING);
        long duration = e.recoveryStartedMs == null ? 0 : now - e.recoveryStartedMs;
        e.recoveryStartedMs = null;
        e.lastTriggerMs = now;
        if (e.rebalanceInProgress) {
            audit("execution.rebalanced", detail("executionId", e.id, "checkpointId", e.restoredFrom,
                    "generation", e.generation, "assignment", e.assignment, "durationMs", duration));
            e.rebalanceInProgress = false;
        } else {
            e.recoveriesCompleted++;
            e.lastRecoveryDurationMs = duration;
            audit("execution.recovered", detail("executionId", e.id, "checkpointId", e.restoredFrom,
                    "generation", e.generation, "recoveryDurationMs", duration));
        }
        log.info("Execution {} RECOVERED from checkpoint {} (generation {}) in {} ms", e.id, e.restoredFrom,
                e.generation, duration);
    }

    private boolean allAssignedRunning(ExecState e) {
        if (e.assignment.isEmpty()) {
            return false; // e.g. the restart grace window: nothing runs yet, so no checkpoint/rebalance may start
        }
        for (String id : e.assignment.keySet()) {
            WorkerEntry w = workers.get(id);
            if (w == null || !w.alive || w.status == null) {
                return false;
            }
            WorkerStatus s = w.status;
            if (!e.id.equals(s.executionId()) || s.generation() != e.generation || !RUNNING.equals(s.state())) {
                return false;
            }
        }
        return true;
    }

    private boolean rebalanceNeeded(ExecState e) {
        List<String> live = liveWorkerIds();
        if (live.isEmpty()) {
            return false;
        }
        int minimum = Integer.MAX_VALUE;
        int maximum = 0;
        for (String id : live) {
            int count = e.assignment.getOrDefault(id, List.of()).size();
            minimum = Math.min(minimum, count);
            maximum = Math.max(maximum, count);
        }
        return maximum - minimum >= 2 || minimum == 0;
    }

    private boolean rateLimited(ExecState e, long now) {
        return e.lastRebalanceMs != REBALANCE_NEVER && now - e.lastRebalanceMs < REBALANCE_RATE_LIMIT_MS;
    }

    private long scheduleRebalance(ExecState e, long now) {
        e.rebalanceRequested = true;
        e.lastRebalanceMs = now;
        for (WorkerEntry worker : workers.values()) {
            worker.rebalanceCandidateSinceMs = REBALANCE_NEVER;
        }
        return e.pending == null ? triggerCheckpoint(e, now) : e.pending.id;
    }

    private void maybeScheduleAutoRebalance(long now) {
        ExecState e = exec;
        if (!autoRebalance || e == null || !RUNNING.equals(e.state) || e.rebalanceRequested
                || !rebalanceNeeded(e) || !allAssignedRunning(e) || rateLimited(e, now)) {
            return;
        }
        for (WorkerEntry worker : workers.values()) {
            if (worker.alive && worker.rebalanceCandidateSinceMs != REBALANCE_NEVER
                    && now - worker.rebalanceCandidateSinceMs >= REBALANCE_STABILITY_MS) {
                scheduleRebalance(e, now);
                return;
            }
        }
    }

    private void performRebalance(ExecState e, long now) {
        List<String> live = liveWorkerIds();
        if (live.isEmpty() || !RUNNING.equals(e.state)) {
            return;
        }
        CheckpointRecord cp = repo.latestCompleted(e.id)
                .orElseThrow(() -> new IllegalStateException("execution " + e.id + " has no COMPLETED checkpoint"));
        Map<Integer, Long> offsets = new TreeMap<>();
        String key = null;
        long restoreCheckpointId = cp.checkpointId();
        if (cp.manifestKey() == null && cp.checkpointId() == 0) {
            if (cp.sourceOffsets() != null) {
                offsets.putAll(cp.sourceOffsets());
            }
        } else {
            key = cp.manifestKey() != null ? cp.manifestKey() : manifestKey(e.id, cp.checkpointId());
            CheckpointManifest manifest = store.getJson(key, CheckpointManifest.class);
            if (manifest == null) {
                throw new IllegalStateException("manifest " + key + " of COMPLETED checkpoint is missing");
            }
            manifest.partitions().forEach((partition, entry) -> offsets.put(partition, entry.nextOffset()));
            restoreCheckpointId = manifest.checkpointId();
        }
        long oldGeneration = e.generation;
        e.state = RECOVERING;
        e.rebalanceInProgress = true;
        e.recoveryStartedMs = now;
        repo.updateState(e.id, RECOVERING);
        e.generation = repo.bumpGeneration(e.id);
        e.restoredFrom = restoreCheckpointId;
        repo.setRestoredFrom(e.id, restoreCheckpointId);
        for (WorkerEntry worker : workers.values()) {
            if (worker.alive) {
                worker.queue.removeIf(c -> isForExecution(c, e.id));
                worker.queue.add(new Command.Cancel(e.id, oldGeneration));
            }
        }
        assign(e, live, restoreCheckpointId, key, offsets, now);
    }

    private void maybeTriggerCheckpoint(long now) {
        ExecState e = exec;
        if (e == null || !RUNNING.equals(e.state) || e.assignment.isEmpty() || e.pending != null) {
            return;
        }
        if (now - e.lastTriggerMs < checkpointIntervalMs || !allAssignedRunning(e)) {
            return;
        }
        triggerCheckpoint(e, now);
    }

    private long triggerCheckpoint(ExecState e, long now) {
        long id = e.nextCheckpointId++;
        repo.insertCheckpoint(new CheckpointRecord(e.id, id, e.generation, CheckpointRecord.PENDING, null, null, now,
                null, null));
        e.pending = new PendingCheckpoint(id, e.generation, now);
        e.lastTriggerMs = now;
        for (String workerId : e.assignment.keySet()) {
            workers.get(workerId).queue.add(new Command.TriggerCheckpoint(e.id, e.generation, id));
        }
        log.info("Checkpoint {} triggered for execution {} (generation {})", id, e.id, e.generation);
        return id;
    }

    /** All partitions acked: manifest first, then COMPLETED (contract 03 section 4). */
    private void completeCheckpoint(ExecState e, PendingCheckpoint p, long now) {
        String key = manifestKey(e.id, p.id);
        CheckpointManifest manifest = new CheckpointManifest(e.id, p.id, p.generation, e.pipelineId,
                e.pipelineVersion, Protocol.VERSION, Instant.ofEpochMilli(now).toString(), new TreeMap<>(p.acked));
        try {
            store.putJson(key, manifest);
        } catch (RuntimeException ex) {
            log.error("Manifest upload for checkpoint {} failed; aborting it", p.id, ex);
            repo.abortCheckpoint(e.id, p.id);
            e.pending = null;
            e.checkpointsAborted++;
            notifyAll();
            return;
        }
        Map<Integer, Long> offsets = new TreeMap<>();
        p.acked.forEach((partition, entry) -> offsets.put(partition, entry.nextOffset()));
        long duration = now - p.startedMs;
        repo.completeCheckpoint(e.id, p.id, key, offsets, now, duration);
        e.pending = null;
        e.lastCompleted = p.id;
        e.lastDurationMs = duration;
        e.checkpointsCompleted++;
        notifyAll();
        if (e.rebalanceRequested) {
            e.rebalanceRequested = false;
            performRebalance(e, now);
        }
        log.info("Checkpoint {} of execution {} COMPLETED in {} ms", p.id, e.id, duration);
    }

    private void checkCheckpointTimeout(long now) {
        ExecState e = exec;
        if (e == null || e.pending == null || now - e.pending.startedMs < CHECKPOINT_TIMEOUT_MS) {
            return;
        }
        e.checkpointsAborted++;
        log.warn("Checkpoint {} of execution {} timed out; ABORTED", e.pending.id, e.id);
        repo.abortCheckpoint(e.id, e.pending.id);
        removeQueuedTriggers(e.id);
        e.pending = null;
        e.lastTriggerMs = now;
        notifyAll();
    }

    private void abortPending(ExecState e) {
        if (e.pending != null) {
            e.checkpointsAborted++;
        }
        repo.abortPendingCheckpoints(e.id);
        e.pending = null;
        removeQueuedTriggers(e.id);
        notifyAll();
    }

    private void stopInternal(ExecState e, String reason) {
        abortPending(e);
        for (String id : e.assignment.keySet()) {
            WorkerEntry w = workers.get(id);
            if (w != null && w.alive) {
                w.queue.removeIf(c -> isForExecution(c, e.id));
                w.queue.add(new Command.Cancel(e.id, e.generation));
            }
        }
        e.assignment = new TreeMap<>();
        e.state = STOPPED;
        e.recoverAtMs = null;
        e.recoveryStartedMs = null;
        repo.updateState(e.id, STOPPED);
        audit("execution.stopped", detail("executionId", e.id, "generation", e.generation, "reason", reason));
        log.info("Execution {} STOPPED ({})", e.id, reason);
    }

    private void pruneDeadWorkers(long now) {
        workers.values().removeIf(w -> !w.alive && now - w.deadSinceMs >= DEAD_WORKER_RETENTION_MS
                && (exec == null || !exec.assignment.containsKey(w.id)));
    }

    private void removeQueuedTriggers(String executionId) {
        for (WorkerEntry w : workers.values()) {
            w.queue.removeIf(c -> c instanceof Command.TriggerCheckpoint t && t.executionId().equals(executionId));
        }
    }

    private static boolean isForExecution(Command c, String executionId) {
        return (c instanceof Command.Assign a && executionId.equals(a.executionId()))
                || (c instanceof Command.TriggerCheckpoint t && executionId.equals(t.executionId()))
                || (c instanceof Command.Cancel x && executionId.equals(x.executionId()));
    }

    /**
     * A worker that joins while an execution already has an assignment it is not part of (RUNNING or mid-recovery)
     * becomes a rebalance candidate; otherwise a worker that rejoined during RECOVERING would stay idle forever.
     */
    private void markRebalanceCandidate(WorkerEntry w, long now) {
        if (autoRebalance && exec != null && exec.active() && !exec.assignment.isEmpty()
                && !exec.assignment.containsKey(w.id)) {
            w.rebalanceCandidateSinceMs = now;
        }
    }

    /**
     * Commands are drained into the heartbeat response; if that response is lost (worker-side timeout while the
     * coordinator was slow) the ASSIGN is gone and the execution would wait for this worker forever. The worker
     * handles every response before its next heartbeat, so a member that still reports an older generation (or is
     * IDLE in the current one because its runtime failed) did not get its ASSIGN or lost its runtime: send the ASSIGN
     * again (idempotent per generation) plus the pending checkpoint trigger. At most once per two heartbeat intervals;
     * a member IDLE in the current generation only after it has stayed IDLE for one heartbeat timeout.
     */
    private void maybeResendAssign(WorkerEntry w, WorkerStatus status, long now) {
        ExecState e = exec;
        if (status == null || e == null || !e.active() || !e.assignment.containsKey(w.id)) {
            return;
        }
        Command.Assign assign = e.assignCommands.get(w.id);
        if (assign == null || assign.generation() != e.generation) {
            return;
        }
        boolean sameExec = e.id.equals(status.executionId());
        if (sameExec && status.generation() > e.generation) {
            return;
        }
        if (sameExec && status.generation() == e.generation) {
            if (!"IDLE".equals(status.state())) {
                e.idleSinceMs.remove(w.id);
                return; // RESTORING or RUNNING the current assignment
            }
            // Its runtime stopped by itself (source/restore failure, or a final IDLE report while shutting down):
            // give it one heartbeat timeout (a dying worker is declared dead by then) before restarting it.
            long idleSince = e.idleSinceMs.computeIfAbsent(w.id, k -> now);
            if (now - idleSince < HEARTBEAT_TIMEOUT_MS) {
                return;
            }
        }
        Long lastSent = e.assignSentMs.get(w.id);
        if (w.queue.contains(assign) || lastSent != null && now - lastSent < 2 * HEARTBEAT_INTERVAL_MS) {
            return;
        }
        e.idleSinceMs.remove(w.id);
        w.queue.add(assign);
        e.assignSentMs.put(w.id, now);
        if (e.pending != null && e.pending.generation == e.generation) {
            w.queue.add(new Command.TriggerCheckpoint(e.id, e.generation, e.pending.id));
        }
        log.warn("Re-sending ASSIGN generation {} to worker {} (it reports {} g{} {})", e.generation, w.id,
                status.executionId(), status.generation(), status.state());
    }

    private boolean isCurrentMember(String workerId, String executionId) {
        return exec != null && exec.active() && exec.id.equals(executionId) && exec.assignment.containsKey(workerId);
    }

    private boolean ownsPartitions(String workerId) {
        return exec != null && exec.active() && exec.assignment.containsKey(workerId);
    }

    private List<Integer> ownedPartitions(String workerId) {
        if (exec == null || !exec.active()) {
            return List.of();
        }
        List<Integer> owned = exec.assignment.get(workerId);
        return owned == null ? List.of() : List.copyOf(owned);
    }

    private List<String> liveWorkerIds() {
        List<String> ids = new ArrayList<>();
        for (WorkerEntry w : workers.values()) {
            if (w.alive) {
                ids.add(w.id);
            }
        }
        return ids;
    }

    private ExecState requireExecution(String executionId) {
        if (exec == null || !exec.id.equals(executionId)) {
            throw new NoSuchElementException("unknown execution " + executionId);
        }
        return exec;
    }

    private String newWorkerId() {
        String id;
        do {
            id = String.format("w-%04x", random.nextInt(0x10000));
        } while (workers.containsKey(id));
        return id;
    }

    private static void checkProtocol(int version) {
        if (version != Protocol.VERSION) {
            throw new IllegalArgumentException("unsupported protocolVersion " + version + " (expected "
                    + Protocol.VERSION + ")");
        }
    }

    private static ExecutionInfo info(ExecState e) {
        return new ExecutionInfo(e.id, e.pipelineId, e.pipelineVersion, e.state, e.generation, e.restoredFrom,
                e.lastCompleted, e.createdAt);
    }

    /** Audit rows are best effort: a failed insert must never abort failure detection or recovery half-way. */
    private void audit(String action, Map<String, Object> detail) {
        try {
            repo.audit(action, detail);
        } catch (RuntimeException ex) {
            log.warn("Audit {} failed: {}", action, ex.toString());
        }
    }

    private static Map<String, Object> detail(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }
}
