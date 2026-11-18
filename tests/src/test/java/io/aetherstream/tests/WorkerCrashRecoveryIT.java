package io.aetherstream.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aetherstream.common.Json;
import io.aetherstream.common.Alert;
import io.aetherstream.common.api.CompareResult;
import io.aetherstream.common.pipeline.ExecutionPlan;
import io.aetherstream.common.pipeline.Node;
import io.aetherstream.common.pipeline.PipelineDefinition;
import io.aetherstream.common.protocol.Command;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

@Tag("integration")
class WorkerCrashRecoveryIT {
    private static final int PARTITIONS = 6;
    private static final TypeReference<Map<String, Double>> VALUES = new TypeReference<>() {};

    @Test
    void workerCrashRestoresCompletedCheckpointAndMatchesReference() throws Exception {
        IntegrationInfrastructure.truncateAll();
        var workload = extendedWorkload();
        IntegrationInfrastructure.produce(workload);
        var offsets = new KafkaOffsetsLookup(IntegrationInfrastructure.bootstrap());
        var end = offsets.offsets(IntegrationInfrastructure.TOPIC, PARTITIONS, "latest");
        var beginning = offsets.offsets(IntegrationInfrastructure.TOPIC, PARTITIONS, "earliest");
        var repository = repository();
        var plan = earliestDemoPlan();
        String executionId = "crash-recovery-" + UUID.randomUUID();
        try (var cluster = new EngineCluster(3)) {
            // Keep a genuine backlog through checkpoint 2 instead of crashing an already idle source.
            post(cluster, "/coord/chaos/sink-delay", Map.of("delayMs", 1));
            await(cluster, Duration.ofSeconds(15), "sink delay delivered to every worker", () ->
                    IntStream.range(0, 3).allMatch(i -> cluster.worker(i).sinkDelayMs() == 1));
            post(cluster, "/coord/executions", new StartExecutionRequest(executionId, plan));
            await(cluster, Duration.ofSeconds(120), "checkpoint >= 2 COMPLETED", () ->
                    repository.latestCompleted(executionId).orElseThrow().checkpointId() >= 2);
            var completed = repository.latestCompleted(executionId).orElseThrow();
            assertThat(completed.sourceOffsets()).as("crash must occur mid-stream").isNotEqualTo(end);
            int victim = IntStream.range(0, 3).boxed().max(Comparator
                    .comparingInt((Integer i) -> cluster.worker(i).status().partitions().size())
                    .thenComparingLong(i -> cluster.worker(i).status().partitions().stream()
                            .mapToLong(p -> end.get(p) - completed.sourceOffsets().get(p)).sum()))
                    .orElseThrow();
            var deadWorker = cluster.worker(victim);
            assertThat(deadWorker.status().partitions()).hasSize(2);
            assertThat(deadWorker.runtime().sinkQueueSize()).as("victim has uncommitted work").isPositive();
            deadWorker.handle(new Command.SetSinkDelay(60_000));
            // A blocked barrier prevents a later checkpoint from completing between this read and the crash.
            long pending = post(cluster, "/coord/executions/" + executionId + "/checkpoint", null)
                    .path("checkpointId").asLong();
            assertThat(repository.findCheckpoint(executionId, pending).orElseThrow().state()).isEqualTo("PENDING");
            var beforeCrash = repository.latestCompleted(executionId).orElseThrow();
            long generation = repository.findExecution(executionId).orElseThrow().generation();
            long crashedAt = System.nanoTime();
            cluster.crash(victim);
            var replacement = cluster.addWorker();
            assertThat(replacement.workerId()).isNotEqualTo(deadWorker.workerId());
            await(cluster, remaining(crashedAt), "RECOVERING after worker death", () ->
                    repository.findExecution(executionId).orElseThrow().state().equals("RECOVERING"));
            await(cluster, remaining(crashedAt), "RUNNING with recovered generation", () -> {
                var execution = repository.findExecution(executionId).orElseThrow();
                return execution.state().equals("RUNNING") && execution.generation() > generation;
            });
            long recoveryMs = Duration.ofNanos(System.nanoTime() - crashedAt).toMillis();
            assertThat(recoveryMs).isLessThan(15_000);
            var recovered = repository.findExecution(executionId).orElseThrow();
            assertThat(recovered.generation()).isEqualTo(generation + 1);
            assertThat(recovered.restoredFromCheckpoint()).isEqualTo(beforeCrash.checkpointId());
            assertThat(deadWorker.runtime().isStopped()).isTrue();
            assertRecoveryAudit(executionId, deadWorker.workerId(), beforeCrash.checkpointId(), recovered.generation());
            System.out.println("Recovery duration: " + recoveryMs + " ms");

            post(cluster, "/coord/chaos/sink-delay", Map.of("delayMs", 0));
            await(cluster, Duration.ofSeconds(120), "full consumption in a recovered checkpoint", () -> {
                var checkpoint = repository.latestCompleted(executionId).orElseThrow();
                return checkpoint.generation() == recovered.generation() && end.equals(checkpoint.sourceOffsets());
            });
            CheckpointRecord last = repository.latestCompleted(executionId).orElseThrow();
            CheckpointRecord initial = repository.findCheckpoint(executionId, 0).orElseThrow();
            assertThat(initial.sourceOffsets()).isEqualTo(beginning);
            long started = System.nanoTime();
            var records = new KafkaRangeReader().read(IntegrationInfrastructure.bootstrap(), plan.topic(),
                    initial.sourceOffsets(), last.sourceOffsets());
            ReferenceResult reference = new ReferenceExecutor().run(plan, records);
            CompareResult comparison = compare(executionId, last.checkpointId(), reference, started);
            System.out.println(Json.MAPPER.writeValueAsString(comparison));
            assertThat(comparison.match()).as("CompareResult: %s", comparison).isTrue();
            assertThat(comparison.matched()).isEqualTo(comparison.referenceResults()).isGreaterThan(100);
            assertThat(comparison.engineResults()).isEqualTo(comparison.referenceResults());
            assertThat(comparison.recordsReplayed()).isEqualTo(workload.size());
            assertThat(reference.alertsBySink().values().stream().flatMap(List::stream))
                    .anySatisfy(alert -> {
                        assertThat(alert.key()).isEqualTo("truck-007");
                        assertThat(alert.message()).isEqualTo("truck-007 has not moved for 10 min");
                    });
            assertDeadLetters(executionId, reference);
            cluster.checkHealthy();
        }
    }

    private static Duration remaining(long started) {
        long nanos = Duration.ofSeconds(15).toNanos() - (System.nanoTime() - started);
        assertThat(nanos).as("recovery deadline").isPositive();
        return Duration.ofNanos(nanos);
    }

    /** Four disjoint event-id/time epochs of the seed-42 workload; duplicates within each epoch stay duplicates. */
    private static List<ProducerRecord<String, String>> extendedWorkload() throws Exception {
        var base = WorkloadGenerator.generate();
        var records = new ArrayList<ProducerRecord<String, String>>(base.size() * 4);
        for (int epoch = 0; epoch < 4; epoch++) {
            for (var record : base) {
                var event = (ObjectNode) Json.MAPPER.readTree(record.value());
                event.put("eventId", event.path("eventId").asText() + "-epoch-" + epoch);
                event.put("eventTime", event.path("eventTime").asLong() + Duration.ofMinutes(40).toMillis() * epoch);
                records.add(new ProducerRecord<>(record.topic(), record.key(), Json.MAPPER.writeValueAsString(event)));
            }
        }
        return records;
    }

    static ExecutionPlan earliestDemoPlan() {
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

    static PostgresExecutionRepository repository() {
        var dataSource = new PGSimpleDataSource();
        dataSource.setURL(IntegrationInfrastructure.jdbcUrl());
        dataSource.setUser("aether");
        dataSource.setPassword("aether");
        return new PostgresExecutionRepository(dataSource);
    }

    static JsonNode post(EngineCluster cluster, String path, Object body) throws Exception {
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

    @FunctionalInterface
    interface CheckedCondition { boolean get() throws Exception; }

    static void await(EngineCluster cluster, Duration timeout, String description, CheckedCondition condition)
            throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            cluster.checkHealthy();
            if (condition.get()) return;
            Thread.sleep(25);
        }
        throw new AssertionError("Timed out waiting for " + description);
    }

    private static void assertRecoveryAudit(String executionId, String workerId, long checkpoint, long generation)
            throws Exception {
        var actions = new ArrayList<String>();
        try (var connection = DriverManager.getConnection(IntegrationInfrastructure.jdbcUrl(), "aether", "aether");
             var statement = connection.prepareStatement(
                     "SELECT action, detail FROM audit_log WHERE detail->>'executionId' = ? ORDER BY id")) {
            statement.setString(1, executionId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String action = rows.getString(1);
                    JsonNode detail = Json.MAPPER.readTree(rows.getString(2));
                    actions.add(action);
                    if (action.equals("worker.dead")) assertThat(detail.path("workerId").asText()).isEqualTo(workerId);
                    if (action.equals("execution.recovering") || action.equals("execution.recovered")) {
                        assertThat(detail.path("checkpointId").asLong()).isEqualTo(checkpoint);
                        assertThat(detail.path("generation").asLong()).isEqualTo(generation);
                    }
                    if (action.equals("execution.recovered")) {
                        assertThat(detail.path("recoveryDurationMs").asLong()).isBetween(0L, 15_000L);
                    }
                }
            }
        }
        assertThat(actions).containsSubsequence("worker.dead", "execution.recovering", "execution.recovered");
    }

    private record WindowIdentity(String sinkId, String key, long start) {}
    private record AlertIdentity(String sinkId, String alertId) {}

    private static CompareResult compare(String executionId, long checkpoint, ReferenceResult reference, long started)
            throws Exception {
        var windows = new ArrayList<EngineWindowRow>();
        var alerts = new ArrayList<EngineAlertRow>();
        var windowEnds = new HashMap<WindowIdentity, Long>();
        reference.windowsBySink().forEach((sink, results) -> results.forEach(window ->
                windowEnds.put(new WindowIdentity(sink, window.key(), window.windowStart()), window.windowEnd())));
        var expectedAlerts = new HashMap<AlertIdentity, Alert>();
        reference.alertsBySink().forEach((sink, results) -> results.forEach(alert ->
                expectedAlerts.put(new AlertIdentity(sink, alert.alertId()), alert)));
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
                    while (rows.next()) {
                        String sink = rows.getString(1);
                        String alertId = rows.getString(2);
                        // ResultComparator checks identities; retain the existing IT's full alert-value check too.
                        var expected = expectedAlerts.get(new AlertIdentity(sink, alertId));
                        assertThat(expected).as("unexpected alert %s/%s", sink, alertId).isNotNull();
                        assertThat(rows.getString(3)).isEqualTo(expected.key());
                        assertThat(rows.getString(4)).isEqualTo(expected.ruleId());
                        assertThat(rows.getString(5)).isEqualTo(expected.message());
                        assertThat(rows.getLong(6)).isEqualTo(expected.eventTime());
                        alerts.add(new EngineAlertRow(sink, alertId));
                    }
                }
            }
        }
        for (var row : windows) {
            Long expectedEnd = windowEnds.get(new WindowIdentity(row.sinkId(), row.key(), row.windowStart()));
            if (expectedEnd != null) assertThat(row.windowEnd()).isEqualTo(expectedEnd);
        }
        return new ResultComparator().compare(reference, windows, alerts, checkpoint,
                Duration.ofNanos(System.nanoTime() - started).toMillis());
    }

    private static void assertDeadLetters(String executionId, ReferenceResult reference) throws Exception {
        try (var connection = DriverManager.getConnection(IntegrationInfrastructure.jdbcUrl(), "aether", "aether");
             var statement = connection.prepareStatement(
                     "SELECT kind, count(*) FROM dead_letters WHERE execution_id = ? GROUP BY kind")) {
            statement.setString(1, executionId);
            long total = 0;
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String kind = rows.getString(1);
                    long expected = reference.deadLetters().stream().filter(letter -> letter.kind().equals(kind)).count();
                    assertThat(rows.getLong(2)).as("%s dead letters after replay", kind).isEqualTo(expected);
                    total += rows.getLong(2);
                }
            }
            assertThat(total).isEqualTo(reference.deadLetters().size());
        }
    }
}
