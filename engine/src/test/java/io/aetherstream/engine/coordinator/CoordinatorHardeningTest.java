package io.aetherstream.engine.coordinator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aetherstream.common.pipeline.ExecutionPlan;
import io.aetherstream.common.protocol.CheckpointAck;
import io.aetherstream.common.protocol.Command;
import io.aetherstream.common.protocol.QueueStatus;
import io.aetherstream.common.protocol.RegisterRequest;
import io.aetherstream.common.protocol.StartExecutionRequest;
import io.aetherstream.common.protocol.WorkerStatus;
import io.aetherstream.engine.storage.InMemoryCheckpointStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Regression tests for the R07 engine-hardening fixes (docs/reviews/R07-engine-hardening.md). */
class CoordinatorHardeningTest {
    static final String EXEC = "exec-h";
    static final Map<Integer, Long> CP0 = Map.of(0, 0L, 1, 0L, 2, 0L, 3, 0L, 4, 0L, 5, 0L);
    static final List<Integer> EVEN = List.of(0, 2, 4);
    static final List<Integer> ODD = List.of(1, 3, 5);

    static final class FakeClock implements Clock {
        long now = 1_795_251_600_000L;

        @Override
        public long nowMs() {
            return now;
        }
    }

    /** Repository whose audit / updateState / setRestoredFrom can be made to fail once (a Postgres hiccup). */
    static final class FlakyRepo extends InMemoryExecutionRepository {
        String failAuditAction;
        String failUpdateState;
        int failSetRestoredFrom;

        @Override
        public synchronized void audit(String action, Map<String, Object> detail) {
            if (action.equals(failAuditAction)) {
                failAuditAction = null;
                throw new IllegalStateException("db down (audit)");
            }
            super.audit(action, detail);
        }

        @Override
        public synchronized void updateState(String executionId, String state) {
            if (state.equals(failUpdateState)) {
                failUpdateState = null;
                throw new IllegalStateException("db down (updateState)");
            }
            super.updateState(executionId, state);
        }

        @Override
        public synchronized void setRestoredFrom(String executionId, long checkpointId) {
            if (failSetRestoredFrom > 0) {
                failSetRestoredFrom--;
                throw new IllegalStateException("db down (setRestoredFrom)");
            }
            super.setRestoredFrom(executionId, checkpointId);
        }
    }

    FakeClock clock;
    InMemoryCheckpointStore store;
    FlakyRepo repo;
    Coordinator coordinator;

    @BeforeEach
    void setUp() {
        clock = new FakeClock();
        store = new InMemoryCheckpointStore();
        repo = new FlakyRepo();
        coordinator = newCoordinator();
    }

    Coordinator newCoordinator() {
        return new Coordinator(repo, store, (topic, partitions, startFrom) -> CP0, clock, 10_000, true);
    }

    static ExecutionPlan plan() {
        return new ExecutionPlan("logistics-demo", 1, "truck-events", 6, "latest", List.of());
    }

    static WorkerStatus status(String id, String execId, long gen, String state, List<Integer> partitions) {
        return new WorkerStatus(1, id, execId, gen, state, partitions, new QueueStatus(0, 1000),
                new QueueStatus(0, 1000), false, 0, 0.0, 0, Map.of());
    }

    void register(String id) {
        coordinator.register(new RegisterRequest(1, id, "host-" + id));
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

    void ack(String worker, long gen, long cp, List<Integer> partitions) {
        Map<Integer, Long> offsets = new TreeMap<>();
        partitions.forEach(p -> offsets.put(p, 100L + p));
        coordinator.ack(new CheckpointAck(1, worker, EXEC, gen, cp, offsets, "k/" + cp + "/" + worker));
    }

    void startRunning() {
        register("w-a");
        register("w-b");
        coordinator.startExecution(new StartExecutionRequest(EXEC, plan()));
        idleHb("w-a");
        idleHb("w-b");
        hb("w-a", 1, "RUNNING", EVEN);
        hb("w-b", 1, "RUNNING", ODD);
    }

    String state() {
        return coordinator.clusterState().execution().state();
    }

    long generation() {
        return coordinator.clusterState().execution().generation();
    }

    /** w-b stops heartbeating: after the 4 s timeout the tick declares it dead and recovers onto w-a. */
    void killWorkerB() {
        for (int i = 0; i < 4; i++) {
            clock.now += 1000;
            hb("w-a", generation(), "RUNNING", EVEN);
        }
        coordinator.tick();
    }

    // ------------------------------------------------------------------ bug 1: lost ASSIGN

    @Test
    void lostAssignResponseIsResentOnNextHeartbeat() {
        startRunning();
        killWorkerB();
        assertThat(state()).isEqualTo("RECOVERING");
        assertThat(generation()).isEqualTo(2);
        // The heartbeat response carrying CANCEL g1 + ASSIGN g2 is lost (worker-side timeout).
        assertThat(ofType(hb("w-a", 1, "RUNNING", EVEN), Command.Assign.class)).hasSize(1);
        clock.now += 1000;
        assertThat(ofType(hb("w-a", 1, "RUNNING", EVEN), Command.Assign.class)).isEmpty(); // rate limit
        clock.now += 1000;
        List<Command.Assign> resent = ofType(hb("w-a", 1, "RUNNING", EVEN), Command.Assign.class);
        assertThat(resent).hasSize(1);
        assertThat(resent.get(0).generation()).isEqualTo(2);
        assertThat(resent.get(0).partitions()).containsExactly(0, 1, 2, 3, 4, 5);

        hb("w-a", 2, "RUNNING", resent.get(0).partitions());
        assertThat(state()).isEqualTo("RUNNING");
    }

    @Test
    void memberWhoseRuntimeFailedGetsAssignAndPendingTriggerAgain() {
        startRunning();
        long cp = coordinator.triggerCheckpointNow(EXEC);
        hb("w-a", 1, "RUNNING", EVEN);
        clock.now += 2000;
        // w-a's runtime stopped by itself (e.g. Kafka source failure): it reports IDLE in the current generation.
        assertThat(ofType(hb("w-a", 1, "IDLE", List.of()), Command.Assign.class)).isEmpty(); // grace period
        clock.now += 1000;
        hb("w-b", 1, "RUNNING", ODD);
        clock.now += 1000;
        hb("w-b", 1, "RUNNING", ODD);
        clock.now += 1000;
        hb("w-b", 1, "RUNNING", ODD);
        assertThat(ofType(hb("w-a", 1, "IDLE", List.of()), Command.Assign.class)).isEmpty();
        clock.now += 1000;
        hb("w-b", 1, "RUNNING", ODD);
        List<Command> cmds = hb("w-a", 1, "IDLE", List.of());
        assertThat(ofType(cmds, Command.Assign.class)).singleElement()
                .satisfies(a -> assertThat(a.partitions()).containsExactly(0, 2, 4));
        assertThat(ofType(cmds, Command.TriggerCheckpoint.class))
                .containsExactly(new Command.TriggerCheckpoint(EXEC, 1, cp));
        // Running members are left alone.
        assertThat(ofType(hb("w-b", 1, "RUNNING", ODD), Command.Assign.class)).isEmpty();
    }

    // ------------------------------------------------------------------ bug 2: rejoin during RECOVERING

    @Test
    void workerRejoiningWhileRecoveringBecomesRebalanceCandidate() {
        startRunning();
        killWorkerB();
        Command.Assign a = ofType(hb("w-a", 1, "RUNNING", EVEN), Command.Assign.class).get(0);
        // The killed container restarts with the same WORKER_ID while the execution is still RECOVERING.
        register("w-b");
        assertThat(state()).isEqualTo("RECOVERING");
        hb("w-a", 2, "RUNNING", a.partitions());
        idleHb("w-b");
        assertThat(state()).isEqualTo("RUNNING");

        clock.now += 5000;
        hb("w-a", 2, "RUNNING", a.partitions());
        idleHb("w-b");
        coordinator.tick();
        // Rebalance scheduled: a checkpoint is triggered long before the 10 s interval.
        assertThat(repo.findCheckpoint(EXEC, 1)).isPresent();
        ack("w-a", 2, 1, a.partitions());
        assertThat(generation()).isEqualTo(3);
        assertThat(ofType(idleHb("w-b"), Command.Assign.class)).singleElement()
                .satisfies(b -> assertThat(b.partitions()).containsExactly(1, 3, 5));
    }

    // ------------------------------------------------------------------ bug 3: DB hiccup mid-recovery

    @Test
    void auditFailureWhileDeclaringWorkerDeadStillRecovers() {
        startRunning();
        repo.failAuditAction = "worker.dead";
        killWorkerB();
        assertThat(state()).isEqualTo("RECOVERING");
        assertThat(generation()).isEqualTo(2);
    }

    @Test
    void failedStateUpdateInBeginRecoveryIsRetriedByTheNextTick() {
        startRunning();
        repo.failUpdateState = "RECOVERING";
        try {
            killWorkerB();
        } catch (IllegalStateException expected) {
            // the tick fails half-way (CoordinatorMain logs it and ticks again 250 ms later)
        }
        coordinator.tick();
        List<Command.Assign> assigns = ofType(hb("w-a", 1, "RUNNING", EVEN), Command.Assign.class);
        assertThat(assigns).singleElement().satisfies(x -> {
            assertThat(x.generation()).isEqualTo(2);
            assertThat(x.partitions()).containsExactly(0, 1, 2, 3, 4, 5);
        });
        hb("w-a", 2, "RUNNING", assigns.get(0).partitions());
        assertThat(state()).isEqualTo("RUNNING");
    }

    @Test
    void inMemoryGenerationFollowsDatabaseEvenIfALaterRepoCallFails() {
        startRunning();
        repo.failSetRestoredFrom = 2; // the tick's detectFailures and maybeAssign both attempt recoverAssign
        killWorkerB();
        assertThat(generation()).isEqualTo(repo.findExecution(EXEC).orElseThrow().generation());
    }

    // ------------------------------------------------------------------ bug 4: restart grace window

    @Test
    void rebalanceRequestDuringRestartGraceWindowIsRejected() {
        startRunning();
        coordinator = newCoordinator();
        coordinator.loadActiveExecutions();
        register("w-a");
        register("w-b");
        assertThatThrownBy(() -> coordinator.requestRebalance(EXEC)).isInstanceOf(IllegalStateException.class);
        assertThat(repo.maxCheckpointId(EXEC)).isZero();
    }

    // ------------------------------------------------------------------ checked: same-id restart

    @Test
    void sameIdReRegistrationIsANewIncarnationWithoutStaleCommands() {
        startRunning();
        coordinator.triggerCheckpointNow(EXEC);
        coordinator.killWorker("w-b");                 // queued, never delivered: the container dies on its own
        register("w-b");                               // compose restarts it with WORKER_ID=w-b before the timeout
        assertThat(state()).isEqualTo("RECOVERING");
        assertThat(generation()).isEqualTo(2);
        List<Command> cmds = idleHb("w-b");
        assertThat(cmds).noneMatch(c -> c instanceof Command.Kill || c instanceof Command.TriggerCheckpoint);
        assertThat(ofType(cmds, Command.Assign.class)).singleElement()
                .satisfies(x -> assertThat(x.generation()).isEqualTo(2));
        // An in-flight ack of the old incarnation (generation 1) cannot complete anything.
        assertThat(coordinator.ack(new CheckpointAck(1, "w-b", EXEC, 1, 1, Map.of(1, 5L), "k")).accepted()).isFalse();
    }
}
