package io.aetherstream.tests.bench;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aetherstream.common.Alert;
import io.aetherstream.common.DeadLetter;
import io.aetherstream.common.Json;
import io.aetherstream.common.WindowResult;
import io.aetherstream.common.api.CompareResult;
import io.aetherstream.common.pipeline.ExecutionPlan;
import io.aetherstream.common.pipeline.Node;
import io.aetherstream.common.pipeline.PipelineDefinition;
import io.aetherstream.common.protocol.Protocol;
import io.aetherstream.common.protocol.RegisterRequest;
import io.aetherstream.common.protocol.StartExecutionRequest;
import io.aetherstream.compiler.PipelineCompiler;
import io.aetherstream.compiler.ops.Collector;
import io.aetherstream.compiler.ops.PartitionPipeline;
import io.aetherstream.engine.coordinator.CheckpointRecord;
import io.aetherstream.engine.coordinator.Clock;
import io.aetherstream.engine.coordinator.Coordinator;
import io.aetherstream.engine.coordinator.CoordinatorHttpServer;
import io.aetherstream.engine.coordinator.KafkaOffsetsLookup;
import io.aetherstream.engine.coordinator.PostgresExecutionRepository;
import io.aetherstream.engine.storage.S3CheckpointStore;
import io.aetherstream.engine.worker.HttpCoordinatorClient;
import io.aetherstream.engine.worker.KafkaRecordSource;
import io.aetherstream.engine.worker.PostgresResultSink;
import io.aetherstream.engine.worker.Worker;
import io.aetherstream.reference.EngineAlertRow;
import io.aetherstream.reference.EngineWindowRow;
import io.aetherstream.reference.KafkaRangeReader;
import io.aetherstream.reference.RawRecord;
import io.aetherstream.reference.ReferenceExecutor;
import io.aetherstream.reference.ReferenceResult;
import io.aetherstream.reference.ResultComparator;
import io.aetherstream.simulator.Simulation;
import io.aetherstream.tests.support.IntegrationInfrastructure;
import io.aetherstream.tests.support.WorkloadGenerator;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.postgresql.ds.PGSimpleDataSource;

/** Opt-in measurements against real services, never a synthetic throughput estimate. */
@Tag("benchmark")
class EngineBenchmarkIT {
    private static final int INPUT_RECORDS = 200_000;
    private static final int PARTITIONS = 6;
    private static final int WORKERS = 3;
    private static final long CHECKPOINT_INTERVAL_MS = 2_000;
    private static final TypeReference<Map<String, Double>> VALUES = new TypeReference<>() {};

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void measuresEngineAndRecovery() throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("status", "FAILED");
        report.put("startedAt", Instant.now().toString());
        report.put("machine", machine());
        report.put("inputs", Map.of("records", INPUT_RECORDS, "partitions", PARTITIONS, "workers", WORKERS,
                "checkpointIntervalMs", CHECKPOINT_INTERVAL_MS, "baseSeed", 42, "extensionSeed", 43,
                "baseTrucks", 16, "extensionTrucks", 64, "pollIntervalMs", 100,
                "topology", "single runner; coordinator and workers in one JVM; services in Docker"));
        Throwable failure = null;
        try {
            IntegrationInfrastructure.truncateAll();
            Map<Position, Long> producedAt = produce(workload());
            var offsets = new KafkaOffsetsLookup(IntegrationInfrastructure.bootstrap());
            Map<Integer, Long> beginning = offsets.offsets(IntegrationInfrastructure.TOPIC, PARTITIONS, "earliest");
            Map<Integer, Long> end = offsets.offsets(IntegrationInfrastructure.TOPIC, PARTITIONS, "latest");
            assertThat(end.entrySet().stream().mapToLong(e -> e.getValue() - beginning.get(e.getKey())).sum())
                    .as("preloaded input count").isEqualTo(INPUT_RECORDS);
            report.put("beginningOffsets", beginning);
            report.put("endOffsets", end);
            ExecutionPlan plan = earliestDemoPlan();
            var repository = repository();
            try (var cluster = new BenchmarkCluster(repository); var http = HttpClient.newHttpClient()) {
                String baselineId = "benchmark-baseline-" + UUID.randomUUID();
                Map<String, Object> baseline = new LinkedHashMap<>();
                report.put("baseline", baseline);
                baseline.put("executionId", baselineId);
                long started = System.nanoTime();
                start(http, cluster, baselineId, plan);
                CheckpointRecord completed = awaitConsumed(cluster, repository, baselineId, end);
                double elapsedMs = elapsedMs(started);
                baseline.put("inputRecords", INPUT_RECORDS);
                baseline.put("completedCheckpointId", completed.checkpointId());
                baseline.put("elapsedMs", elapsedMs);
                baseline.put("recordsPerSecond", INPUT_RECORDS * 1_000.0 / elapsedMs);
                baseline.put("checkpointDurationMs", checkpointDurations(baselineId, completed.checkpointId()));
                cluster.coordinator.stopExecution(baselineId);
                CheckpointRecord initial = repository.findCheckpoint(baselineId, 0).orElseThrow();
                assertThat(initial.sourceOffsets()).isEqualTo(beginning);
                // Replay and SQL reads are outside the timed throughput interval.
                Map<Integer, List<RawRecord>> records = new KafkaRangeReader().read(
                        IntegrationInfrastructure.bootstrap(), plan.topic(), initial.sourceOffsets(), completed.sourceOffsets());
                ReferenceResult reference = new ReferenceExecutor().run(plan, records);
                Results results = loadResults(baselineId);
                baseline.put("windows", results.windows().size());
                baseline.put("alerts", results.alerts().size());
                long rejected = reference.deadLetters().stream().filter(d -> d.kind().equals("REJECTED")).count();
                baseline.put("rejectedRecords", rejected);
                baseline.put("acceptedRecords", INPUT_RECORDS - rejected);
                baseline.put("lateRecords", reference.deadLetters().stream().filter(d -> d.kind().equals("LATE")).count());
                baseline.put("latencyMs", percentiles(windowLatencies(plan, records, producedAt, results.writtenAt()), true));
                verify(baselineId, completed, reference, results, baseline);

                String recoveryId = "benchmark-recovery-" + UUID.randomUUID();
                Map<String, Object> recovery = new LinkedHashMap<>();
                report.put("recovery", recovery);
                recovery.put("executionId", recoveryId);
                recovery.put("crashMode", "cancel worker heartbeat and TaskRuntime.stop(); no JVM/process halt");
                // Bound progress so the failure happens after a real checkpoint, while input remains.
                cluster.coordinator.setSinkDelay(1);
                await(cluster, "all workers apply the recovery-only 1 ms sink delay", () ->
                        cluster.workers.stream().allMatch(w -> w.sinkDelayMs() == 1));
                long recoveryRunStarted = System.nanoTime();
                start(http, cluster, recoveryId, plan);
                await(cluster, "positive checkpoint with unconsumed input", () -> {
                    CheckpointRecord cp = repository.latestCompleted(recoveryId).orElseThrow();
                    if (cp.checkpointId() == 0) return false;
                    assertThat(cp.sourceOffsets()).as("recovery must crash before all input is covered").isNotEqualTo(end);
                    return true;
                });
                CheckpointRecord crashCheckpoint = repository.latestCompleted(recoveryId).orElseThrow();
                long generation = repository.findExecution(recoveryId).orElseThrow().generation();
                assertThat(cluster.workers.get(0).status().partitions()).isNotEmpty();
                recovery.put("crashCheckpointId", crashCheckpoint.checkpointId());
                recovery.put("crashGeneration", generation);
                long crashed = System.nanoTime();
                cluster.crash(0);
                cluster.coordinator.setSinkDelay(0);
                await(cluster, "RUNNING execution in a newer generation", () -> {
                    var execution = repository.findExecution(recoveryId).orElseThrow();
                    return execution.state().equals("RUNNING") && execution.generation() > generation;
                });
                recovery.put("recoveryMs", elapsedMs(crashed));
                recovery.put("recoveredGeneration", repository.findExecution(recoveryId).orElseThrow().generation());
                CheckpointRecord recovered = awaitConsumed(cluster, repository, recoveryId, end);
                recovery.put("elapsedMs", elapsedMs(recoveryRunStarted));
                recovery.put("completedCheckpointId", recovered.checkpointId());
                assertThat(repository.findCheckpoint(recoveryId, 0).orElseThrow().sourceOffsets()).isEqualTo(beginning);
                cluster.coordinator.stopExecution(recoveryId);
                verify(recoveryId, recovered, reference, loadResults(recoveryId), recovery);
                cluster.checkHealthy();
            }
            report.put("status", "PASSED");
        } catch (Exception | Error e) {
            failure = e;
            report.put("failure", e.toString());
            throw e;
        } finally {
            report.put("finishedAt", Instant.now().toString());
            try {
                BenchReport.write(report);
            } catch (IOException e) {
                if (failure == null) throw e;
                failure.addSuppressed(e);
            }
        }
    }

    private static List<ProducerRecord<String, String>> workload() {
        var records = new ArrayList<ProducerRecord<String, String>>(INPUT_RECORDS);
        records.addAll(WorkloadGenerator.generate());
        assertThat(records.size()).isLessThan(INPUT_RECORDS);
        Simulation extension = new Simulation(43, 64, 0, true);
        long baseDuration = Duration.ofMinutes(40).toMillis();
        for (long elapsed = 0; records.size() < INPUT_RECORDS; elapsed += Simulation.POSITION_INTERVAL_MS) {
            var emissions = extension.step(Simulation.START_EPOCH_MS + elapsed);
            if (elapsed <= baseDuration) continue;
            for (var emission : emissions) {
                records.add(new ProducerRecord<>(IntegrationInfrastructure.TOPIC, emission.key(), emission.json()));
                if (records.size() == INPUT_RECORDS) break;
            }
        }
        return records;
    }

    private static Map<Position, Long> produce(List<ProducerRecord<String, String>> records) {
        Properties properties = new Properties();
        properties.put("bootstrap.servers", IntegrationInfrastructure.bootstrap());
        properties.put("key.serializer", StringSerializer.class.getName());
        properties.put("value.serializer", StringSerializer.class.getName());
        properties.put("acks", "all");
        properties.put("enable.idempotence", "true");
        Map<Position, Long> producedAt = new ConcurrentHashMap<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(properties)) {
            for (var record : records) {
                long wallMs = System.currentTimeMillis();
                producer.send(record, (metadata, error) -> {
                    if (error != null) failure.compareAndSet(null, error);
                    else producedAt.put(new Position(metadata.partition(), metadata.offset()), wallMs);
                });
            }
            producer.flush();
        }
        if (failure.get() != null) throw new IllegalStateException("Benchmark production failed", failure.get());
        assertThat(producedAt).hasSize(INPUT_RECORDS);
        return producedAt;
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

    private static void start(HttpClient http, BenchmarkCluster cluster, String id, ExecutionPlan plan) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(cluster.url() + "/coord/executions"))
                .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(Json.MAPPER.writeValueAsBytes(new StartExecutionRequest(id, plan))))
                .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }

    private static CheckpointRecord awaitConsumed(BenchmarkCluster cluster, PostgresExecutionRepository repository,
                                                   String id, Map<Integer, Long> end) throws Exception {
        await(cluster, "COMPLETED checkpoint covering " + end + " for " + id, () -> {
            var cp = repository.latestCompleted(id).orElse(null);
            return cp != null && cp.checkpointId() > 0 && end.equals(cp.sourceOffsets());
        });
        return repository.latestCompleted(id).orElseThrow();
    }

    private static void await(BenchmarkCluster cluster, String condition, BooleanSupplier ready) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
        while (System.nanoTime() < deadline) {
            cluster.checkHealthy();
            if (ready.getAsBoolean()) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for " + condition + "; cluster=" + cluster.coordinator.clusterState());
    }

    private static List<Double> windowLatencies(ExecutionPlan plan, Map<Integer, List<RawRecord>> records,
            Map<Position, Long> producedAt, Map<WindowKey, Long> writtenAt) {
        List<Double> latencies = new ArrayList<>(writtenAt.size());
        for (var partition : records.entrySet()) {
            var pipeline = new PartitionPipeline(plan, partition.getKey());
            // A firing can be triggered by a different key or a filtered event: follow actual operator processing.
            class TriggerCollector implements Collector {
                long produced;
                @Override public void windowResult(String sink, WindowResult result) {
                    Long written = writtenAt.get(new WindowKey(sink, result.key(), result.windowStart()));
                    assertThat(written).as("written_at for fired window").isNotNull();
                    double latency = written - produced;
                    assertThat(latency).as("same-host wall clocks must not yield negative latency").isNotNegative();
                    latencies.add(latency);
                }
                @Override public void alert(String sink, Alert alert) { }
                @Override public void deadLetter(DeadLetter letter) { }
            }
            var collector = new TriggerCollector();
            for (RawRecord record : partition.getValue()) {
                Long produced = producedAt.get(new Position(record.partition(), record.offset()));
                assertThat(produced).as("producer timestamp for source position").isNotNull();
                collector.produced = produced;
                pipeline.process(record.offset(), record.value(), collector);
            }
        }
        assertThat(latencies).hasSize(writtenAt.size()).isNotEmpty();
        return latencies;
    }

    private static Map<String, Object> checkpointDurations(String id, long through) throws Exception {
        List<Double> durations = new ArrayList<>();
        try (var c = DriverManager.getConnection(IntegrationInfrastructure.jdbcUrl(), "aether", "aether");
             var s = c.prepareStatement("SELECT duration_ms FROM checkpoints WHERE execution_id = ? "
                     + "AND state = 'COMPLETED' AND checkpoint_id > 0 AND checkpoint_id <= ? ORDER BY checkpoint_id")) {
            s.setString(1, id);
            s.setLong(2, through);
            try (var rows = s.executeQuery()) {
                while (rows.next()) {
                    double duration = rows.getDouble(1);
                    assertThat(rows.wasNull()).as("completed checkpoint duration").isFalse();
                    durations.add(duration);
                }
            }
        }
        return percentiles(durations, false);
    }

    private static Map<String, Object> percentiles(List<Double> samples, boolean p99) {
        assertThat(samples).isNotEmpty();
        samples.sort(Double::compare);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("samples", samples.size());
        result.put("p50", samples.get((int) Math.ceil(samples.size() * 0.50) - 1));
        result.put("p95", samples.get((int) Math.ceil(samples.size() * 0.95) - 1));
        if (p99) result.put("p99", samples.get((int) Math.ceil(samples.size() * 0.99) - 1));
        return result;
    }

    private static void verify(String id, CheckpointRecord completed, ReferenceResult reference,
                               Results results, Map<String, Object> metrics) throws Exception {
        long started = System.nanoTime();
        CompareResult result = new ResultComparator().compare(reference, results.windows(),
                List.copyOf(results.alerts().keySet()), completed.checkpointId(), 0);
        CompareResult comparison = new CompareResult(result.checkpointId(), result.referenceResults(),
                result.engineResults(), result.matched(), result.mismatches(), result.recordsReplayed(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), result.match());
        Map<DeadLetter, Long> expectedDead = new HashMap<>();
        reference.deadLetters().forEach(d -> expectedDead.merge(d, 1L, Long::sum));
        Map<EngineAlertRow, Alert> expectedAlerts = new HashMap<>();
        reference.alertsBySink().forEach((sink, alerts) -> alerts.forEach(a ->
                expectedAlerts.put(new EngineAlertRow(sink, a.alertId()), a)));
        metrics.put("comparison", comparison);
        metrics.put("deadLettersMatch", expectedDead.equals(results.deadLetters()));
        metrics.put("alertPayloadsMatch", expectedAlerts.equals(results.alerts()));
        metrics.put("verificationMs", elapsedMs(started));
        assertThat(comparison.match()).as("reference comparison for %s: %s", id, comparison).isTrue();
        assertThat(comparison.recordsReplayed()).isEqualTo(INPUT_RECORDS);
        assertThat(results.alerts()).as("alert payloads").isEqualTo(expectedAlerts);
        assertThat(results.deadLetters()).as("dead letter payloads and multiplicities").isEqualTo(expectedDead);
    }

    private static Results loadResults(String id) throws Exception {
        List<EngineWindowRow> windows = new ArrayList<>();
        Map<WindowKey, Long> writtenAt = new HashMap<>();
        Map<EngineAlertRow, Alert> alerts = new HashMap<>();
        Map<DeadLetter, Long> deadLetters = new HashMap<>();
        try (var c = DriverManager.getConnection(IntegrationInfrastructure.jdbcUrl(), "aether", "aether")) {
            try (var s = c.prepareStatement("SELECT sink_id, key, window_start, window_end, \"values\", event_count, "
                    + "written_at FROM window_results WHERE execution_id = ?")) {
                s.setString(1, id);
                try (var rows = s.executeQuery()) {
                    while (rows.next()) {
                        windows.add(new EngineWindowRow(rows.getString(1), rows.getString(2), rows.getLong(3),
                                rows.getLong(4), Json.MAPPER.readValue(rows.getString(5), VALUES), rows.getLong(6)));
                        writtenAt.put(new WindowKey(rows.getString(1), rows.getString(2), rows.getLong(3)),
                                rows.getTimestamp(7).getTime());
                    }
                }
            }
            try (var s = c.prepareStatement("SELECT sink_id, alert_id, key, rule_id, message, event_time "
                    + "FROM alerts WHERE execution_id = ?")) {
                s.setString(1, id);
                try (var rows = s.executeQuery()) {
                    while (rows.next()) alerts.put(new EngineAlertRow(rows.getString(1), rows.getString(2)),
                            new Alert(rows.getString(2), rows.getString(3), rows.getString(4), rows.getString(5), rows.getLong(6)));
                }
            }
            try (var s = c.prepareStatement("SELECT event_id, kind, reason, partition, \"offset\", event_time, raw "
                    + "FROM dead_letters WHERE execution_id = ?")) {
                s.setString(1, id);
                try (var rows = s.executeQuery()) {
                    while (rows.next()) {
                        long eventTime = rows.getLong(6);
                        Long nullableTime = rows.wasNull() ? null : eventTime;
                        var letter = new DeadLetter(rows.getString(1), rows.getString(2), rows.getString(3),
                                rows.getInt(4), rows.getLong(5), nullableTime, rows.getString(7));
                        deadLetters.merge(letter, 1L, Long::sum);
                    }
                }
            }
        }
        return new Results(windows, writtenAt, alerts, deadLetters);
    }

    private static PostgresExecutionRepository repository() {
        return new PostgresExecutionRepository(dataSource());
    }

    private static PGSimpleDataSource dataSource() {
        var source = new PGSimpleDataSource();
        source.setURL(IntegrationInfrastructure.jdbcUrl());
        source.setUser("aether");
        source.setPassword("aether");
        return source;
    }

    private static Map<String, Object> machine() {
        var os = ManagementFactory.getOperatingSystemMXBean();
        var runtime = ManagementFactory.getRuntimeMXBean();
        Map<String, Object> machine = new LinkedHashMap<>();
        machine.put("os", os.getName());
        machine.put("osVersion", os.getVersion());
        machine.put("architecture", os.getArch());
        machine.put("processors", Runtime.getRuntime().availableProcessors());
        machine.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        machine.put("javaVersion", System.getProperty("java.version"));
        machine.put("vm", runtime.getVmName() + " " + runtime.getVmVersion());
        machine.put("jvmArguments", runtime.getInputArguments());
        machine.put("systemLoadAverage", os.getSystemLoadAverage());
        machine.put("commit", System.getenv().getOrDefault("GITHUB_SHA", "local"));
        if (os instanceof com.sun.management.OperatingSystemMXBean extended) {
            machine.put("totalMemoryBytes", extended.getTotalMemorySize());
            machine.put("freeMemoryBytes", extended.getFreeMemorySize());
        }
        return machine;
    }

    private static double elapsedMs(long started) { return (System.nanoTime() - started) / 1_000_000.0; }

    private record Position(int partition, long offset) { }
    private record WindowKey(String sink, String key, long start) { }
    private record Results(List<EngineWindowRow> windows, Map<WindowKey, Long> writtenAt,
                           Map<EngineAlertRow, Alert> alerts, Map<DeadLetter, Long> deadLetters) { }

    /** EngineCluster's wiring, with individually cancellable heartbeats for non-graceful failure injection. */
    private static final class BenchmarkCluster implements AutoCloseable {
        private final List<Worker> workers = new ArrayList<>();
        private final List<ScheduledFuture<?>> heartbeats = new ArrayList<>();
        private final List<Object> heartbeatLocks = new ArrayList<>();
        private final ScheduledExecutorService loops = Executors.newScheduledThreadPool(WORKERS + 1);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final Coordinator coordinator;
        private final CoordinatorHttpServer http;

        BenchmarkCluster(PostgresExecutionRepository repository) throws Exception {
            var store = new S3CheckpointStore(IntegrationInfrastructure.s3Endpoint(), "aether", "aether-secret",
                    IntegrationInfrastructure.BUCKET);
            coordinator = new Coordinator(repository, store, new KafkaOffsetsLookup(IntegrationInfrastructure.bootstrap()),
                    Clock.SYSTEM, CHECKPOINT_INTERVAL_MS);
            coordinator.loadActiveExecutions();
            http = new CoordinatorHttpServer(coordinator, 0);
            try {
                http.start();
                loops.scheduleWithFixedDelay(() -> guarded(coordinator::tick), 250, 250, TimeUnit.MILLISECONDS);
                for (int i = 0; i < WORKERS; i++) {
                    var client = new HttpCoordinatorClient(url());
                    var registration = client.register(new RegisterRequest(Protocol.VERSION, "bench-worker-" + i, "localhost"));
                    String workerId = registration.workerId();
                    var worker = new Worker(workerId,
                            topic -> new KafkaRecordSource(IntegrationInfrastructure.bootstrap(), topic, workerId),
                            new PostgresResultSink(dataSource()), store, client,
                            () -> { throw new IllegalStateException("Use benchmark runtime/heartbeat crash, not process KILL"); });
                    workers.add(worker);
                    Object lock = new Object();
                    heartbeatLocks.add(lock);
                    heartbeats.add(loops.scheduleWithFixedDelay(() -> {
                        synchronized (lock) {
                            guarded(() -> worker.handleAll(client.heartbeat(workerId, worker.status()).commands()));
                        }
                    }, 0, registration.heartbeatIntervalMs(), TimeUnit.MILLISECONDS));
                }
            } catch (Exception | Error e) {
                close();
                throw e;
            }
        }

        String url() { return "http://localhost:" + http.port(); }

        void crash(int index) {
            // Wait out any in-flight heartbeat; no heartbeat or checkpoint command can run after cancellation.
            synchronized (heartbeatLocks.get(index)) {
                heartbeats.get(index).cancel(false);
                var runtime = workers.get(index).runtime();
                assertThat(runtime).as("live runtime to crash").isNotNull();
                runtime.stop();
            }
        }

        void checkHealthy() {
            if (failure.get() != null) throw new AssertionError("Benchmark cluster loop failed", failure.get());
        }

        private void guarded(CheckedAction action) {
            try { action.run(); }
            catch (Throwable e) { if (!loops.isShutdown()) failure.compareAndSet(null, e); }
        }

        @Override public void close() {
            loops.shutdownNow();
            try {
                if (!loops.awaitTermination(10, TimeUnit.SECONDS)) throw new IllegalStateException("Benchmark loops did not stop");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted stopping benchmark cluster", e);
            } finally {
                try { workers.forEach(Worker::shutdown); }
                finally { http.stop(); }
            }
        }

        @FunctionalInterface private interface CheckedAction { void run() throws Exception; }
    }
}
