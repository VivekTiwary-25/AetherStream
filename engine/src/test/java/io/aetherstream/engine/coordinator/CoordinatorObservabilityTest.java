package io.aetherstream.engine.coordinator;

import static org.assertj.core.api.Assertions.assertThat;

import io.aetherstream.common.pipeline.ExecutionPlan;
import io.aetherstream.common.protocol.CheckpointAck;
import io.aetherstream.common.protocol.QueueStatus;
import io.aetherstream.common.protocol.RegisterRequest;
import io.aetherstream.common.protocol.StartExecutionRequest;
import io.aetherstream.common.protocol.WorkerStatus;
import io.aetherstream.engine.storage.InMemoryCheckpointStore;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CoordinatorObservabilityTest {
    private final AtomicLong now = new AtomicLong(10_000);
    private final InMemoryExecutionRepository repo = new InMemoryExecutionRepository();
    private final Coordinator coordinator = new Coordinator(repo, new InMemoryCheckpointStore(),
            (topic, partitions, startFrom) -> Map.of(0, 0L, 1, 0L), now::get, 10_000, true);

    @Test void fastSameIdRegistrationAuditsDeathAndRestartEvenThoughEveryStateSampleIsAlive() {
        startRunning();
        assertThat(coordinator.clusterState().workers()).allMatch(worker -> worker.alive());
        now.addAndGet(50);
        coordinator.register(new RegisterRequest(1, "worker-2", "host-2"));
        assertThat(coordinator.clusterState().workers()).allMatch(worker -> worker.alive());
        assertThat(repo.auditRows().stream().filter(row -> row.action().startsWith("worker.")).toList())
                .extracting(InMemoryExecutionRepository.AuditRow::action)
                .containsExactly("worker.dead", "worker.restarted");
        assertThat(row("worker.dead").detail()).containsEntry("workerId", "worker-2")
                .containsEntry("reason", "re-registered").containsEntry("atMs", 10_050L);
        assertThat(row("worker.restarted").detail()).containsEntry("workerId", "worker-2")
                .containsEntry("durationMs", null);
        now.addAndGet(300);
        heartbeat("worker-1", 2, List.of(0));
        heartbeat("worker-2", 2, List.of(1));
        assertThat(row("execution.recovered").detail()).containsEntry("recoveryDurationMs", 300L)
                .containsEntry("generation", 2L).containsEntry("checkpointId", 0L);
        assertThat(repo.auditRows().stream().filter(row -> row.action().equals("execution.recoveryStarted")))
                .hasSize(1);
    }

    @Test void failureAssignmentAuditsActualOldAndNewOwnersAndObservedRestartGap() {
        startRunning();
        now.addAndGet(Coordinator.HEARTBEAT_TIMEOUT_MS);
        heartbeat("worker-1", 1, List.of(0));
        coordinator.tick();
        var assignment = repo.auditRows().stream().filter(row -> row.action().equals("execution.assignment"))
                .reduce((first, last) -> last).orElseThrow();
        assertThat(assignment.detail()).containsEntry("oldAssignment", Map.of("worker-1", List.of(0), "worker-2", List.of(1)))
                .containsEntry("assignment", Map.of("worker-1", List.of(0, 1)));
        now.addAndGet(700);
        coordinator.register(new RegisterRequest(1, "worker-2", "host-2"));
        assertThat(row("worker.restarted").detail()).containsEntry("durationMs", 700L);
    }

    @Test void checkpointAuditRequiresCompletedCheckpointAndContainsMeasuredDuration() {
        startRunning();
        long checkpoint = coordinator.triggerCheckpointNow("exec-1");
        assertThat(repo.auditRows()).noneMatch(row -> row.action().equals("checkpoint.completed"));
        now.addAndGet(25);
        coordinator.ack(new CheckpointAck(1, "worker-1", "exec-1", 1, checkpoint, Map.of(0, 11L), "worker-1.json"));
        assertThat(repo.auditRows()).noneMatch(row -> row.action().equals("checkpoint.completed"));
        now.addAndGet(15);
        coordinator.ack(new CheckpointAck(1, "worker-2", "exec-1", 1, checkpoint, Map.of(1, 13L), "worker-2.json"));
        assertThat(row("checkpoint.completed").detail()).containsEntry("checkpointId", checkpoint)
                .containsEntry("generation", 1L).containsEntry("durationMs", 40L).containsEntry("atMs", 10_040L);
    }

    private void startRunning() {
        coordinator.register(new RegisterRequest(1, "worker-1", "host-1"));
        coordinator.register(new RegisterRequest(1, "worker-2", "host-2"));
        coordinator.startExecution(new StartExecutionRequest("exec-1",
                new ExecutionPlan("pipeline", 1, "events", 2, "latest", List.of())));
        heartbeat("worker-1", 1, List.of(0));
        heartbeat("worker-2", 1, List.of(1));
    }

    private void heartbeat(String workerId, long generation, List<Integer> partitions) {
        coordinator.heartbeat(workerId, new WorkerStatus(1, workerId, "exec-1", generation, "RUNNING", partitions,
                new QueueStatus(0, 10), new QueueStatus(0, 10), false, 0, 0, 0, Map.of()));
    }

    private InMemoryExecutionRepository.AuditRow row(String action) {
        return repo.auditRows().stream().filter(row -> row.action().equals(action)).findFirst().orElseThrow();
    }
}
