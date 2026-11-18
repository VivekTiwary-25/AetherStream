package io.aetherstream.tests;

import static io.aetherstream.tests.WorkerCrashRecoveryIT.await;
import static io.aetherstream.tests.WorkerCrashRecoveryIT.earliestDemoPlan;
import static io.aetherstream.tests.WorkerCrashRecoveryIT.post;
import static io.aetherstream.tests.WorkerCrashRecoveryIT.repository;
import static org.assertj.core.api.Assertions.assertThat;

import io.aetherstream.common.protocol.Command;
import io.aetherstream.common.protocol.StartExecutionRequest;
import io.aetherstream.engine.coordinator.CheckpointRecord;
import io.aetherstream.engine.coordinator.Coordinator;
import io.aetherstream.engine.coordinator.ExecutionRecord;
import io.aetherstream.engine.coordinator.PostgresExecutionRepository;
import io.aetherstream.engine.storage.S3CheckpointStore;
import io.aetherstream.tests.support.EngineCluster;
import io.aetherstream.tests.support.IntegrationInfrastructure;
import io.aetherstream.tests.support.WorkloadGenerator;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.apache.kafka.common.utils.Utils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class IncompleteCheckpointIT {
    @Test
    void abortsInterruptedCheckpointAndRestoresPreviousCompletedManifest() throws Exception {
        IntegrationInfrastructure.truncateAll();
        var workload = WorkloadGenerator.generate();
        String executionId = "incomplete-checkpoint-" + UUID.randomUUID();
        var repository = repository();
        var store = new S3CheckpointStore(IntegrationInfrastructure.s3Endpoint(), "aether", "aether-secret",
                IntegrationInfrastructure.BUCKET);

        try (var cluster = new EngineCluster(3)) {
            assertThat(post(cluster, "/coord/executions",
                    new StartExecutionRequest(executionId, earliestDemoPlan())).path("id").asText())
                    .isEqualTo(executionId);
            await(cluster, Duration.ofSeconds(15), "all three workers assigned and running", () -> {
                for (int i = 0; i < 3; i++) {
                    var status = cluster.worker(i).status();
                    if (!"RUNNING".equals(status.state()) || status.partitions().isEmpty()) return false;
                }
                return "RUNNING".equals(repository.findExecution(executionId).orElseThrow().state());
            });
            await(cluster, Duration.ofSeconds(15), "nonzero completed checkpoint before slowing the sink", () ->
                    repository.latestCompleted(executionId)
                            .filter(cp -> cp.checkpointId() > 0 && CheckpointRecord.COMPLETED.equals(cp.state()))
                            .isPresent());

            int firstPartition = Utils.toPositive(Utils.murmur2(
                    workload.getFirst().key().getBytes(StandardCharsets.UTF_8))) % 6;
            int victimIndex = IntStream.range(0, 3)
                    .filter(i -> cluster.worker(i).status().partitions().contains(firstPartition))
                    .findFirst().orElseThrow();
            var victim = cluster.worker(victimIndex);
            victim.handle(new Command.SetSinkDelay(60_000));
            for (int i = 0; i < 3; i++) {
                if (i != victimIndex) assertThat(cluster.worker(i).sinkDelayMs()).isZero();
            }

            // Produce concurrently so the crash does not wait for thousands of synchronous Kafka sends;
            // the interrupted checkpoint must be observed and aborted before its 30-second timeout.
            try (var producers = Executors.newSingleThreadExecutor()) {
                var produced = producers.submit(() -> IntegrationInfrastructure.produce(workload));
                try {
                    await(cluster, Duration.ofSeconds(10), "delayed worker processing with a sink backlog", () -> {
                        if (produced.isDone()) produced.get();
                        var status = victim.status();
                        return status.processedRecords() > 0 && status.sinkQueue().size() > 0;
                    });
                    long pendingId = post(cluster, "/coord/executions/" + executionId + "/checkpoint", null)
                            .path("checkpointId").asLong();
                    await(cluster, Duration.ofSeconds(5), "the exact triggered checkpoint is pending", () ->
                            repository.findCheckpoint(executionId, pendingId)
                                    .filter(cp -> CheckpointRecord.PENDING.equals(cp.state())).isPresent());
                    CheckpointRecord pending = repository.findCheckpoint(executionId, pendingId).orElseThrow();
                    CheckpointRecord previous = repository.latestCompleted(executionId).orElseThrow();
                    ExecutionRecord beforeCrash = repository.findExecution(executionId).orElseThrow();
                    assertThat(previous.checkpointId()).isPositive().isLessThan(pendingId);
                    assertThat(previous.state()).isEqualTo(CheckpointRecord.COMPLETED);
                    assertThat(pending.state()).isEqualTo(CheckpointRecord.PENDING);
                    assertThat(pending.generation()).isEqualTo(beforeCrash.generation());
                    assertThat(victim.status().lastCheckpointId()).isLessThan(pendingId);

                    long crashedAt = System.nanoTime();
                    cluster.crash(victimIndex);
                    assertRecoversBeforeTimeout(cluster, repository, executionId, beforeCrash.generation(),
                            previous.checkpointId(), pending, crashedAt);

                    assertThat(store.exists(Coordinator.manifestKey(executionId, pendingId)))
                            .as("an incomplete checkpoint must never publish a manifest").isFalse();
                    assertThat(store.exists(Coordinator.manifestKey(executionId, previous.checkpointId())))
                            .as("the previous completed checkpoint remains restorable").isTrue();
                    for (int i = 0; i < 3; i++) {
                        if (i == victimIndex) continue;
                        var survivor = cluster.worker(i);
                        assertThat(survivor.sinkDelayMs()).isZero();
                        assertThat(survivor.status().state()).isEqualTo("RUNNING");
                        assertThat(survivor.status().generation()).isEqualTo(beforeCrash.generation() + 1);
                    }
                    produced.get(60, TimeUnit.SECONDS);
                    cluster.checkHealthy();
                } finally {
                    produced.cancel(true);
                }
            }
        }
    }

    private static void assertRecoversBeforeTimeout(EngineCluster cluster, PostgresExecutionRepository repository,
            String executionId, long previousGeneration, long previousCheckpointId, CheckpointRecord pending,
            long crashedAt) throws Exception {
        long deadline = crashedAt + Duration.ofSeconds(15).toNanos();
        boolean sawRecovering = false;
        ExecutionRecord recovered = null;
        ExecutionRecord lastExecution = null;
        CheckpointRecord lastCheckpoint = pending;
        long abortedAfterMs = -1;
        while (System.nanoTime() < deadline) {
            cluster.checkHealthy();
            lastExecution = repository.findExecution(executionId).orElseThrow();
            lastCheckpoint = repository.findCheckpoint(executionId, pending.checkpointId()).orElseThrow();
            sawRecovering |= "RECOVERING".equals(lastExecution.state());
            if (CheckpointRecord.ABORTED.equals(lastCheckpoint.state()) && abortedAfterMs < 0) {
                abortedAfterMs = System.currentTimeMillis() - pending.startedAtMs();
            }
            if ("RUNNING".equals(lastExecution.state()) && lastExecution.generation() > previousGeneration
                    && CheckpointRecord.ABORTED.equals(lastCheckpoint.state())) {
                recovered = lastExecution;
                break;
            }
            Thread.sleep(25);
        }
        assertThat(sawRecovering).as("observe RECOVERING in Postgres before RUNNING").isTrue();
        assertThat(recovered).as("recover within 15 seconds; execution=%s, checkpoint=%s",
                lastExecution, lastCheckpoint).isNotNull();
        assertThat(System.nanoTime() - crashedAt).isLessThan(Duration.ofSeconds(15).toNanos());
        assertThat(recovered.state()).isEqualTo("RUNNING");
        assertThat(recovered.generation()).isEqualTo(previousGeneration + 1);
        assertThat(recovered.restoredFromCheckpoint()).isEqualTo(previousCheckpointId);
        assertThat(lastCheckpoint.state()).isEqualTo(CheckpointRecord.ABORTED);
        assertThat(abortedAfterMs).as("failure aborts the checkpoint, not the 30-second timeout")
                .isBetween(0L, Duration.ofSeconds(30).toMillis() - 1);
    }
}
