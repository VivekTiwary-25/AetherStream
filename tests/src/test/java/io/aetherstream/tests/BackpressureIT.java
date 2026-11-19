package io.aetherstream.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aetherstream.common.Alert;
import io.aetherstream.common.Json;
import io.aetherstream.common.api.CompareResult;
import io.aetherstream.common.pipeline.ExecutionPlan;
import io.aetherstream.common.pipeline.Node;
import io.aetherstream.common.pipeline.PipelineDefinition;
import io.aetherstream.common.protocol.StartExecutionRequest;
import io.aetherstream.compiler.PipelineCompiler;
import io.aetherstream.engine.coordinator.CheckpointRecord;
import io.aetherstream.engine.coordinator.KafkaOffsetsLookup;
import io.aetherstream.engine.coordinator.PostgresExecutionRepository;
import io.aetherstream.reference.EngineAlertRow;
import io.aetherstream.reference.EngineWindowRow;
import io.aetherstream.reference.KafkaRangeReader;
import io.aetherstream.reference.ReferenceExecutor;
import io.aetherstream.reference.ReferenceResult;
import io.aetherstream.reference.ResultComparator;
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
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.postgresql.ds.PGSimpleDataSource;

@Tag("integration")
class BackpressureIT {
    private static final int PARTITIONS = 6;
    private static final long SINK_DELAY_MS = 2;
    private static final Duration DEADLINE = Duration.ofSeconds(120);
    private static final TypeReference<Map<String, Double>> VALUES = new TypeReference<>() {};

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void boundedQueuesBuildLagThenDrainWithoutLosingResults() throws Exception {
        IntegrationInfrastructure.truncateAll();
        var workload = WorkloadGenerator.generate();
        var offsets = new KafkaOffsetsLookup(IntegrationInfrastructure.bootstrap());
        var repository = repository();
        String executionId = "backpressure-" + UUID.randomUUID();
        AtomicLong peakLag = new AtomicLong();
        CompletableFuture<Void> producing = null;

        try (var cluster = new EngineCluster(3)) {
            post(cluster, "/coord/chaos/sink-delay", Map.of("delayMs", SINK_DELAY_MS));
            WorkerCrashRecoveryIT.await(cluster, DEADLINE, "sink delay delivered to every worker", () ->
                    java.util.stream.IntStream.range(0, 3)
                            .allMatch(i -> cluster.worker(i).sinkDelayMs() == SINK_DELAY_MS));

            var plan = latestDemoPlan();
            post(cluster, "/coord/executions", new StartExecutionRequest(executionId, plan));
            WorkerCrashRecoveryIT.await(cluster, DEADLINE, "workers running", () -> {
                assertQueuesBounded(cluster);
                return java.util.stream.IntStream.range(0, 3)
                        .allMatch(i -> cluster.worker(i).status().state().equals("RUNNING"));
            });
            CheckpointRecord initial = repository.findCheckpoint(executionId, 0).orElseThrow();
            Map<Integer, Long> beforeProduction = offsets.offsets(plan.topic(), PARTITIONS, "latest");
            assertThat(initial.sourceOffsets()).isEqualTo(beforeProduction);
            assertThat(totalLag(cluster)).isZero();

            producing = CompletableFuture.runAsync(() -> IntegrationInfrastructure.produce(workload));
            WorkerCrashRecoveryIT.await(cluster, DEADLINE, "consumer lag growing under slow sink", () -> {
                assertQueuesBounded(cluster);
                peakLag.accumulateAndGet(totalLag(cluster), Math::max);
                return peakLag.get() > 0;
            });
            producing.get(DEADLINE.toSeconds(), TimeUnit.SECONDS);

            awaitCheckpoint(cluster, repository, executionId, 1, peakLag);
            awaitCheckpoint(cluster, repository, executionId, 2, peakLag);
            assertThat(peakLag.get()).as("source consumer lag under sink backpressure").isPositive();
            assertThat(java.util.stream.IntStream.range(0, 3)
                    .allMatch(i -> cluster.worker(i).sinkDelayMs() == SINK_DELAY_MS)).isTrue();

            post(cluster, "/coord/chaos/sink-delay", Map.of("delayMs", 0));
            WorkerCrashRecoveryIT.await(cluster, DEADLINE, "sink delay removed", () ->
                    java.util.stream.IntStream.range(0, 3).allMatch(i -> cluster.worker(i).sinkDelayMs() == 0));
            Map<Integer, Long> endOffsets = offsets.offsets(plan.topic(), PARTITIONS, "latest");
            CheckpointRecord completed = awaitConsumedCheckpoint(cluster, repository, executionId, endOffsets, peakLag);
            assertThat(completed.checkpointId()).isGreaterThanOrEqualTo(2);
            assertQueuesBounded(cluster);
            assertThat(totalLag(cluster)).as("consumer lag after drain").isZero();
            for (int i = 0; i < 3; i++) {
                var status = cluster.worker(i).status();
                assertThat(status.sourceQueue().size()).isZero();
                assertThat(status.sinkQueue().size()).isZero();
            }

            long started = System.nanoTime();
            ReferenceResult reference = new ReferenceExecutor().run(plan, new KafkaRangeReader().read(
                    IntegrationInfrastructure.bootstrap(), plan.topic(), initial.sourceOffsets(), completed.sourceOffsets()));
            Results engine = loadResults(executionId);
            CompareResult comparison = new ResultComparator().compare(reference, engine.windows(),
                    List.copyOf(engine.alerts().keySet()), completed.checkpointId(),
                    Duration.ofNanos(System.nanoTime() - started).toMillis());
            assertThat(comparison.match()).as("CompareResult: %s", comparison).isTrue();
            assertThat(comparison.engineResults()).isEqualTo(comparison.referenceResults());
            assertThat(comparison.recordsReplayed()).isEqualTo(workload.size());
            assertDeadLettersMatchReference(executionId, reference);
            cluster.checkHealthy();
        } finally {
            if (producing != null) producing.cancel(true);
        }
    }

    private static void awaitCheckpoint(EngineCluster cluster, PostgresExecutionRepository repository,
                                        String executionId, long checkpointId, AtomicLong peakLag) throws Exception {
        WorkerCrashRecoveryIT.await(cluster, DEADLINE, "checkpoint " + checkpointId + " completes during backpressure", () -> {
            assertQueuesBounded(cluster);
            peakLag.accumulateAndGet(totalLag(cluster), Math::max);
            return repository.latestCompleted(executionId).map(cp -> cp.checkpointId() >= checkpointId).orElse(false);
        });
    }

    private static CheckpointRecord awaitConsumedCheckpoint(EngineCluster cluster,
            PostgresExecutionRepository repository, String executionId, Map<Integer, Long> endOffsets,
            AtomicLong peakLag) throws Exception {
        final CheckpointRecord[] completed = new CheckpointRecord[1];
        WorkerCrashRecoveryIT.await(cluster, DEADLINE, "checkpoint consuming every produced record", () -> {
            assertQueuesBounded(cluster);
            peakLag.accumulateAndGet(totalLag(cluster), Math::max);
            completed[0] = repository.latestCompleted(executionId).orElse(null);
            return completed[0] != null && completed[0].checkpointId() > 0
                    && endOffsets.equals(completed[0].sourceOffsets());
        });
        return completed[0];
    }

    private static void assertQueuesBounded(EngineCluster cluster) {
        for (int i = 0; i < 3; i++) {
            var status = cluster.worker(i).status();
            assertThat(status.sourceQueue().size()).as("worker %s source queue", i)
                    .isBetween(0, status.sourceQueue().capacity());
            assertThat(status.sinkQueue().size()).as("worker %s sink queue", i)
                    .isBetween(0, status.sinkQueue().capacity());
        }
    }

    private static long totalLag(EngineCluster cluster) {
        long lag = 0;
        for (int i = 0; i < 3; i++) {
            lag += cluster.worker(i).status().lag().values().stream().mapToLong(Long::longValue).sum();
        }
        return lag;
    }

    private static ExecutionPlan latestDemoPlan() {
        PipelineDefinition demo = PipelineCompiler.demoPipeline();
        List<Node> nodes = demo.nodes().stream().map(node -> {
            if (!node.type().equals("source")) return node;
            ObjectNode config = ((ObjectNode) node.config()).deepCopy();
            config.put("startFrom", "latest");
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

    private static JsonNode post(EngineCluster cluster, String path, Object body) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create(cluster.coordinatorUrl() + path))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                    .POST(body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofByteArray(Json.MAPPER.writeValueAsBytes(body))).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("%s: %s", path, response.body()).isEqualTo(200);
            return Json.MAPPER.readTree(response.body());
        }
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
                                new Alert(rows.getString(2), rows.getString(3), rows.getString(4),
                                        rows.getString(5), rows.getLong(6)));
                    }
                }
            }
        }
        return new Results(windows, alerts);
    }

    private static void assertDeadLettersMatchReference(String executionId, ReferenceResult reference)
            throws Exception {
        Map<DeadLetterKey, Long> expected = new HashMap<>();
        for (var letter : reference.deadLetters()) {
            expected.merge(new DeadLetterKey(letter.eventId(), letter.kind()), 1L, Long::sum);
        }
        Map<DeadLetterKey, Long> actual = new HashMap<>();
        try (var connection = DriverManager.getConnection(IntegrationInfrastructure.jdbcUrl(), "aether", "aether");
             var statement = connection.prepareStatement(
                     "SELECT event_id, kind FROM dead_letters WHERE execution_id = ?")) {
            statement.setString(1, executionId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    actual.merge(new DeadLetterKey(rows.getString(1), rows.getString(2)), 1L, Long::sum);
                }
            }
        }
        assertThat(actual).as("dead-letter multiset (eventId, kind)").isEqualTo(expected);
    }

    private record DeadLetterKey(String eventId, String kind) {}
    private record Results(List<EngineWindowRow> windows, Map<EngineAlertRow, Alert> alerts) {}
}
