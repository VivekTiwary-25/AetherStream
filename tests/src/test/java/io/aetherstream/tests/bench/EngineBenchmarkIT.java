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
import io.aetherstream.common.protocol.StartExecutionRequest;
import io.aetherstream.compiler.PipelineCompiler;
import io.aetherstream.engine.coordinator.CheckpointRecord;
import io.aetherstream.engine.coordinator.Clock;
import io.aetherstream.engine.coordinator.Coordinator;
import io.aetherstream.engine.coordinator.CoordinatorHttpServer;
import io.aetherstream.engine.coordinator.KafkaOffsetsLookup;
import io.aetherstream.engine.coordinator.PostgresExecutionRepository;
import io.aetherstream.engine.storage.S3CheckpointStore;
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
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.File;
import java.util.LinkedHashSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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
    private static final List<Integer> WORKER_COUNTS = List.of(1, 2, 3);
    private static final long CHECKPOINT_INTERVAL_MS = 2_000;
    private static final TypeReference<Map<String, Double>> VALUES = new TypeReference<>() {};

    @Test
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    void measuresEngineAndRecovery() throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("status", "FAILED");
        report.put("startedAt", Instant.now().toString());
        report.put("machine", machine());
        report.put("inputs", Map.of("records", INPUT_RECORDS, "partitions", PARTITIONS, "workers", WORKER_COUNTS,
                "checkpointIntervalMs", CHECKPOINT_INTERVAL_MS, "baseSeed", 42, "extensionSeed", 43,
                "baseTrucks", 16, "extensionTrucks", 64, "pollIntervalMs", 100,
                "topology", "single runner; separate worker JVMs; coordinator in test JVM; services in Docker"));
        List<Map<String, Object>> runs = new ArrayList<>();
        report.put("runs", runs);
        Throwable failure = null;
        try {
            IntegrationInfrastructure.truncateAll();
            produce(workload());
            var offsets = new KafkaOffsetsLookup(IntegrationInfrastructure.bootstrap());
            Map<Integer, Long> beginning = offsets.offsets(IntegrationInfrastructure.TOPIC, PARTITIONS, "earliest");
            Map<Integer, Long> end = offsets.offsets(IntegrationInfrastructure.TOPIC, PARTITIONS, "latest");
            assertThat(end.entrySet().stream().mapToLong(e -> e.getValue() - beginning.get(e.getKey())).sum())
                    .as("preloaded input count").isEqualTo(INPUT_RECORDS);
            report.put("beginningOffsets", beginning);
            report.put("endOffsets", end);
            ExecutionPlan plan = earliestDemoPlan();
            var repository = repository();
            // One immutable Kafka range and reference for all six executions.
            Map<Integer, List<RawRecord>> records = new KafkaRangeReader().read(
                    IntegrationInfrastructure.bootstrap(), plan.topic(), beginning, end);
            ReferenceResult reference = new ReferenceExecutor().run(plan, records);
            for (int workerCount : WORKER_COUNTS) {
                Map<String, Object> run = new LinkedHashMap<>();
                run.put("workers", workerCount);
                runs.add(run);
                try (var cluster = new BenchmarkCluster(repository, workerCount);
                     var http = HttpClient.newHttpClient()) {
                    await(cluster, "all initial workers registered", () ->
                            cluster.coordinator.clusterState().workers().stream().filter(w -> w.alive()).count() == workerCount);
                    String baselineId = "benchmark-baseline-" + workerCount + "-" + UUID.randomUUID();
                    Map<String, Object> baseline = new LinkedHashMap<>();
                    run.put("baseline", baseline);
                    baseline.put("executionId", baselineId);
                    long started = System.nanoTime();
                    start(http, cluster, baselineId, plan);
                    CheckpointRecord completed = awaitConsumed(cluster, repository, baselineId, end);
                    double durationMs = elapsedMs(started);
                    baseline.put("inputRecords", INPUT_RECORDS);
                    baseline.put("completedCheckpointId", completed.checkpointId());
                    baseline.put("generation", repository.findExecution(baselineId).orElseThrow().generation());
                    baseline.put("elapsedMs", durationMs);
                    baseline.put("recordsPerSecond", INPUT_RECORDS * 1_000.0 / durationMs);
                    baseline.put("checkpointDurationMs", checkpointDurations(baselineId, completed.checkpointId()));
                    cluster.coordinator.stopExecution(baselineId);
                    assertThat(repository.findCheckpoint(baselineId, 0).orElseThrow().sourceOffsets()).isEqualTo(beginning);
                    verify(baselineId, completed, reference, loadResults(baselineId), baseline);

                    // Use a separate execution so sink delay and the kill never contaminate baseline throughput.
                    String recoveryId = "benchmark-recovery-" + workerCount + "-" + UUID.randomUUID();
                    Map<String, Object> recovery = new LinkedHashMap<>();
                    run.put("recovery", recovery);
                    recovery.put("executionId", recoveryId);
                    recovery.put("crashMode", "Process.destroyForcibly(): real worker JVM kill, no shutdown hooks");
                    recovery.put("replacementPolicy", workerCount == 1
                            ? "start new worker after coordinator declares victim DEAD"
                            : "recover on surviving workers; no replacement");
                    cluster.coordinator.setSinkDelay(1);
                    start(http, cluster, recoveryId, plan);
                    await(cluster, "positive checkpoint with unconsumed input", () -> {
                        CheckpointRecord cp = repository.latestCompleted(recoveryId).orElseThrow();
                        if (cp.checkpointId() == 0 || cp.sourceOffsets().equals(beginning)) return false;
                        assertThat(cp.sourceOffsets()).as("kill must precede input completion").isNotEqualTo(end);
                        return true;
                    });
                    CheckpointRecord crashCheckpoint = repository.latestCompleted(recoveryId).orElseThrow();
                    long generation = repository.findExecution(recoveryId).orElseThrow().generation();
                    assertThat(cluster.coordinator.clusterState().workers().stream()
                            .filter(w -> w.workerId().equals(cluster.workerIds.getFirst())).findFirst().orElseThrow()
                            .partitions()).isNotEmpty();
                    recovery.put("crashCheckpointId", crashCheckpoint.checkpointId());
                    recovery.put("crashGeneration", generation);
                    long killed = System.nanoTime();
                    cluster.crash(0);
                    cluster.coordinator.setSinkDelay(0);
                    if (workerCount == 1) {
                        await(cluster, "killed worker declared DEAD", () ->
                                cluster.coordinator.clusterState().workers().stream()
                                        .anyMatch(w -> w.workerId().equals(cluster.workerIds.getFirst()) && !w.alive()));
                        cluster.startWorker("bench-replacement");
                    }
                    await(cluster, "RUNNING execution in a newer generation", () -> {
                        var execution = repository.findExecution(recoveryId).orElseThrow();
                        return execution.state().equals("RUNNING") && execution.generation() > generation;
                    });
                    recovery.put("recoveryMs", elapsedMs(killed));
                    recovery.put("recoveredGeneration", repository.findExecution(recoveryId).orElseThrow().generation());
                    recovery.put("restoredFromCheckpoint", repository.findExecution(recoveryId).orElseThrow()
                            .restoredFromCheckpoint());
                    assertThat(repository.findExecution(recoveryId).orElseThrow().restoredFromCheckpoint())
                            .as("recovery uses a completed positive checkpoint").isGreaterThanOrEqualTo(crashCheckpoint.checkpointId());
                    recovery.put("workersAfterKill", workerCount == 1 ? 1 : workerCount - 1);
                    CheckpointRecord recovered = awaitConsumed(cluster, repository, recoveryId, end);
                    recovery.put("completedCheckpointId", recovered.checkpointId());
                    recovery.put("checkpointDurationMs", checkpointDurations(recoveryId, recovered.checkpointId()));
                    assertThat(repository.findCheckpoint(recoveryId, 0).orElseThrow().sourceOffsets()).isEqualTo(beginning);
                    cluster.coordinator.stopExecution(recoveryId);
                    verify(recoveryId, recovered, reference, loadResults(recoveryId), recovery);
                    cluster.checkHealthy();
                }
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

    private static void produce(List<ProducerRecord<String, String>> records) {
        Properties properties = new Properties();
        properties.put("bootstrap.servers", IntegrationInfrastructure.bootstrap());
        properties.put("key.serializer", StringSerializer.class.getName());
        properties.put("value.serializer", StringSerializer.class.getName());
        properties.put("acks", "all");
        properties.put("enable.idempotence", "true");
        AtomicLong produced = new AtomicLong();
        AtomicReference<Exception> failure = new AtomicReference<>();
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(properties)) {
            for (var record : records) {
                producer.send(record, (metadata, error) -> {
                    if (error != null) failure.compareAndSet(null, error);
                    else produced.incrementAndGet();
                });
            }
            producer.flush();
        }
        if (failure.get() != null) throw new IllegalStateException("Benchmark production failed", failure.get());
        assertThat(produced.get()).isEqualTo(INPUT_RECORDS);
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
        // SQL reads and reference checks are deliberately outside throughput and recovery timers.
        Map<EngineAlertRow, Alert> alerts = new HashMap<>();
        Map<DeadLetter, Long> deadLetters = new HashMap<>();
        try (var c = DriverManager.getConnection(IntegrationInfrastructure.jdbcUrl(), "aether", "aether")) {
            try (var s = c.prepareStatement("SELECT sink_id, key, window_start, window_end, \"values\", event_count "
                    + "FROM window_results WHERE execution_id = ?")) {
                s.setString(1, id);
                try (var rows = s.executeQuery()) {
                    while (rows.next()) {
                        windows.add(new EngineWindowRow(rows.getString(1), rows.getString(2), rows.getLong(3),
                                rows.getLong(4), Json.MAPPER.readValue(rows.getString(5), VALUES), rows.getLong(6)));
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
        return new Results(windows, alerts, deadLetters);
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
        machine.put("runUrl", System.getenv().containsKey("GITHUB_RUN_ID")
                ? System.getenv("GITHUB_SERVER_URL") + "/" + System.getenv("GITHUB_REPOSITORY")
                        + "/actions/runs/" + System.getenv("GITHUB_RUN_ID") : "local");
        for (String key : List.of("RUNNER_OS", "RUNNER_ARCH", "ImageOS", "ImageVersion")) {
            machine.put(key, System.getenv().getOrDefault(key, "unavailable"));
        }
        machine.put("workerMaxHeapBytes", 512L * 1024 * 1024);
        if (os instanceof com.sun.management.OperatingSystemMXBean extended) {
            machine.put("totalMemoryBytes", extended.getTotalMemorySize());
            machine.put("freeMemoryBytes", extended.getFreeMemorySize());
        }
        return machine;
    }

    private static double elapsedMs(long started) { return (System.nanoTime() - started) / 1_000_000.0; }

    private record Results(List<EngineWindowRow> windows,
                           Map<EngineAlertRow, Alert> alerts, Map<DeadLetter, Long> deadLetters) { }

    /** Workers run in killable JVMs; only the coordinator and its tick loop share the test process. */
    private static final class BenchmarkCluster implements AutoCloseable {
        private final List<Process> workers = new ArrayList<>();
        private final List<String> workerIds = new ArrayList<>();
        private final List<Boolean> killed = new ArrayList<>();
        private final ScheduledExecutorService loops = Executors.newSingleThreadScheduledExecutor();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final Coordinator coordinator;
        private final CoordinatorHttpServer http;
        private final Path logs;

        BenchmarkCluster(PostgresExecutionRepository repository, int workerCount) throws Exception {
            logs = Path.of(System.getProperty("aether.benchmark.reportDir", "build/reports/benchmark"))
                    .resolve("workers-" + workerCount).toAbsolutePath();
            Files.createDirectories(logs);
            var store = new S3CheckpointStore(IntegrationInfrastructure.s3Endpoint(), "aether", "aether-secret",
                    IntegrationInfrastructure.BUCKET);
            coordinator = new Coordinator(repository, store, new KafkaOffsetsLookup(IntegrationInfrastructure.bootstrap()),
                    Clock.SYSTEM, CHECKPOINT_INTERVAL_MS);
            coordinator.loadActiveExecutions();
            http = new CoordinatorHttpServer(coordinator, 0);
            try {
                http.start();
                loops.scheduleWithFixedDelay(() -> guarded(coordinator::tick), 250, 250, TimeUnit.MILLISECONDS);
                for (int i = 0; i < workerCount; i++) startWorker("bench-worker-" + workerCount + "-" + i);
            } catch (Exception | Error e) {
                close();
                throw e;
            }
        }

        String url() { return "http://localhost:" + http.port(); }

        void startWorker(String id) throws Exception {
            // Gradle's test classpath lives in a URLClassLoader, not necessarily java.class.path.
            var entries = new LinkedHashSet<String>();
            entries.add(System.getProperty("java.class.path"));
            for (ClassLoader loader = Thread.currentThread().getContextClassLoader(); loader != null; loader = loader.getParent()) {
                if (loader instanceof URLClassLoader urls) {
                    for (var entry : urls.getURLs()) entries.add(Path.of(entry.toURI()).toString());
                }
            }
            String classpath = String.join(File.pathSeparator, entries);
            List<String> args = List.of("-Xmx512m", "-cp", classpath, BenchmarkWorkerMain.class.getName(),
                    IntegrationInfrastructure.bootstrap(), IntegrationInfrastructure.jdbcUrl(),
                    IntegrationInfrastructure.s3Endpoint(), url(), id);
            // An argument file also works with Windows' command-line length limit.
            Path argFile = logs.resolve(id + ".args");
            Files.write(argFile, args.stream()
                    .map(arg -> "\"" + arg.replace("\\", "\\\\").replace("\"", "\\\"") + "\"").toList());
            Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "@" + argFile).redirectErrorStream(true).redirectOutput(logs.resolve(id + ".log").toFile()).start();
            workers.add(process);
            workerIds.add(id);
            killed.add(false);
        }

        void crash(int index) throws Exception {
            killed.set(index, true);
            Process process = workers.get(index);
            process.destroyForcibly();
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).as("worker process killed").isTrue();
            assertThat(process.exitValue()).as("worker exit status after forced kill").isNotZero();
        }

        void checkHealthy() {
            if (failure.get() != null) throw new AssertionError("Benchmark cluster loop failed", failure.get());
            for (int i = 0; i < workers.size(); i++) {
                if (!killed.get(i) && !workers.get(i).isAlive()) {
                    throw new AssertionError("Worker " + workerIds.get(i) + " exited " + workers.get(i).exitValue()
                            + "; see " + logs.resolve(workerIds.get(i) + ".log"));
                }
            }
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
                try {
                    for (Process worker : workers) worker.destroyForcibly();
                    for (Process worker : workers) {
                        if (!worker.waitFor(10, TimeUnit.SECONDS)) throw new IllegalStateException("Worker process did not exit");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted stopping worker processes", e);
                }
                finally { http.stop(); }
            }
        }

        @FunctionalInterface private interface CheckedAction { void run() throws Exception; }
    }
}
