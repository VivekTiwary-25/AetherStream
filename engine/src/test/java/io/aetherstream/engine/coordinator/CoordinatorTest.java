package io.aetherstream.engine.coordinator;

import static org.assertj.core.api.Assertions.assertThat;

import io.aetherstream.common.api.ExecutionInfo;
import io.aetherstream.common.pipeline.ExecutionPlan;
import io.aetherstream.common.protocol.AckResponse;
import io.aetherstream.common.protocol.CheckpointAck;
import io.aetherstream.common.protocol.CheckpointManifest;
import io.aetherstream.common.protocol.ClusterState;
import io.aetherstream.common.protocol.Command;
import io.aetherstream.common.protocol.QueueStatus;
import io.aetherstream.common.protocol.RegisterRequest;
import io.aetherstream.common.protocol.StartExecutionRequest;
import io.aetherstream.common.protocol.WorkerStatus;
import io.aetherstream.engine.storage.InMemoryCheckpointStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CoordinatorTest {
    static final String EXEC = "exec-1";
    static final Map<Integer, Long> CP0 = Map.of(0, 100L, 1, 110L, 2, 120L, 3, 130L, 4, 140L, 5, 150L);
    static final List<Integer> EVEN = List.of(0, 2, 4);
    static final List<Integer> ODD = List.of(1, 3, 5);
    static final List<Integer> ALL = List.of(0, 1, 2, 3, 4, 5);

    static final class FakeClock implements Clock {
        long now = 1_795_251_600_000L;

        @Override
        public long nowMs() {
            return now;
        }

        void advance(long ms) {
            now += ms;
        }
    }

    /** Records whether the manifest already existed in the store when the checkpoint flipped to COMPLETED. */
    static final class OrderCheckingRepo extends InMemoryExecutionRepository {
        final InMemoryCheckpointStore store;
        final Map<Long, Boolean> manifestExistedAtCompletion = new HashMap<>();

        OrderCheckingRepo(InMemoryCheckpointStore store) {
            this.store = store;
        }

        @Override
        public synchronized void completeCheckpoint(String executionId, long checkpointId, String manifestKey,
                                                    Map<Integer, Long> sourceOffsets, long completedAtMs,
                                                    long durationMs) {
            manifestExistedAtCompletion.put(checkpointId, store.exists(manifestKey));
            super.completeCheckpoint(executionId, checkpointId, manifestKey, sourceOffsets, completedAtMs, durationMs);
        }
    }

    static final class ClockedCheckpointStore extends InMemoryCheckpointStore {
        final FakeClock clock;
        long uploadDelayMs;
        boolean failManifest;

        ClockedCheckpointStore(FakeClock clock) {
            this.clock = clock;
        }

        @Override
        public void putJson(String key, Object value) {
            if (key.endsWith("/manifest.json")) {
                clock.advance(uploadDelayMs);
                if (failManifest) {
                    throw new IllegalStateException("manifest upload failed");
                }
            }
            super.putJson(key, value);
        }
    }

    FakeClock clock;
    ClockedCheckpointStore store;
    OrderCheckingRepo repo;
    Coordinator coordinator;

    @BeforeEach
    void setUp() {
        clock = new FakeClock();
        store = new ClockedCheckpointStore(clock);
        repo = new OrderCheckingRepo(store);
        coordinator = newCoordinator();
    }

    Coordinator newCoordinator() {
        return new Coordinator(repo, store, (topic, partitions, startFrom) -> CP0, clock, 10_000, true);
    }
    static ExecutionPlan plan() {
        return new ExecutionPlan("logistics-demo", 1, "truck-events", 6, "latest", List.of());
    }

    void register(String id) {
        assertThat(coordinator.register(new RegisterRequest(1, id, "host-" + id)).workerId()).isEqualTo(id);
    }

    static WorkerStatus status(String id, String execId, long gen, String state, List<Integer> partitions) {
        return new WorkerStatus(1, id, execId, gen, state, partitions, new QueueStatus(0, 1000),
                new QueueStatus(0, 1000), false, 0, 0.0, 0, Map.of());
    }

    List<Command> hb(String id, long gen, String state, List<Integer> partitions) {
        return coordinator.heartbeat(id, status(id, EXEC, gen, state, partitions)).commands();
    }

    List<Command> idleHb(String id) {
        return coordinator.heartbeat(id, status(id, null, 0, "IDLE", List.of())).commands();
    }

    static <T> List<T> ofType(List<Command> commands, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (Command c : commands) {
            if (type.isInstance(c)) {
                out.add(type.cast(c));
            }
        }
        return out;
    }

    static String workerKey(long cp, String worker) {
        return "executions/" + EXEC + "/checkpoints/" + cp + "/worker-" + worker + ".json";
    }

    static Map<Integer, Long> offsets(List<Integer> partitions, long base) {
        Map<Integer, Long> m = new TreeMap<>();
        for (int p : partitions) {
            m.put(p, base + 10L * p);
        }
        return m;
    }

    AckResponse ack(String worker, long gen, long cp, List<Integer> partitions, long base) {
        return coordinator.ack(new CheckpointAck(1, worker, EXEC, gen, cp, offsets(partitions, base), workerKey(cp, worker)));
    }

    /** Two workers registered, execution started, ASSIGNs drained and both report RUNNING generation 1. */
    void startRunning() {
        register("w-a");
        register("w-b");
        coordinator.startExecution(new StartExecutionRequest(EXEC, plan()));
        assertThat(ofType(idleHb("w-a"), Command.Assign.class)).hasSize(1);
        assertThat(ofType(idleHb("w-b"), Command.Assign.class)).hasSize(1);
        hb("w-a", 1, "RUNNING", EVEN);
        hb("w-b", 1, "RUNNING", ODD);
    }

    /** Advances to the next checkpoint interval, both workers heartbeat, tick triggers; returns the drained commands. */
    Map<String, List<Command>> nextInterval(long gen) {
        clock.advance(10_000);
        hb("w-a", gen, "RUNNING", EVEN);
        hb("w-b", gen, "RUNNING", ODD);
        coordinator.tick();
        Map<String, List<Command>> out = new HashMap<>();
        out.put("w-a", hb("w-a", gen, "RUNNING", EVEN));
        out.put("w-b", hb("w-b", gen, "RUNNING", ODD));
        return out;
    }

    void completeCheckpoint1() {
        Map<String, List<Command>> cmds = nextInterval(1);
        assertThat(ofType(cmds.get("w-a"), Command.TriggerCheckpoint.class))
                .containsExactly(new Command.TriggerCheckpoint(EXEC, 1, 1));
        assertThat(ack("w-a", 1, 1, EVEN, 200).accepted()).isTrue();
        assertThat(ack("w-b", 1, 1, ODD, 200).accepted()).isTrue();
        assertThat(repo.findCheckpoint(EXEC, 1).orElseThrow().state()).isEqualTo(CheckpointRecord.COMPLETED);
    }

    @Test
    void twoWorkersSplitPartitionsRoundRobinWithCheckpointZeroOffsets() {
        register("w-a");
        register("w-b");
        ExecutionInfo info = coordinator.startExecution(new StartExecutionRequest(EXEC, plan()));

        assertThat(info.state()).isEqualTo("RUNNING");
        assertThat(info.generation()).isEqualTo(1);
        assertThat(info.lastCompletedCheckpoint()).isEqualTo(0L);
        CheckpointRecord cp0 = repo.findCheckpoint(EXEC, 0).orElseThrow();
        assertThat(cp0.state()).isEqualTo(CheckpointRecord.COMPLETED);
        assertThat(cp0.sourceOffsets()).isEqualTo(CP0);
        assertThat(repo.findExecution(EXEC).orElseThrow().generation()).isEqualTo(1);

        List<Command.Assign> a = ofType(idleHb("w-a"), Command.Assign.class);
        List<Command.Assign> b = ofType(idleHb("w-b"), Command.Assign.class);
        assertThat(a).hasSize(1);
        assertThat(b).hasSize(1);
        assertThat(a.get(0).partitions()).containsExactly(0, 2, 4);
        assertThat(b.get(0).partitions()).containsExactly(1, 3, 5);
        assertThat(a.get(0).generation()).isEqualTo(1);
        assertThat(b.get(0).generation()).isEqualTo(1);
        assertThat(a.get(0).restoreCheckpointId()).isZero();
        assertThat(a.get(0).startOffsets()).isEqualTo(Map.of(0, 100L, 2, 120L, 4, 140L));
        assertThat(b.get(0).startOffsets()).isEqualTo(Map.of(1, 110L, 3, 130L, 5, 150L));
        // Commands are removed once delivered.
        assertThat(idleHb("w-a")).isEmpty();
    }

    @Test
    void checkpointCompletesOnlyWhenAllPartitionsAckedAndManifestIsWrittenFirst() {
        startRunning();
        Map<String, List<Command>> cmds = nextInterval(1);
        assertThat(ofType(cmds.get("w-a"), Command.TriggerCheckpoint.class))
                .containsExactly(new Command.TriggerCheckpoint(EXEC, 1, 1));
        assertThat(ofType(cmds.get("w-b"), Command.TriggerCheckpoint.class))
                .containsExactly(new Command.TriggerCheckpoint(EXEC, 1, 1));
        assertThat(repo.findCheckpoint(EXEC, 1).orElseThrow().state()).isEqualTo(CheckpointRecord.PENDING);

        String manifestKey = Coordinator.manifestKey(EXEC, 1);
        assertThat(ack("w-a", 1, 1, EVEN, 200).accepted()).isTrue();
        assertThat(repo.findCheckpoint(EXEC, 1).orElseThrow().state()).isEqualTo(CheckpointRecord.PENDING);
        assertThat(store.exists(manifestKey)).isFalse();

        // Duplicate ack is harmless.
        assertThat(ack("w-a", 1, 1, EVEN, 200).accepted()).isTrue();
        assertThat(repo.findCheckpoint(EXEC, 1).orElseThrow().state()).isEqualTo(CheckpointRecord.PENDING);

        assertThat(ack("w-b", 1, 1, ODD, 200).accepted()).isTrue();
        CheckpointRecord cp1 = repo.findCheckpoint(EXEC, 1).orElseThrow();
        assertThat(cp1.state()).isEqualTo(CheckpointRecord.COMPLETED);
        assertThat(cp1.manifestKey()).isEqualTo(manifestKey);
        assertThat(cp1.sourceOffsets()).isEqualTo(offsets(ALL, 200));
        assertThat(cp1.durationMs()).isNotNull();
        assertThat(repo.manifestExistedAtCompletion).containsEntry(1L, true);

        CheckpointManifest manifest = store.getJson(manifestKey, CheckpointManifest.class);
        assertThat(manifest.checkpointId()).isEqualTo(1);
        assertThat(manifest.generation()).isEqualTo(1);
        assertThat(manifest.pipelineId()).isEqualTo("logistics-demo");
        assertThat(manifest.protocolVersion()).isEqualTo(1);
        assertThat(manifest.partitions()).hasSize(6);
        assertThat(manifest.partitions().get(3)).isEqualTo(new CheckpointManifest.PartitionEntry(230, workerKey(1, "w-b")));
        assertThat(manifest.partitions().get(4)).isEqualTo(new CheckpointManifest.PartitionEntry(240, workerKey(1, "w-a")));

        assertThat(coordinator.clusterState().execution().lastCompletedCheckpoint()).isEqualTo(1L);
        // Duplicate after completion is still harmless.
        assertThat(ack("w-b", 1, 1, ODD, 200).accepted()).isTrue();
        assertThat(repo.findCheckpoint(EXEC, 1).orElseThrow().state()).isEqualTo(CheckpointRecord.COMPLETED);
    }

    @Test
    void checkpointHistogramIncludesUploadTimeAndIgnoresDuplicateAcks() {
        startRunning();
        coordinator.triggerCheckpointNow(EXEC);
        clock.advance(250);
        ack("w-a", 1, 1, EVEN, 200);
        assertThat(coordinator.metricsText()).contains("aether_checkpoint_duration_seconds_count 0\n");
        store.uploadDelayMs = 500;
        ack("w-b", 1, 1, ODD, 200);
        ack("w-b", 1, 1, ODD, 200);
        assertThat(repo.findCheckpoint(EXEC, 1).orElseThrow().durationMs()).isEqualTo(750);
        assertThat(coordinator.metricsText()).contains(
                "aether_checkpoints_completed_total 1\n",
                "aether_checkpoint_duration_seconds_bucket{le=\"0.5\"} 0\n",
                "aether_checkpoint_duration_seconds_bucket{le=\"1.0\"} 1\n",
                "aether_checkpoint_duration_seconds_bucket{le=\"+Inf\"} 1\n",
                "aether_checkpoint_duration_seconds_sum 0.75\n",
                "aether_checkpoint_duration_seconds_count 1\n");
    }

    @Test
    void failedManifestCountsAsAbortedWithoutObservingDuration() {
        startRunning();
        coordinator.triggerCheckpointNow(EXEC);
        store.failManifest = true;
        clock.advance(250);
        ack("w-a", 1, 1, EVEN, 200);
        ack("w-b", 1, 1, ODD, 200);
        assertThat(repo.findCheckpoint(EXEC, 1).orElseThrow().state()).isEqualTo(CheckpointRecord.ABORTED);
        assertThat(coordinator.metricsText()).contains("aether_checkpoints_aborted_total 1\n",
                "aether_checkpoints_completed_total 0\n", "aether_checkpoint_duration_seconds_count 0\n",
                "aether_checkpoint_duration_seconds_sum 0.0\n");
    }

    @Test
    void metricCountersAndHistogramsDoNotResetWhenExecutionChanges() {
        startRunning();
        completeCheckpoint1();
        coordinator.triggerCheckpointNow(EXEC);
        coordinator.startExecution(new StartExecutionRequest("exec-2", plan()));
        assertThat(coordinator.metricsText()).contains("aether_checkpoints_completed_total 1\n",
                "aether_checkpoints_aborted_total 1\n", "aether_checkpoint_duration_seconds_count 1\n",
                "aether_execution_generation 1\n");
        coordinator.stopExecution("exec-2");
        assertThat(coordinator.metricsText()).contains("aether_checkpoints_aborted_total 1\n");
    }

    @Test
    void recoveryHistogramStartsAtDetectionAndWaitsForEveryWorkerInNewGeneration() {
        register("w-a");
        register("w-b");
        register("w-c");
        coordinator.startExecution(new StartExecutionRequest(EXEC, plan()));
        hb("w-a", 1, "RUNNING", List.of(0, 3));
        hb("w-b", 1, "RUNNING", List.of(1, 4));
        hb("w-c", 1, "RUNNING", List.of(2, 5));
        coordinator.triggerCheckpointNow(EXEC);
        clock.advance(Coordinator.HEARTBEAT_TIMEOUT_MS);
        hb("w-a", 1, "RUNNING", List.of(0, 3));
        hb("w-b", 1, "RUNNING", List.of(1, 4));
        coordinator.tick();
        assertThat(coordinator.metricsText()).contains("aether_recoveries_total 0\n",
                "aether_recovery_duration_seconds_count 0\n", "aether_checkpoints_aborted_total 1\n",
                "aether_workers_alive 2\n", "aether_execution_generation 2\n");
        clock.advance(500);
        hb("w-a", 2, "RUNNING", EVEN);
        hb("w-b", 1, "RUNNING", ODD);
        assertThat(coordinator.metricsText()).contains("aether_recoveries_total 0\n");
        clock.advance(750);
        hb("w-b", 2, "RUNNING", ODD);
        hb("w-a", 2, "RUNNING", EVEN);
        coordinator.tick();
        assertThat(coordinator.metricsText()).contains("aether_recoveries_total 1\n",
                "aether_recovery_duration_seconds_bucket{le=\"1.0\"} 0\n",
                "aether_recovery_duration_seconds_bucket{le=\"2.5\"} 1\n",
                "aether_recovery_duration_seconds_bucket{le=\"+Inf\"} 1\n",
                "aether_recovery_duration_seconds_sum 1.25\n",
                "aether_recovery_duration_seconds_count 1\n");
        coordinator.startExecution(new StartExecutionRequest("exec-2", plan()));
        assertThat(coordinator.metricsText()).contains("aether_recoveries_total 1\n",
                "aether_recovery_duration_seconds_count 1\n");
    }

    @Test
    void failureDuringRebalanceMeasuresFromDeathInsteadOfRebalanceStart() {
        startRunning();
        register("w-c");
        clock.advance(5000);
        hb("w-a", 1, "RUNNING", EVEN);
        hb("w-b", 1, "RUNNING", ODD);
        idleHb("w-c");
        ack("w-a", 1, 1, EVEN, 200);
        ack("w-b", 1, 1, ODD, 200);
        assertThat(coordinator.executionInfo(EXEC).state()).isEqualTo("RECOVERING");
        clock.advance(Coordinator.HEARTBEAT_TIMEOUT_MS);
        hb("w-a", 2, "RESTORING", List.of(0, 3));
        hb("w-b", 2, "RESTORING", List.of(1, 4));
        coordinator.tick();
        clock.advance(250);
        hb("w-a", 3, "RUNNING", EVEN);
        hb("w-b", 3, "RUNNING", ODD);
        assertThat(coordinator.metricsText()).contains("aether_recoveries_total 1\n",
                "aether_recovery_duration_seconds_sum 0.25\n",
                "aether_recovery_duration_seconds_count 1\n");
    }

    @Test
    void staleAckIsRejected() {
        startRunning();
        assertThat(coordinator.triggerCheckpointNow(EXEC)).isEqualTo(1);

        AckResponse old = ack("w-a", 0, 1, EVEN, 200);
        assertThat(old.accepted()).isFalse();
        assertThat(old.reason()).contains("stale");
        assertThat(ack("w-a", 2, 1, EVEN, 200).accepted()).isFalse();
        assertThat(ack("w-a", 1, 7, EVEN, 200).accepted()).isFalse();
        assertThat(repo.findCheckpoint(EXEC, 1).orElseThrow().state()).isEqualTo(CheckpointRecord.PENDING);
    }

    @Test
    void workerFailureRecoversAllPartitionsOntoTwoSurvivors() {
        register("w-a");
        register("w-b");
        register("w-c");
        coordinator.startExecution(new StartExecutionRequest(EXEC, plan()));
        assertThat(ofType(idleHb("w-a"), Command.Assign.class).get(0).partitions()).containsExactly(0, 3);
        assertThat(ofType(idleHb("w-b"), Command.Assign.class).get(0).partitions()).containsExactly(1, 4);
        assertThat(ofType(idleHb("w-c"), Command.Assign.class).get(0).partitions()).containsExactly(2, 5);
        hb("w-a", 1, "RUNNING", List.of(0, 3));
        hb("w-b", 1, "RUNNING", List.of(1, 4));
        hb("w-c", 1, "RUNNING", List.of(2, 5));
        assertThat(coordinator.killWorker("w-c")).isEqualTo("w-c");
        assertThat(idleHb("w-c")).containsExactly(new Command.Kill());

        for (int i = 0; i < 4; i++) {
            clock.advance(1000);
            hb("w-a", 1, "RUNNING", List.of(0, 3));
            hb("w-b", 1, "RUNNING", List.of(1, 4));
            coordinator.tick();
        }
        assertThat(coordinator.executionInfo(EXEC).state()).isEqualTo("RECOVERING");
        assertThat(coordinator.executionInfo(EXEC).generation()).isEqualTo(2);
        Command.Assign a = ofType(hb("w-a", 1, "RUNNING", List.of(0, 3)), Command.Assign.class).get(0);
        Command.Assign b = ofType(hb("w-b", 1, "RUNNING", List.of(1, 4)), Command.Assign.class).get(0);
        assertThat(a.partitions()).containsExactly(0, 2, 4);
        assertThat(b.partitions()).containsExactly(1, 3, 5);

        hb("w-a", 2, "RUNNING", List.of(0, 2, 4));
        hb("w-b", 2, "RUNNING", List.of(1, 3, 5));
        assertThat(coordinator.executionInfo(EXEC).state()).isEqualTo("RUNNING");
        assertThat(coordinator.executionInfo(EXEC).restoredFromCheckpoint()).isEqualTo(0L);
    }

    @Test
    void workerFailureRecoversFromLastCompletedCheckpointWithNewGeneration() {
        startRunning();
        completeCheckpoint1();

        // Checkpoint 2 starts; only w-a acks (its worker file exists), then w-b goes silent.
        Map<String, List<Command>> cmds = nextInterval(1);
        assertThat(ofType(cmds.get("w-b"), Command.TriggerCheckpoint.class))
                .containsExactly(new Command.TriggerCheckpoint(EXEC, 1, 2));
        store.putJson(workerKey(2, "w-a"), Map.of("checkpointId", 2));
        assertThat(ack("w-a", 1, 2, EVEN, 300).accepted()).isTrue();

        clock.advance(2000);
        hb("w-a", 1, "RUNNING", EVEN);
        coordinator.tick();
        assertThat(coordinator.clusterState().execution().state()).isEqualTo("RUNNING");
        clock.advance(2000);
        hb("w-a", 1, "RUNNING", EVEN);
        coordinator.tick();

        ClusterState state = coordinator.clusterState();
        assertThat(state.execution().state()).isEqualTo("RECOVERING");
        assertThat(state.execution().generation()).isEqualTo(2);
        assertThat(repo.findExecution(EXEC).orElseThrow().generation()).isEqualTo(2);
        assertThat(repo.findExecution(EXEC).orElseThrow().state()).isEqualTo("RECOVERING");
        assertThat(repo.findCheckpoint(EXEC, 2).orElseThrow().state()).isEqualTo(CheckpointRecord.ABORTED);
        assertThat(state.workers()).anySatisfy(w -> {
            assertThat(w.workerId()).isEqualTo("w-b");
            assertThat(w.alive()).isFalse();
        });

        List<Command> survivor = hb("w-a", 1, "RUNNING", EVEN);
        assertThat(survivor).hasSize(2);
        assertThat(survivor.get(0)).isEqualTo(new Command.Cancel(EXEC, 1));
        Command.Assign assign = (Command.Assign) survivor.get(1);
        assertThat(assign.generation()).isEqualTo(2);
        assertThat(assign.partitions()).containsExactlyElementsOf(ALL);
        assertThat(assign.restoreCheckpointId()).isEqualTo(1);
        assertThat(assign.manifestKey()).isEqualTo(Coordinator.manifestKey(EXEC, 1));
        assertThat(assign.startOffsets()).isEqualTo(offsets(ALL, 200));

        // A late ack from the old generation is rejected.
        assertThat(ack("w-b", 1, 2, ODD, 300).accepted()).isFalse();

        hb("w-a", 2, "RESTORING", ALL);
        assertThat(coordinator.clusterState().execution().state()).isEqualTo("RECOVERING");
        hb("w-a", 2, "RUNNING", ALL);
        ExecutionInfo info = coordinator.executionInfo(EXEC);
        assertThat(info.state()).isEqualTo("RUNNING");
        assertThat(info.generation()).isEqualTo(2);
        assertThat(info.restoredFromCheckpoint()).isEqualTo(1L);
        assertThat(repo.findExecution(EXEC).orElseThrow().restoredFromCheckpoint()).isEqualTo(1L);
        assertThat(repo.findExecution(EXEC).orElseThrow().state()).isEqualTo("RUNNING");

        List<String> actions = repo.auditRows().stream().map(InMemoryExecutionRepository.AuditRow::action).toList();
        assertThat(actions).contains("worker.dead", "execution.recovering", "execution.recovered");
        InMemoryExecutionRepository.AuditRow recovered = repo.auditRows().stream()
                .filter(r -> r.action().equals("execution.recovered")).findFirst().orElseThrow();
        assertThat(recovered.detail()).containsEntry("checkpointId", 1L).containsEntry("generation", 2L)
                .containsKey("recoveryDurationMs");

        // Checkpointing continues with the next id under the new generation.
        clock.advance(10_000);
        hb("w-a", 2, "RUNNING", ALL);
        coordinator.tick();
        assertThat(ofType(hb("w-a", 2, "RUNNING", ALL), Command.TriggerCheckpoint.class))
                .containsExactly(new Command.TriggerCheckpoint(EXEC, 2, 3));
        assertThat(repo.findCheckpoint(EXEC, 3).orElseThrow().generation()).isEqualTo(2);
    }

    @Test
    void incompleteCheckpointIsNeverChosenForRecovery() {
        startRunning();
        completeCheckpoint1();

        // Checkpoint 2: w-a uploads its file and acks, w-b never acks -> times out after 30 s -> ABORTED.
        nextInterval(1);
        store.putJson(workerKey(2, "w-a"), Map.of("checkpointId", 2));
        assertThat(ack("w-a", 1, 2, EVEN, 300).accepted()).isTrue();
        Map<String, List<Command>> seen = new HashMap<>();
        for (int i = 0; i < 45; i++) {
            clock.advance(1000);
            seen.computeIfAbsent("w-a", k -> new ArrayList<>()).addAll(hb("w-a", 1, "RUNNING", EVEN));
            hb("w-b", 1, "RUNNING", ODD);
            coordinator.tick();
        }
        assertThat(repo.findCheckpoint(EXEC, 2).orElseThrow().state()).isEqualTo(CheckpointRecord.ABORTED);
        assertThat(coordinator.metricsText()).contains("aether_checkpoints_aborted_total 1\n",
                "aether_checkpoint_duration_seconds_count 1\n");

        // Checkpoint 3 is pending with a partial ack when w-b dies.
        assertThat(ofType(seen.get("w-a"), Command.TriggerCheckpoint.class))
                .contains(new Command.TriggerCheckpoint(EXEC, 1, 3));
        store.putJson(workerKey(3, "w-a"), Map.of("checkpointId", 3));
        assertThat(ack("w-a", 1, 3, EVEN, 400).accepted()).isTrue();
        for (int i = 0; i < 4; i++) {
            clock.advance(1000);
            hb("w-a", 1, "RUNNING", EVEN);
            coordinator.tick();
        }
        assertThat(coordinator.clusterState().execution().state()).isEqualTo("RECOVERING");
        assertThat(repo.findCheckpoint(EXEC, 3).orElseThrow().state()).isEqualTo(CheckpointRecord.ABORTED);

        Command.Assign assign = ofType(hb("w-a", 1, "RUNNING", EVEN), Command.Assign.class).get(0);
        assertThat(assign.restoreCheckpointId()).isEqualTo(1);
        assertThat(assign.manifestKey()).isEqualTo(Coordinator.manifestKey(EXEC, 1));
        assertThat(assign.startOffsets()).isEqualTo(offsets(ALL, 200));
        assertThat(store.exists(Coordinator.manifestKey(EXEC, 2))).isFalse();
        assertThat(store.exists(Coordinator.manifestKey(EXEC, 3))).isFalse();
    }

    @Test
    void executionStartedWithoutWorkersIsAssignedWhenOneRegisters() {
        ExecutionInfo info = coordinator.startExecution(new StartExecutionRequest(EXEC, plan()));
        assertThat(info.state()).isEqualTo("RUNNING");
        coordinator.tick();
        register("w-z");
        Command.Assign assign = ofType(idleHb("w-z"), Command.Assign.class).get(0);
        assertThat(assign.generation()).isEqualTo(1);
        assertThat(assign.partitions()).containsExactlyElementsOf(ALL);
        assertThat(assign.startOffsets()).isEqualTo(CP0);
    }

    @Test
    void recoveryBeforeAnyCheckpointUsesCheckpointZeroOffsets() {
        startRunning();
        for (int i = 0; i < 4; i++) {
            clock.advance(1000);
            hb("w-b", 1, "RUNNING", ODD);
            coordinator.tick();
        }
        Command.Assign assign = ofType(hb("w-b", 1, "RUNNING", ODD), Command.Assign.class).get(0);
        assertThat(assign.generation()).isEqualTo(2);
        assertThat(assign.restoreCheckpointId()).isZero();
        assertThat(assign.manifestKey()).isNull();
        assertThat(assign.startOffsets()).isEqualTo(CP0);
    }

    @Test
    void coordinatorRestartRecoversAfterWorkersReRegister() {
        startRunning();
        completeCheckpoint1();

        Coordinator restarted = newCoordinator();
        restarted.loadActiveExecutions();
        coordinator = restarted;
        register("w-a");
        assertThat(ofType(hb("w-a", 1, "RUNNING", EVEN), Command.Assign.class)).isEmpty();
        clock.advance(1000);
        register("w-b");
        clock.advance(2000);
        hb("w-a", 1, "RUNNING", EVEN);
        hb("w-b", 1, "RUNNING", ODD);
        coordinator.tick();
        assertThat(coordinator.clusterState().execution().generation()).isEqualTo(1);
        clock.advance(1000);
        coordinator.tick();

        assertThat(coordinator.clusterState().execution().state()).isEqualTo("RECOVERING");
        Command.Assign a = ofType(hb("w-a", 1, "RUNNING", EVEN), Command.Assign.class).get(0);
        Command.Assign b = ofType(hb("w-b", 1, "RUNNING", ODD), Command.Assign.class).get(0);
        assertThat(a.generation()).isEqualTo(2);
        assertThat(a.restoreCheckpointId()).isEqualTo(1);
        assertThat(a.partitions()).containsExactly(0, 2, 4);
        assertThat(b.partitions()).containsExactly(1, 3, 5);

        hb("w-a", 2, "RUNNING", EVEN);
        hb("w-b", 2, "RUNNING", ODD);
        assertThat(coordinator.clusterState().execution().state()).isEqualTo("RUNNING");
        assertThat(coordinator.triggerCheckpointNow(EXEC)).isEqualTo(2);
    }

    @Test
    void chaosKillTargetsBusiestWorkerAndSinkDelayReachesNewWorkers() {
        register("w-a");
        coordinator.startExecution(new StartExecutionRequest(EXEC, plan()));
        register("w-b");
        idleHb("w-a");
        idleHb("w-b");

        assertThat(coordinator.killWorker(null)).isEqualTo("w-a");
        assertThat(idleHb("w-a")).containsExactly(new Command.Kill());

        assertThat(coordinator.setSinkDelay(50)).isEqualTo(50);
        assertThat(idleHb("w-b")).containsExactly(new Command.SetSinkDelay(50));
        register("w-c");
        assertThat(idleHb("w-c")).containsExactly(new Command.SetSinkDelay(50));
        assertThat(coordinator.clusterState().sinkDelayMs()).isEqualTo(50);
    }

    @Test
    void stableNewWorkerRebalancesAtCompletedCheckpointAndHonorsRecoveryAndRateLimit() {
        startRunning();
        register("w-c");
        clock.advance(5000);
        hb("w-a", 1, "RUNNING", EVEN);
        hb("w-b", 1, "RUNNING", ODD);
        idleHb("w-c");
        assertThat(repo.findCheckpoint(EXEC, 1).orElseThrow().state()).isEqualTo(CheckpointRecord.PENDING);
        assertThat(ack("w-a", 1, 1, EVEN, 200).accepted()).isTrue();
        assertThat(ack("w-b", 1, 1, ODD, 200).accepted()).isTrue();

        ExecutionInfo recovering = coordinator.executionInfo(EXEC);
        assertThat(recovering.state()).isEqualTo("RECOVERING");
        assertThat(recovering.generation()).isEqualTo(2);
        assertThat(recovering.restoredFromCheckpoint()).isEqualTo(1L);
        Command.Assign a = ofType(hb("w-a", 1, "RUNNING", EVEN), Command.Assign.class).get(0);
        Command.Assign b = ofType(hb("w-b", 1, "RUNNING", ODD), Command.Assign.class).get(0);
        Command.Assign c = ofType(idleHb("w-c"), Command.Assign.class).get(0);
        assertThat(a.partitions()).containsExactly(0, 3);
        assertThat(b.partitions()).containsExactly(1, 4);
        assertThat(c.partitions()).containsExactly(2, 5);
        assertThat(a.restoreCheckpointId()).isEqualTo(1);

        register("w-d");
        clock.advance(5000);
        hb("w-a", 2, "RESTORING", List.of(0, 3));
        hb("w-b", 2, "RESTORING", List.of(1, 4));
        idleHb("w-c");
        idleHb("w-d");
        coordinator.tick();
        assertThat(coordinator.executionInfo(EXEC).state()).isEqualTo("RECOVERING");
        assertThat(coordinator.executionInfo(EXEC).generation()).isEqualTo(2);

        hb("w-a", 2, "RUNNING", List.of(0, 3));
        hb("w-b", 2, "RUNNING", List.of(1, 4));
        hb("w-c", 2, "RUNNING", List.of(2, 5));
        assertThat(coordinator.executionInfo(EXEC).state()).isEqualTo("RUNNING");
        assertThat(coordinator.metricsText()).contains("aether_recoveries_total 0\n",
                "aether_recovery_duration_seconds_count 0\n");
        try {
            coordinator.requestRebalance(EXEC);
        } catch (IllegalStateException expected) {
            assertThat(expected.getMessage()).contains("30 s");
            return;
        }
        throw new AssertionError("rebalance rate limit was not enforced");
    }

    @Test
    void coordinatorMetricsExistAtZeroBeforeAnyExecutionOrRecovery() {
        assertThat(coordinator.metricsText()).contains("aether_workers_alive 0\n", "aether_execution_generation 0\n",
                "aether_checkpoints_completed_total 0\n", "aether_checkpoints_aborted_total 0\n",
                "aether_recoveries_total 0\n",
                "aether_checkpoint_duration_seconds_bucket{le=\"0.005\"} 0\n",
                "aether_checkpoint_duration_seconds_bucket{le=\"+Inf\"} 0\n",
                "aether_checkpoint_duration_seconds_count 0\n", "aether_checkpoint_duration_seconds_sum 0.0\n",
                "aether_recovery_duration_seconds_bucket{le=\"0.005\"} 0\n",
                "aether_recovery_duration_seconds_bucket{le=\"+Inf\"} 0\n",
                "aether_recovery_duration_seconds_count 0\n", "aether_recovery_duration_seconds_sum 0.0\n");
    }

    @Test
    void metricsExposeEveryRequestedSeries() {
        startRunning();
        hb("w-a", 1, "RUNNING", EVEN);
        String text = coordinator.metricsText();
        assertThat(text).contains("aether_workers_alive", "aether_execution_generation",
                "aether_checkpoints_completed_total", "aether_checkpoints_aborted_total",
                "aether_last_checkpoint_duration_ms", "aether_recoveries_total",
                "aether_last_recovery_duration_ms", "aether_worker_records_per_sec",
                "aether_checkpoint_duration_seconds_bucket", "aether_recovery_duration_seconds_bucket",
                "aether_worker_queue_size", "aether_worker_backpressured",
                "worker=\"w-a\"", "queue=\"source\"", "queue=\"sink\"");
    }

    @Test
    void stopSendsCancelAndMarksStopped() {
        startRunning();
        ExecutionInfo info = coordinator.stopExecution(EXEC);
        assertThat(info.state()).isEqualTo("STOPPED");
        assertThat(repo.findExecution(EXEC).orElseThrow().state()).isEqualTo("STOPPED");
        assertThat(hb("w-a", 1, "RUNNING", EVEN)).contains(new Command.Cancel(EXEC, 1));
    }
}
