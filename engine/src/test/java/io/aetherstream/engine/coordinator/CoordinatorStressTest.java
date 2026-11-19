package io.aetherstream.engine.coordinator;

import static org.assertj.core.api.Assertions.assertThat;

import io.aetherstream.common.pipeline.ExecutionPlan;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * 200 simulated chaos cycles against the real Coordinator with a fake clock: workers with fixed ids (compose
 * WORKER_ID=worker-N) are killed (silently or via KILL), restarted with the same id, heartbeat responses are lost,
 * checkpoints are forced and the coordinator itself restarts mid-run. Invariants are checked after every tick.
 */
class CoordinatorStressTest {
    static final String EXEC = "exec-stress";
    static final int PARTITIONS = 6;
    static final long STEP_MS = 250;
    static final int MAX_QUEUE = 8;

    static final class FakeClock implements Clock {
        long now = 1_795_251_600_000L;

        @Override
        public long nowMs() {
            return now;
        }
    }

    /** Simulated worker process, mirroring Worker's command rules (contract 03 section 2). */
    static final class SimWorker {
        final String id;
        final int slot;
        boolean up = true;
        String exec;
        long gen;
        String state = "IDLE";
        List<Integer> partitions = List.of();
        final List<Long> triggers = new ArrayList<>();
        long nextOffset = 1000;

        SimWorker(String id, int slot) {
            this.id = id;
            this.slot = slot;
        }

        void restart() {
            up = true;
            exec = null;
            gen = 0;
            state = "IDLE";
            partitions = List.of();
            triggers.clear();
        }

        WorkerStatus status() {
            return new WorkerStatus(1, id, exec, gen, state, partitions, new QueueStatus(0, 1000),
                    new QueueStatus(0, 1000), false, 0, 0.0, 0, Map.of());
        }

        boolean stale(String e, long g) {
            return e.equals(exec) && g < gen;
        }

        void handle(Command c) {
            if (c instanceof Command.Kill) {
                up = false;
            } else if (c instanceof Command.Assign a) {
                if (stale(a.executionId(), a.generation())) {
                    return;
                }
                if (a.executionId().equals(exec) && a.generation() == gen && !"IDLE".equals(state)) {
                    return; // idempotent
                }
                exec = a.executionId();
                gen = a.generation();
                partitions = List.copyOf(a.partitions());
                state = "RESTORING";
                triggers.clear();
            } else if (c instanceof Command.Cancel x) {
                if (stale(x.executionId(), x.generation())) {
                    return;
                }
                if (x.executionId().equals(exec)) {
                    state = "IDLE";
                    partitions = List.of();
                    triggers.clear();
                    gen = Math.max(gen, x.generation());
                }
            } else if (c instanceof Command.TriggerCheckpoint t) {
                if (!"IDLE".equals(state) && t.executionId().equals(exec) && t.generation() == gen) {
                    triggers.add(t.checkpointId());
                }
            }
        }
    }

    final FakeClock clock = new FakeClock();
    final InMemoryCheckpointStore store = new InMemoryCheckpointStore();
    final InMemoryExecutionRepository repo = new InMemoryExecutionRepository();
    final Random random = new Random(20261119L);
    final List<SimWorker> sims = new ArrayList<>();
    Coordinator coordinator;
    long lastGeneration;
    /** generation -> workerId -> partitions of every ASSIGN ever delivered. */
    final Map<Long, Map<String, List<Integer>>> assignsByGeneration = new HashMap<>();
    long maxAssignGeneration;
    int coordinatorRestarts;
    int kills;
    int lostResponses;

    Coordinator newCoordinator() {
        Map<Integer, Long> zero = new TreeMap<>();
        for (int p = 0; p < PARTITIONS; p++) {
            zero.put(p, 0L);
        }
        return new Coordinator(repo, store, (topic, partitions, startFrom) -> zero, clock, 10_000, true);
    }

    @Test
    void invariantsHoldThrough200KillRejoinCheckpointCycles() {
        coordinator = newCoordinator();
        for (int i = 1; i <= 4; i++) {
            SimWorker w = new SimWorker("worker-" + i, i % 4);
            sims.add(w);
            coordinator.register(new RegisterRequest(1, w.id, w.id));
        }
        coordinator.startExecution(new StartExecutionRequest(EXEC,
                new ExecutionPlan("logistics-demo", 1, "truck-events", PARTITIONS, "latest", List.of())));

        for (int cycle = 1; cycle <= 200; cycle++) {
            chaos();
            int steps = 4 + random.nextInt(16);
            for (int s = 0; s < steps; s++) {
                step(0.1);
            }
            if (cycle % 10 == 0) {
                assertConverges("after cycle " + cycle, false);
            }
        }
        // Finally every process is up again (same ids) and the cluster must still converge.
        assertConverges("after restarting every worker", true);
        assertThat(kills).isPositive();
        assertThat(coordinatorRestarts).isPositive();
        assertThat(lostResponses).isPositive();
        assertThat(lastGeneration).isGreaterThan(5);
    }

    /**
     * Liveness: with no further chaos and no lost responses, the execution must reach RUNNING and complete a new
     * checkpoint within 100 simulated seconds (at least one worker process is up; nothing else is restarted, so a
     * stuck recovery cannot be rescued by an incidental re-registration).
     */
    void assertConverges(String when, boolean restartAll) {
        for (SimWorker w : sims) {
            if (!w.up && (restartAll || sims.stream().noneMatch(x -> x.up))) {
                restart(w);
            }
        }
        long completedBefore = lastCompleted();
        boolean converged = false;
        for (int s = 0; s < 400 && !converged; s++) {
            step(0.0);
            converged = "RUNNING".equals(coordinator.clusterState().execution().state())
                    && lastCompleted() > completedBefore;
        }
        assertThat(converged).as("execution converges to RUNNING and completes a checkpoint " + when
                + " (state " + coordinator.clusterState().execution().state() + ")").isTrue();
    }

    long lastCompleted() {
        Long c = coordinator.clusterState().execution().lastCompletedCheckpoint();
        return c == null ? -1 : c;
    }

    void restart(SimWorker w) {
        w.restart();
        coordinator.register(new RegisterRequest(1, w.id, w.id));
    }

    void chaos() {
        int roll = random.nextInt(100);
        List<SimWorker> upWorkers = sims.stream().filter(w -> w.up).toList();
        List<SimWorker> down = sims.stream().filter(w -> !w.up).toList();
        if (roll < 20 && upWorkers.size() > 1) {
            upWorkers.get(random.nextInt(upWorkers.size())).up = false; // silent crash
            kills++;
        } else if (roll < 30 && upWorkers.size() > 1) {
            try {
                coordinator.killWorker(null);
                kills++;
            } catch (RuntimeException ignored) {
                // no live worker known (e.g. right after a coordinator restart)
            }
        } else if (roll < 55 && !down.isEmpty()) {
            restart(down.get(random.nextInt(down.size())));
        } else if (roll < 70) {
            try {
                coordinator.triggerCheckpointNow(EXEC);
            } catch (IllegalStateException ignored) {
                // not RUNNING right now
            }
        } else if (roll < 75) {
            coordinator = newCoordinator();          // CD redeploy: coordinator restarts, workers keep running
            coordinator.loadActiveExecutions();
            coordinatorRestarts++;
        } else if (roll < 80) {
            try {
                coordinator.requestRebalance(EXEC);
            } catch (IllegalStateException ignored) {
                // not needed / rate-limited / not RUNNING
            }
        }
    }

    void step(double lossRate) {
        clock.now += STEP_MS;
        for (SimWorker w : sims) {
            if (!w.up) {
                continue;
            }
            if ("RESTORING".equals(w.state)) {
                w.state = "RUNNING";
            }
            if (!w.triggers.isEmpty() && "RUNNING".equals(w.state)) {
                for (long cp : w.triggers) {
                    Map<Integer, Long> offsets = new TreeMap<>();
                    for (int p : w.partitions) {
                        offsets.put(p, w.nextOffset++);
                    }
                    coordinator.ack(new CheckpointAck(1, w.id, w.exec, w.gen, cp, offsets,
                            "executions/" + w.exec + "/checkpoints/" + cp + "/worker-" + w.id + "@g" + w.gen));
                }
                w.triggers.clear();
            }
            if ((clock.now / STEP_MS) % 4 == w.slot) { // one heartbeat per second, staggered
                List<Command> commands = coordinator.heartbeat(w.id, w.status()).commands();
                assertThat(commands.size()).as("heartbeat response size").isLessThanOrEqualTo(MAX_QUEUE);
                if (random.nextDouble() < lossRate) {
                    lostResponses++;
                    continue; // response lost in transit: the coordinator already drained the queue
                }
                for (Command c : commands) {
                    if (c instanceof Command.Assign a) {
                        recordAssign(w.id, a);
                    }
                    w.handle(c);
                }
            }
        }
        coordinator.tick();
        checkInvariants();
    }

    void recordAssign(String workerId, Command.Assign a) {
        assertThat(a.generation()).as("ASSIGN generation never goes back").isGreaterThanOrEqualTo(maxAssignGeneration);
        maxAssignGeneration = a.generation();
        Map<String, List<Integer>> gen = assignsByGeneration.computeIfAbsent(a.generation(), g -> new HashMap<>());
        List<Integer> previous = gen.putIfAbsent(workerId, List.copyOf(a.partitions()));
        if (previous != null) {
            assertThat(a.partitions()).as("one assignment per generation").isEqualTo(previous);
        }
        Set<Integer> seen = new HashSet<>();
        for (List<Integer> ps : gen.values()) {
            for (int p : ps) {
                assertThat(seen.add(p)).as("partition %d assigned twice in generation %d", p, a.generation()).isTrue();
            }
        }
    }

    void checkInvariants() {
        ClusterState cs = coordinator.clusterState();
        long generation = cs.execution().generation();
        long dbGeneration = repo.findExecution(EXEC).orElseThrow().generation();
        assertThat(generation).as("in-memory generation == DB fencing token").isEqualTo(dbGeneration);
        assertThat(generation).as("generation never decreases").isGreaterThanOrEqualTo(lastGeneration);
        lastGeneration = generation;

        // No partition owned by two live workers in the current generation (worker side and coordinator side).
        Set<Integer> owned = new HashSet<>();
        for (SimWorker w : sims) {
            if (w.up && EXEC.equals(w.exec) && w.gen == generation && !"IDLE".equals(w.state)) {
                for (int p : w.partitions) {
                    assertThat(owned.add(p)).as("partition %d run twice in generation %d", p, generation).isTrue();
                }
            }
        }
        Set<Integer> assigned = new HashSet<>();
        for (ClusterState.WorkerView v : cs.workers()) {
            for (int p : v.partitions()) {
                assertThat(v.alive()).isTrue();
                assertThat(assigned.add(p)).as("partition %d assigned twice", p).isTrue();
            }
        }

        // COMPLETED checkpoints: manifest covers all partitions, every ack from the checkpoint's single generation.
        for (long id = 1; id <= repo.maxCheckpointId(EXEC); id++) {
            CheckpointRecord cp = repo.findCheckpoint(EXEC, id).orElseThrow();
            if (!CheckpointRecord.COMPLETED.equals(cp.state())) {
                continue;
            }
            CheckpointManifest m = store.getJson(cp.manifestKey(), CheckpointManifest.class);
            assertThat(m).as("manifest of COMPLETED checkpoint %d", id).isNotNull();
            assertThat(m.partitions().keySet()).hasSize(PARTITIONS);
            assertThat(m.generation()).isEqualTo(cp.generation());
            for (CheckpointManifest.PartitionEntry e : m.partitions().values()) {
                assertThat(e.objectKey()).as("checkpoint %d ack generation", id).endsWith("@g" + cp.generation());
            }
        }

        assertThat(coordinator.maxQueuedCommands()).as("command queues bounded").isLessThanOrEqualTo(MAX_QUEUE);
    }
}
