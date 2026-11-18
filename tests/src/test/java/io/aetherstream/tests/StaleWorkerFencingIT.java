package io.aetherstream.tests;

import static io.aetherstream.tests.WorkerCrashRecoveryIT.await;
import static io.aetherstream.tests.WorkerCrashRecoveryIT.earliestDemoPlan;
import static io.aetherstream.tests.WorkerCrashRecoveryIT.post;
import static io.aetherstream.tests.WorkerCrashRecoveryIT.repository;
import static org.assertj.core.api.Assertions.assertThat;

import io.aetherstream.common.protocol.StartExecutionRequest;
import io.aetherstream.engine.coordinator.CheckpointRecord;
import io.aetherstream.engine.worker.TaskRuntime;
import io.aetherstream.tests.support.EngineCluster;
import io.aetherstream.tests.support.IntegrationInfrastructure;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class StaleWorkerFencingIT {
    private static final Duration DEADLINE = Duration.ofSeconds(15);

    @Test
    void staleWorkerRejectsItsNextNonemptySinkTransactionAndStops() throws Exception {
        IntegrationInfrastructure.truncateAll();
        String executionId = "stale-worker-" + UUID.randomUUID();
        var repository = repository();
        try (var cluster = new EngineCluster(1)) {
            post(cluster, "/coord/executions", new StartExecutionRequest(executionId, earliestDemoPlan()));
            var worker = cluster.worker(0);
            await(cluster, DEADLINE, "worker running on the empty topic", () ->
                    worker.status().state().equals("RUNNING"));
            TaskRuntime runtime = worker.runtime();
            assertThat(runtime).isNotNull();
            long oldGeneration = worker.status().generation();
            assertThat(runtime.processedRecords()).isZero();
            var initial = repository.findCheckpoint(executionId, 0).orElseThrow();
            assertThat(initial.sourceOffsets()).hasSize(6);
            assertThat(initial.sourceOffsets().values()).containsOnly(0L);

            // Drain any initial empty checkpoint before arranging the nonempty transaction.
            long checkpointId = post(cluster, "/coord/executions/" + executionId + "/checkpoint", null)
                    .path("checkpointId").asLong();
            await(cluster, DEADLINE, "initial empty checkpoint completed", () ->
                    repository.findCheckpoint(executionId, checkpointId)
                            .map(checkpoint -> checkpoint.state().equals(CheckpointRecord.COMPLETED))
                            .orElse(false));

            OutputCounts before;
            try (Connection lock = DriverManager.getConnection(
                    IntegrationInfrastructure.jdbcUrl(), "aether", "aether")) {
                lock.setAutoCommit(false);
                try (var select = lock.prepareStatement(
                        "SELECT generation FROM executions WHERE id = ? FOR UPDATE")) {
                    select.setQueryTimeout(15);
                    select.setString(1, executionId);
                    try (var rows = select.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getLong(1)).isEqualTo(oldGeneration);
                    }
                }
                try (var update = lock.prepareStatement(
                        "UPDATE executions SET generation = generation + 1 WHERE id = ?")) {
                    update.setQueryTimeout(15);
                    update.setString(1, executionId);
                    assertThat(update.executeUpdate()).isEqualTo(1);
                }

                // Invalid JSON deterministically emits a REJECTED row, even without a window firing.
                IntegrationInfrastructure.produce(List.of(new ProducerRecord<>(
                        IntegrationInfrastructure.TOPIC, 0, "fenced-record", "{not-json")));
                await(cluster, DEADLINE, "malformed record processed and output queued", () ->
                        runtime.processedRecords() == 1);
                await(cluster, DEADLINE, "nonempty sink batch blocked on the execution fence", () ->
                        runtime.sinkQueueSize() == 0 && sinkWaitingForFence(lock));
                // An earlier empty barrier cannot satisfy this: it would leave our output in the queue.
                assertThat(runtime.isStopped()).isFalse();
                assertThat(runtime.isFenced()).isFalse();
                before = outputCounts(lock, executionId);
                assertThat(before).isEqualTo(new OutputCounts(0, 0, 0));
                lock.commit();
            }

            await(cluster, DEADLINE, "stale runtime fenced and stopped with worker idle", () ->
                    runtime.isFenced() && runtime.isStopped() && worker.status().state().equals("IDLE"));
            assertThat(runtime.generation()).isEqualTo(oldGeneration);
            assertThat(worker.status().generation()).isEqualTo(oldGeneration);
            assertThat(runtime.processedRecords()).isEqualTo(1);
            var execution = repository.findExecution(executionId).orElseThrow();
            assertThat(execution.generation()).isEqualTo(oldGeneration + 1);
            assertThat(execution.state()).isEqualTo("RUNNING");
            try (Connection connection = DriverManager.getConnection(
                    IntegrationInfrastructure.jdbcUrl(), "aether", "aether")) {
                assertThat(outputCounts(connection, executionId)).isEqualTo(before);
            }
            cluster.checkHealthy();
        }
    }

    private static boolean sinkWaitingForFence(Connection lock) throws Exception {
        // Activity snapshots otherwise remain cached for this deliberately long-lived transaction.
        try (var clear = lock.createStatement()) {
            clear.setQueryTimeout(5);
            clear.execute("SELECT pg_stat_clear_snapshot()");
        }
        try (var statement = lock.prepareStatement("""
                SELECT EXISTS (
                    SELECT 1 FROM pg_stat_activity
                    WHERE query LIKE 'SELECT generation FROM executions WHERE id = % FOR SHARE'
                      AND pg_backend_pid() = ANY(pg_blocking_pids(pid))
                )
                """)) {
            statement.setQueryTimeout(5);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getBoolean(1);
            }
        }
    }

    private static OutputCounts outputCounts(Connection connection, String executionId) throws Exception {
        try (var statement = connection.prepareStatement("""
                SELECT (SELECT count(*) FROM window_results WHERE execution_id = ?),
                       (SELECT count(*) FROM alerts WHERE execution_id = ?),
                       (SELECT count(*) FROM dead_letters WHERE execution_id = ?)
                """)) {
            statement.setQueryTimeout(5);
            statement.setString(1, executionId);
            statement.setString(2, executionId);
            statement.setString(3, executionId);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return new OutputCounts(rows.getLong(1), rows.getLong(2), rows.getLong(3));
            }
        }
    }

    private record OutputCounts(long windows, long alerts, long deadLetters) {}
}
