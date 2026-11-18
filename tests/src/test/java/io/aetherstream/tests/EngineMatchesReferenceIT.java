package io.aetherstream.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aetherstream.common.Alert;
import io.aetherstream.common.Json;
import io.aetherstream.common.api.CompareResult;
import io.aetherstream.common.pipeline.ExecutionPlan;
import io.aetherstream.common.pipeline.Node;
import io.aetherstream.common.pipeline.PipelineDefinition;
import io.aetherstream.common.protocol.StartExecutionRequest;
import io.aetherstream.compiler.PipelineCompiler;
import io.aetherstream.reference.EngineAlertRow;
import io.aetherstream.reference.EngineWindowRow;
import io.aetherstream.reference.KafkaRangeReader;
import io.aetherstream.reference.ReferenceExecutor;
import io.aetherstream.reference.ReferenceResult;
import io.aetherstream.reference.ResultComparator;
import io.aetherstream.engine.coordinator.CheckpointRecord;
import io.aetherstream.engine.coordinator.KafkaOffsetsLookup;
import io.aetherstream.engine.coordinator.PostgresExecutionRepository;
import io.aetherstream.tests.support.EngineCluster;
import io.aetherstream.tests.support.IntegrationInfrastructure;
import io.aetherstream.tests.support.WorkloadGenerator;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

@Tag("integration")
class EngineMatchesReferenceIT {
    private static final int PARTITIONS = 6;
    private static final TypeReference<Map<String, Double>> VALUES = new TypeReference<>() {};

    @Test
    void engineMatchesReferenceAtCompletedCheckpoint() throws Exception {
        IntegrationInfrastructure.truncateAll();
        var workload = WorkloadGenerator.generate();
        IntegrationInfrastructure.produce(workload);
        var offsets = new KafkaOffsetsLookup(IntegrationInfrastructure.bootstrap());
        Map<Integer, Long> endOffsets = offsets.offsets(IntegrationInfrastructure.TOPIC, PARTITIONS, "latest");
        Map<Integer, Long> beginningOffsets = offsets.offsets(IntegrationInfrastructure.TOPIC, PARTITIONS, "earliest");
        assertThat(endOffsets).hasSize(PARTITIONS);
        endOffsets.forEach((partition, offset) ->
                assertThat(offset).as("partition %s has records", partition).isPositive());

        ExecutionPlan plan = earliestDemoPlan();
        String executionId = "engine-reference-" + UUID.randomUUID();
        var repository = repository();
        try (var cluster = new EngineCluster(3); var http = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create(cluster.coordinatorUrl() + "/coord/executions"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(
                            Json.MAPPER.writeValueAsBytes(new StartExecutionRequest(executionId, plan))))
                    .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(Json.MAPPER.readTree(response.body()).path("id").asText()).isEqualTo(executionId);

            CheckpointRecord completed = awaitConsumedCheckpoint(cluster, repository, executionId, endOffsets);
            CheckpointRecord initial = repository.findCheckpoint(executionId, 0).orElseThrow();
            assertThat(initial.sourceOffsets()).isEqualTo(beginningOffsets);
            List<Integer> assignedPartitions = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                assertThat(cluster.worker(i).status().partitions()).hasSize(2);
                assignedPartitions.addAll(cluster.worker(i).status().partitions());
            }
            assertThat(assignedPartitions).doesNotHaveDuplicates().containsExactlyInAnyOrder(0, 1, 2, 3, 4, 5);

            long started = System.nanoTime();
            Results engine = loadResults(executionId);
            ReferenceResult reference = new ReferenceExecutor().run(plan, new KafkaRangeReader().read(
                    IntegrationInfrastructure.bootstrap(), plan.topic(), initial.sourceOffsets(), completed.sourceOffsets()));
            CompareResult comparison = new ResultComparator().compare(reference, engine.windows(),
                    List.copyOf(engine.alerts().keySet()), completed.checkpointId(),
                    Duration.ofNanos(System.nanoTime() - started).toMillis());
            System.out.println(Json.MAPPER.writeValueAsString(comparison));
            assertDeadLetters(executionId, reference);
            assertThat(comparison.match()).as("CompareResult: %s", comparison).isTrue();
            assertThat(comparison.matched()).isEqualTo(comparison.referenceResults()).isGreaterThan(100);
            assertThat(comparison.recordsReplayed()).isEqualTo(workload.size());
            assertThat(engine.alerts().values()).anySatisfy(alert -> {
                assertThat(alert.key()).isEqualTo("truck-007");
                assertThat(alert.message()).isEqualTo("truck-007 has not moved for 10 min");
            });
            cluster.checkHealthy();
        }
    }

    private static ExecutionPlan earliestDemoPlan() {
        PipelineDefinition demo = PipelineCompiler.demoPipeline();
        List<Node> nodes = demo.nodes().stream().map(node -> {
            if (!node.type().equals("source")) return node;
            ObjectNode config = ((ObjectNode) node.config()).deepCopy();
            config.put("startFrom", "earliest");
            return new Node(node.id(), node.type(), node.label(), node.position(), config);
        }).toList();
        return new PipelineCompiler().plan(new PipelineDefinition(demo.id(), demo.version(), demo.name(),
                nodes, demo.edges()), PARTITIONS);
    }

    private static PostgresExecutionRepository repository() {
        var dataSource = new PGSimpleDataSource();
        dataSource.setURL(IntegrationInfrastructure.jdbcUrl());
        dataSource.setUser("aether");
        dataSource.setPassword("aether");
        return new PostgresExecutionRepository(dataSource);
    }

    private static CheckpointRecord awaitConsumedCheckpoint(EngineCluster cluster,
            PostgresExecutionRepository repository, String executionId, Map<Integer, Long> endOffsets) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        CheckpointRecord latest = null;
        while (System.nanoTime() < deadline) {
            cluster.checkHealthy();
            latest = repository.latestCompleted(executionId).orElse(null);
            if (latest != null && latest.checkpointId() > 0 && endOffsets.equals(latest.sourceOffsets())) return latest;
            Thread.sleep(250);
        }
        throw new AssertionError("No COMPLETED checkpoint consumed " + endOffsets + "; latest=" + latest
                + "; workers=" + List.of(cluster.worker(0).status(), cluster.worker(1).status(), cluster.worker(2).status()));
    }

    private static Results loadResults(String executionId) throws Exception {
        List<EngineWindowRow> windows = new ArrayList<>();
        Map<EngineAlertRow, Alert> alerts = new LinkedHashMap<>();
        try (var connection = DriverManager.getConnection(IntegrationInfrastructure.jdbcUrl(), "aether", "aether")) {
            try (var statement = connection.prepareStatement("""
                    SELECT sink_id, key, window_start, window_end, "values", event_count
                    FROM window_results WHERE execution_id = ? ORDER BY sink_id, key, window_start
                    """)) {
                statement.setString(1, executionId);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        windows.add(new EngineWindowRow(rows.getString(1), rows.getString(2), rows.getLong(3),
                                rows.getLong(4), Json.MAPPER.readValue(rows.getString(5), VALUES), rows.getLong(6)));
                    }
                }
            }
            try (var statement = connection.prepareStatement("""
                    SELECT sink_id, alert_id, key, rule_id, message, event_time
                    FROM alerts WHERE execution_id = ? ORDER BY sink_id, alert_id
                    """)) {
                statement.setString(1, executionId);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        alerts.put(new EngineAlertRow(rows.getString(1), rows.getString(2)),
                                new Alert(rows.getString(2), rows.getString(3), rows.getString(4), rows.getString(5), rows.getLong(6)));
                    }
                }
            }
        }
        return new Results(windows, alerts);
    }

    private static void assertDeadLetters(String executionId, ReferenceResult reference) throws Exception {
        Map<DeadLetterKey, Long> expected = new HashMap<>();
        for (var letter : reference.deadLetters()) {
            expected.merge(new DeadLetterKey(letter.eventId(), letter.kind()), 1L, Long::sum);
        }
        Map<DeadLetterKey, Long> actual = new HashMap<>();
        long engineCount = 0;
        try (var connection = DriverManager.getConnection(IntegrationInfrastructure.jdbcUrl(), "aether", "aether");
             var statement = connection.prepareStatement(
                     "SELECT event_id, kind FROM dead_letters WHERE execution_id = ?")) {
            statement.setString(1, executionId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    actual.merge(new DeadLetterKey(rows.getString(1), rows.getString(2)), 1L, Long::sum);
                    engineCount++;
                }
            }
        }
        System.out.println("Dead-letter comparison: " + Json.MAPPER.writeValueAsString(Map.of(
                "referenceResults", reference.deadLetters().size(), "engineResults", engineCount,
                "equal", expected.equals(actual))));
        assertThat(actual).as("dead-letter multiset (eventId, kind)").isEqualTo(expected);
    }

    private record DeadLetterKey(String eventId, String kind) {}
    private record Results(List<EngineWindowRow> windows, Map<EngineAlertRow, Alert> alerts) {}
}
