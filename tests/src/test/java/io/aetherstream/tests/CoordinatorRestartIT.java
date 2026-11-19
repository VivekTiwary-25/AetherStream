package io.aetherstream.tests;

import static io.aetherstream.tests.WorkerCrashRecoveryIT.await;
import static io.aetherstream.tests.WorkerCrashRecoveryIT.earliestDemoPlan;
import static io.aetherstream.tests.WorkerCrashRecoveryIT.post;
import static io.aetherstream.tests.WorkerCrashRecoveryIT.repository;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import io.aetherstream.common.Alert;
import io.aetherstream.common.DeadLetter;
import io.aetherstream.common.Json;
import io.aetherstream.common.protocol.StartExecutionRequest;
import io.aetherstream.engine.coordinator.CheckpointRecord;
import io.aetherstream.engine.coordinator.KafkaOffsetsLookup;
import io.aetherstream.reference.EngineAlertRow;
import io.aetherstream.reference.EngineWindowRow;
import io.aetherstream.reference.KafkaRangeReader;
import io.aetherstream.reference.ReferenceExecutor;
import io.aetherstream.reference.ReferenceResult;
import io.aetherstream.reference.ResultComparator;
import io.aetherstream.tests.support.EngineCluster;
import io.aetherstream.tests.support.IntegrationInfrastructure;
import io.aetherstream.tests.support.WorkloadGenerator;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class CoordinatorRestartIT {
    private static final TypeReference<Map<String, Double>> VALUES = new TypeReference<>() {};

    @Test
    void coordinatorRestartRestoresMetadataAndExecutionFromLatestCompletedCheckpoint() throws Exception {
        IntegrationInfrastructure.truncateAll();
        var workload = WorkloadGenerator.generate();
        int split = workload.size() / 2;
        IntegrationInfrastructure.produce(workload.subList(0, split));
        var offsets = new KafkaOffsetsLookup(IntegrationInfrastructure.bootstrap());
        var beginning = offsets.offsets(IntegrationInfrastructure.TOPIC, 6, "earliest");
        var prefixEnd = offsets.offsets(IntegrationInfrastructure.TOPIC, 6, "latest");
        var repository = repository();
        var plan = earliestDemoPlan();
        String executionId = "coordinator-restart-" + UUID.randomUUID();
        try (var cluster = new EngineCluster(3)) {
            post(cluster, "/coord/executions", new StartExecutionRequest(executionId, plan));
            await(cluster, Duration.ofSeconds(120), "checkpoint >= 2 containing the workload prefix", () -> {
                var checkpoint = repository.latestCompleted(executionId).orElseThrow();
                return checkpoint.checkpointId() >= 2 && prefixEnd.equals(checkpoint.sourceOffsets());
            });
            var before = repository.findExecution(executionId).orElseThrow();
            var identities = IntStream.range(0, 3).mapToObj(i -> cluster.worker(i).workerId()).toList();
            String address = cluster.coordinatorUrl();

            // The suffix is withheld so restored window/streak state must be used for new input after restart.
            cluster.restartCoordinator();
            assertThat(cluster.coordinatorUrl()).isEqualTo(address);
            // The old HTTP server is gone; no new checkpoint can complete during the restart grace period.
            var latestAtBoot = repository.latestCompleted(executionId).orElseThrow();
            assertThat(latestAtBoot.checkpointId()).isGreaterThanOrEqualTo(2);
            assertThat(latestAtBoot.state()).isEqualTo(CheckpointRecord.COMPLETED);
            assertThat(latestAtBoot.manifestKey()).isNotBlank();
            assertThat(latestAtBoot.sourceOffsets()).isEqualTo(prefixEnd);
            await(cluster, Duration.ofSeconds(30), "RUNNING after coordinator restart and worker re-registration", () -> {
                var execution = repository.findExecution(executionId).orElseThrow();
                return execution.state().equals("RUNNING") && execution.generation() > before.generation()
                        && IntStream.range(0, 3).allMatch(i -> {
                            var status = cluster.worker(i).status();
                            return executionId.equals(status.executionId()) && status.state().equals("RUNNING")
                                    && status.generation() == execution.generation();
                        });
            });
            var recovered = repository.findExecution(executionId).orElseThrow();
            assertThat(recovered.generation()).isEqualTo(before.generation() + 1);
            assertThat(recovered.restoredFromCheckpoint()).isEqualTo(latestAtBoot.checkpointId());
            assertThat(recovered.pipelineId()).isEqualTo(before.pipelineId());
            assertThat(recovered.pipelineVersion()).isEqualTo(before.pipelineVersion());
            assertThat(recovered.plan()).isEqualTo(plan);
            assertThat(recovered.createdAtMs()).isEqualTo(before.createdAtMs());
            var partitions = new ArrayList<Integer>();
            for (int i = 0; i < 3; i++) {
                assertThat(cluster.worker(i).workerId()).isEqualTo(identities.get(i));
                assertThat(cluster.worker(i).status().partitions()).hasSize(2);
                partitions.addAll(cluster.worker(i).status().partitions());
            }
            assertThat(partitions).doesNotHaveDuplicates().containsExactlyInAnyOrder(0, 1, 2, 3, 4, 5);

            IntegrationInfrastructure.produce(workload.subList(split, workload.size()));
            var end = offsets.offsets(IntegrationInfrastructure.TOPIC, 6, "latest");
            await(cluster, Duration.ofSeconds(120), "full consumption checkpoint after coordinator recovery", () -> {
                var checkpoint = repository.latestCompleted(executionId).orElseThrow();
                return checkpoint.generation() == recovered.generation() && end.equals(checkpoint.sourceOffsets());
            });
            var completed = repository.latestCompleted(executionId).orElseThrow();
            var initial = repository.findCheckpoint(executionId, 0).orElseThrow();
            assertThat(initial.sourceOffsets()).isEqualTo(beginning);
            var reference = new ReferenceExecutor().run(plan, new KafkaRangeReader().read(
                    IntegrationInfrastructure.bootstrap(), plan.topic(), initial.sourceOffsets(), completed.sourceOffsets()));
            assertThat(reference.recordsReplayed()).isEqualTo(workload.size());
            assertMatchesReference(executionId, completed, reference);
            assertThat(repository.findExecution(executionId).orElseThrow().state()).isEqualTo("RUNNING");
            cluster.checkHealthy();
        }
    }

    /** Shared end-to-end output comparison for the restart, rescale and savepoint scenarios. */
    static void assertMatchesReference(String executionId, CheckpointRecord completed, ReferenceResult reference)
            throws Exception {
        long started = System.nanoTime();
        var windows = new ArrayList<EngineWindowRow>();
        var alerts = new HashMap<EngineAlertRow, Alert>();
        var deadLetters = new HashMap<DeadLetter, Long>();
        var windowEnds = new HashMap<WindowIdentity, Long>();
        reference.windowsBySink().forEach((sink, results) -> results.forEach(window ->
                windowEnds.put(new WindowIdentity(sink, window.key(), window.windowStart()), window.windowEnd())));
        try (var connection = DriverManager.getConnection(IntegrationInfrastructure.jdbcUrl(), "aether", "aether")) {
            try (var statement = connection.prepareStatement("""
                    SELECT sink_id, key, window_start, window_end, "values", event_count
                    FROM window_results WHERE execution_id = ?
                    """)) {
                statement.setString(1, executionId);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) windows.add(new EngineWindowRow(rows.getString(1), rows.getString(2),
                            rows.getLong(3), rows.getLong(4), Json.MAPPER.readValue(rows.getString(5), VALUES), rows.getLong(6)));
                }
            }
            try (var statement = connection.prepareStatement("""
                    SELECT sink_id, alert_id, key, rule_id, message, event_time FROM alerts WHERE execution_id = ?
                    """)) {
                statement.setString(1, executionId);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) alerts.put(new EngineAlertRow(rows.getString(1), rows.getString(2)),
                            new Alert(rows.getString(2), rows.getString(3), rows.getString(4), rows.getString(5), rows.getLong(6)));
                }
            }
            try (var statement = connection.prepareStatement("""
                    SELECT event_id, kind, reason, partition, "offset", event_time, raw
                    FROM dead_letters WHERE execution_id = ?
                    """)) {
                statement.setString(1, executionId);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) deadLetters.merge(new DeadLetter(rows.getString(1), rows.getString(2),
                            rows.getString(3), rows.getInt(4), rows.getLong(5), rows.getObject(6, Long.class),
                            rows.getString(7)), 1L, Long::sum);
                }
            }
        }
        var comparison = new ResultComparator().compare(reference, windows, List.copyOf(alerts.keySet()),
                completed.checkpointId(), Duration.ofNanos(System.nanoTime() - started).toMillis());
        System.out.println(Json.MAPPER.writeValueAsString(comparison));
        assertThat(comparison.match()).as("CompareResult: %s", comparison).isTrue();
        assertThat(comparison.matched()).isEqualTo(comparison.referenceResults()).isGreaterThan(100);
        assertThat(comparison.engineResults()).isEqualTo(comparison.referenceResults());
        for (var row : windows) {
            assertThat(row.windowEnd()).as("window end for %s", row)
                    .isEqualTo(windowEnds.get(new WindowIdentity(row.sinkId(), row.key(), row.windowStart())));
        }
        var expectedAlerts = new HashMap<EngineAlertRow, Alert>();
        reference.alertsBySink().forEach((sink, results) -> results.forEach(alert ->
                expectedAlerts.put(new EngineAlertRow(sink, alert.alertId()), alert)));
        assertThat(alerts).as("complete alert values").isEqualTo(expectedAlerts);
        var expectedDeadLetters = new HashMap<DeadLetter, Long>();
        reference.deadLetters().forEach(letter -> expectedDeadLetters.merge(letter, 1L, Long::sum));
        assertThat(deadLetters).as("complete dead-letter multiset").isEqualTo(expectedDeadLetters);
    }

    private record WindowIdentity(String sinkId, String key, long start) {}
}
