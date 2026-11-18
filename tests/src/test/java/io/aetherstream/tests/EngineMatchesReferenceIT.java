package io.aetherstream.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aetherstream.common.Alert;
import io.aetherstream.common.Json;
import io.aetherstream.common.api.CompareResult;
import io.aetherstream.common.api.CompareResult.Mismatch;
import io.aetherstream.common.pipeline.ExecutionPlan;
import io.aetherstream.common.pipeline.Node;
import io.aetherstream.common.pipeline.PipelineDefinition;
import io.aetherstream.common.protocol.StartExecutionRequest;
import io.aetherstream.compiler.PipelineCompiler;
import io.aetherstream.compiler.ops.ListCollector;
import io.aetherstream.compiler.ops.PartitionPipeline;
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
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
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
            for (int i = 0; i < 3; i++) {
                assertThat(cluster.worker(i).status().partitions()).hasSize(2);
            }

            long started = System.nanoTime();
            Results engine = loadResults(executionId);
            Replay reference = replay(plan, initial.sourceOffsets(), completed.sourceOffsets());
            CompareResult comparison = compare(completed.checkpointId(), plan, reference, engine,
                    Duration.ofNanos(System.nanoTime() - started).toMillis());
            System.out.println(Json.MAPPER.writeValueAsString(comparison));
            assertThat(comparison.match()).as("CompareResult: %s", comparison).isTrue();
            assertThat(comparison.matched()).isEqualTo(comparison.referenceResults()).isGreaterThan(100);
            assertThat(comparison.recordsReplayed()).isEqualTo(workload.size());
            assertThat(engine.alerts().values()).anySatisfy(alert -> {
                assertThat(alert.key()).isEqualTo("truck-007");
                assertThat(alert.message()).isEqualTo("truck-007 has not moved for 10 min");
            });
            assertDeadLetters(executionId);
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

    /** Fallback reference executor: only the declared Kafka range, one semantics instance per partition, no idle flush. */
    private static Replay replay(ExecutionPlan plan, Map<Integer, Long> start, Map<Integer, Long> end) {
        Properties config = new Properties();
        config.put("bootstrap.servers", IntegrationInfrastructure.bootstrap());
        config.put("enable.auto.commit", "false");
        config.put("auto.offset.reset", "none");
        config.put("key.deserializer", StringDeserializer.class.getName());
        config.put("value.deserializer", StringDeserializer.class.getName());
        var pipelines = new LinkedHashMap<Integer, PartitionPipeline>();
        var collectors = new LinkedHashMap<Integer, ListCollector>();
        var keyPartitions = new HashMap<String, Integer>();
        var next = new HashMap<>(start);
        List<TopicPartition> partitions = new ArrayList<>();
        for (int p = 0; p < plan.partitions(); p++) {
            partitions.add(new TopicPartition(plan.topic(), p));
            pipelines.put(p, new PartitionPipeline(plan, p));
            collectors.put(p, new ListCollector());
        }
        long records = 0;
        try (var consumer = new KafkaConsumer<String, String>(config)) {
            consumer.assign(partitions);
            for (TopicPartition partition : partitions) consumer.seek(partition, start.get(partition.partition()));
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (!next.equals(end)) {
                if (System.nanoTime() >= deadline) {
                    throw new AssertionError("Reference Kafka replay timed out: next=" + next + ", end=" + end);
                }
                for (var record : consumer.poll(Duration.ofMillis(250))) {
                    int p = record.partition();
                    if (record.offset() >= end.get(p)) continue;
                    assertThat(record.offset()).as("reference offset in partition %s", p).isEqualTo(next.get(p));
                    pipelines.get(p).process(record.offset(), record.value(), collectors.get(p));
                    if (record.key() != null) {
                        Integer previous = keyPartitions.putIfAbsent(record.key(), p);
                        assertThat(previous == null || previous == p).as("stable partition for %s", record.key()).isTrue();
                    }
                    next.put(p, record.offset() + 1);
                    records++;
                }
                for (TopicPartition partition : partitions) {
                    if (next.get(partition.partition()).equals(end.get(partition.partition()))) {
                        consumer.pause(List.of(partition));
                    }
                }
            }
        }
        var windows = new LinkedHashMap<WindowKey, WindowValue>();
        var alerts = new LinkedHashMap<AlertKey, Alert>();
        for (ListCollector collector : collectors.values()) {
            for (var output : collector.outputs()) {
                if (output instanceof ListCollector.WindowOutput window) {
                    var row = window.result();
                    windows.put(new WindowKey(window.sinkNodeId(), row.key(), row.windowStart()),
                            new WindowValue(row.windowEnd(), row.values(), row.eventCount()));
                } else if (output instanceof ListCollector.AlertOutput alert) {
                    alerts.put(new AlertKey(alert.sinkNodeId(), alert.alert().alertId()), alert.alert());
                }
            }
        }
        return new Replay(new Results(windows, alerts), pipelines, keyPartitions, records);
    }

    private static Results loadResults(String executionId) throws Exception {
        var windows = new LinkedHashMap<WindowKey, WindowValue>();
        var alerts = new LinkedHashMap<AlertKey, Alert>();
        try (var connection = DriverManager.getConnection(IntegrationInfrastructure.jdbcUrl(), "aether", "aether")) {
            try (var statement = connection.prepareStatement("""
                    SELECT sink_id, key, window_start, window_end, "values", event_count
                    FROM window_results WHERE execution_id = ? ORDER BY sink_id, key, window_start
                    """)) {
                statement.setString(1, executionId);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        windows.put(new WindowKey(rows.getString(1), rows.getString(2), rows.getLong(3)),
                                new WindowValue(rows.getLong(4), Json.MAPPER.readValue(rows.getString(5), VALUES), rows.getLong(6)));
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
                        alerts.put(new AlertKey(rows.getString(1), rows.getString(2)),
                                new Alert(rows.getString(2), rows.getString(3), rows.getString(4), rows.getString(5), rows.getLong(6)));
                    }
                }
            }
        }
        return new Results(windows, alerts);
    }

    private static CompareResult compare(long checkpointId, ExecutionPlan plan, Replay replay, Results engine, long durationMs) {
        Results reference = replay.results();
        List<Mismatch> mismatches = new ArrayList<>();
        long matched = 0;
        for (var entry : reference.windows().entrySet()) {
            WindowKey key = entry.getKey();
            WindowValue expected = entry.getValue();
            WindowValue actual = engine.windows().get(key);
            if (actual == null) {
                mismatches.add(new Mismatch("MISSING", key.sinkId(), key.key(), key.windowStart(), "window missing"));
            } else if (!equalWindow(expected, actual)) {
                mismatches.add(new Mismatch("DIFFERENT", key.sinkId(), key.key(), key.windowStart(),
                        "expected " + expected + ", actual " + actual));
            } else matched++;
        }
        Map<String, String> sinkWindows = new HashMap<>();
        for (var operator : plan.operators()) {
            if (operator.type().equals("sink") && operator.config().path("kind").asText().equals("postgres.windows")) {
                sinkWindows.put(operator.id(), operator.inputs().getFirst());
            }
        }
        for (var entry : engine.windows().entrySet()) {
            WindowKey key = entry.getKey();
            if (reference.windows().containsKey(key)) continue;
            Integer partition = replay.keyPartitions().get(key.key());
            String windowNode = sinkWindows.get(key.sinkId());
            // Contract 04 step 3: rows beyond the reference watermark are not EXTRA.
            if (partition != null && windowNode != null
                    && entry.getValue().windowEnd() <= replay.pipelines().get(partition).watermarks().get(windowNode)) {
                mismatches.add(new Mismatch("EXTRA", key.sinkId(), key.key(), key.windowStart(), "unexpected closed window"));
            }
        }
        for (var entry : reference.alerts().entrySet()) {
            AlertKey key = entry.getKey();
            Alert expected = entry.getValue();
            Alert actual = engine.alerts().get(key);
            if (actual == null || !expected.equals(actual)) {
                mismatches.add(new Mismatch(actual == null ? "MISSING" : "DIFFERENT", key.sinkId(), expected.key(),
                        expected.eventTime(), "alert " + key.alertId() + ": expected " + expected + ", actual " + actual));
            } else matched++;
        }
        for (var entry : engine.alerts().entrySet()) {
            if (!reference.alerts().containsKey(entry.getKey())) {
                Alert alert = entry.getValue();
                mismatches.add(new Mismatch("EXTRA", entry.getKey().sinkId(), alert.key(), alert.eventTime(),
                        "unexpected alert " + alert.alertId()));
            }
        }
        return new CompareResult(checkpointId, reference.windows().size() + reference.alerts().size(),
                engine.windows().size() + engine.alerts().size(), matched,
                List.copyOf(mismatches.subList(0, Math.min(50, mismatches.size()))), replay.records(), durationMs, mismatches.isEmpty());
    }

    private static boolean equalWindow(WindowValue expected, WindowValue actual) {
        if (expected.windowEnd() != actual.windowEnd() || expected.eventCount() != actual.eventCount()
                || !expected.values().keySet().equals(actual.values().keySet())) return false;
        for (var value : expected.values().entrySet()) {
            if (!(Math.abs(value.getValue() - actual.values().get(value.getKey())) <= 1e-6)) return false;
        }
        return true;
    }

    private static void assertDeadLetters(String executionId) throws Exception {
        Map<String, Long> counts = new HashMap<>();
        try (var connection = DriverManager.getConnection(IntegrationInfrastructure.jdbcUrl(), "aether", "aether");
             var statement = connection.prepareStatement(
                     "SELECT kind, count(*) FROM dead_letters WHERE execution_id = ? GROUP BY kind")) {
            statement.setString(1, executionId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) counts.put(rows.getString(1), rows.getLong(2));
            }
        }
        assertThat(counts.getOrDefault("REJECTED", 0L)).isGreaterThanOrEqualTo(5);
        assertThat(counts.getOrDefault("LATE", 0L)).isGreaterThanOrEqualTo(1);
    }

    private record WindowKey(String sinkId, String key, long windowStart) {}
    private record WindowValue(long windowEnd, Map<String, Double> values, long eventCount) {}
    private record AlertKey(String sinkId, String alertId) {}
    private record Results(Map<WindowKey, WindowValue> windows, Map<AlertKey, Alert> alerts) {}
    private record Replay(Results results, Map<Integer, PartitionPipeline> pipelines,
                          Map<String, Integer> keyPartitions, long records) {}
}
