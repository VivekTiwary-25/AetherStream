package io.aetherstream.tests;

import static io.aetherstream.tests.WorkerCrashRecoveryIT.await;
import static io.aetherstream.tests.WorkerCrashRecoveryIT.earliestDemoPlan;
import static io.aetherstream.tests.WorkerCrashRecoveryIT.post;
import static io.aetherstream.tests.WorkerCrashRecoveryIT.repository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.core.type.TypeReference;
import io.aetherstream.common.Json;
import io.aetherstream.common.WindowResult;
import io.aetherstream.common.protocol.StartExecutionRequest;
import io.aetherstream.compiler.ops.ListCollector;
import io.aetherstream.engine.coordinator.KafkaOffsetsLookup;
import io.aetherstream.engine.worker.PostgresResultSink;
import io.aetherstream.engine.worker.SinkRow;
import io.aetherstream.reference.EngineWindowRow;
import io.aetherstream.reference.KafkaRangeReader;
import io.aetherstream.reference.ReferenceExecutor;
import io.aetherstream.reference.ReferenceResult;
import io.aetherstream.tests.support.EngineCluster;
import io.aetherstream.tests.support.IntegrationInfrastructure;
import io.aetherstream.tests.support.WorkloadGenerator;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.postgresql.ds.PGSimpleDataSource;

@Tag("integration")
class ExactlyOnceSinkIT {
    private static final Duration DEADLINE = Duration.ofSeconds(15);
    private static final TypeReference<Map<String, Double>> VALUES = new TypeReference<>() {};

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void crashInsideSinkTransactionRollsBackAndReplaysWithoutDuplicateOrMissingWindows() throws Exception {
        IntegrationInfrastructure.truncateAll();
        var workload = WorkloadGenerator.generate();
        IntegrationInfrastructure.produce(workload);
        var plan = earliestDemoPlan();
        var offsets = new KafkaOffsetsLookup(IntegrationInfrastructure.bootstrap());
        var beginning = offsets.offsets(plan.topic(), plan.partitions(), "earliest");
        var end = offsets.offsets(plan.topic(), plan.partitions(), "latest");
        var repository = repository();
        String executionId = "exactly-once-" + UUID.randomUUID();

        try (var connection = DriverManager.getConnection(
                IntegrationInfrastructure.jdbcUrl(), "aether", "aether")) {
            installTransactionDelay(connection, executionId);
            try (var cluster = new EngineCluster(1)) {
                post(cluster, "/coord/executions", new StartExecutionRequest(executionId, plan));
                var worker = cluster.worker(0);
                await(cluster, DEADLINE, "worker running", () -> worker.status().state().equals("RUNNING"));
                var runtime = worker.runtime();
                long oldGeneration = runtime.generation();

                // SET_SINK_DELAY sleeps before the transaction. This AFTER trigger instead pauses with
                // a window row already written, the execution's FOR SHARE fence held, and no COMMIT.
                var delayed = new AtomicReference<DelayedTransaction>();
                await(cluster, DEADLINE, "sink transaction sleeping after its first window write", () -> {
                    delayed.set(findDelayedTransaction(connection));
                    return delayed.get() != null;
                });
                var transaction = delayed.get();
                assertThat(runtime.isStopped()).isFalse();
                assertThat(runtime.processedRecords()).isPositive();
                assertNoOldGenerationWindows(connection, executionId, oldGeneration);
                var beforeCrash = repository.latestCompleted(executionId).orElseThrow();
                assertThat(beforeCrash.sourceOffsets()).as("crash precedes full consumption").isNotEqualTo(end);

                // The in-JVM harness drops queues but cannot kill a JDBC session. Terminate that
                // session too, as an actual process crash would; never release the delay to COMMIT.
                cluster.crash(0);
                terminateBackend(connection, transaction.pid());
                await(cluster, DEADLINE, "crashed sink transaction aborted", () ->
                        transactionAborted(connection, transaction.xid()));
                assertThat(runtime.isStopped()).isTrue();
                // Check before replay: an upsert by the replacement must not hide an old commit.
                assertNoOldGenerationWindows(connection, executionId, oldGeneration);
                removeTransactionDelay(connection);
                await(cluster, DEADLINE, "worker death detected", () ->
                        repository.findExecution(executionId).orElseThrow().state().equals("RECOVERING"));

                var replacement = cluster.addWorker();
                assertThat(replacement.workerId()).isNotEqualTo(worker.workerId());
                await(cluster, DEADLINE, "replacement running with a new generation", () -> {
                    var execution = repository.findExecution(executionId).orElseThrow();
                    return execution.state().equals("RUNNING") && execution.generation() > oldGeneration;
                });
                var recovered = repository.findExecution(executionId).orElseThrow();
                assertThat(recovered.generation()).isEqualTo(oldGeneration + 1);
                assertThat(recovered.restoredFromCheckpoint()).isEqualTo(beforeCrash.checkpointId());
                await(cluster, Duration.ofSeconds(90), "recovered checkpoint consuming every event", () -> {
                    var checkpoint = repository.latestCompleted(executionId).orElseThrow();
                    return checkpoint.generation() == recovered.generation() && end.equals(checkpoint.sourceOffsets());
                });
                var initial = repository.findCheckpoint(executionId, 0).orElseThrow();
                assertThat(initial.sourceOffsets()).isEqualTo(beginning);
                ReferenceResult reference = new ReferenceExecutor().run(plan, new KafkaRangeReader().read(
                        IntegrationInfrastructure.bootstrap(), plan.topic(), initial.sourceOffsets(), end));
                assertThat(reference.recordsReplayed()).isEqualTo(workload.size());

                // A late write using the dead worker's token must reject both an overwrite and a
                // new identity. Comparing all rows afterwards also catches any partial batch commit.
                assertStaleGenerationRejected(executionId, oldGeneration, reference);
                assertNoOldGenerationWindows(connection, executionId, oldGeneration);
                assertWindowsMatchReference(connection, executionId, recovered.generation(), reference);
                cluster.checkHealthy();
            } finally {
                // Also unblock a failed test before dropping its trigger; never leave a sleeping
                // JDBC transaction behind for the harness shutdown or another integration test.
                var delayed = findDelayedTransaction(connection);
                if (delayed != null) terminateBackend(connection, delayed.pid());
                removeTransactionDelay(connection);
            }
        }
    }

    private static void installTransactionDelay(Connection connection, String executionId) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            statement.execute("""
                    CREATE FUNCTION exactly_once_sink_delay() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        PERFORM pg_sleep(120);
                        RETURN NEW;
                    END;
                    $$
                    """);
            statement.execute("""
                    CREATE TRIGGER exactly_once_sink_delay AFTER INSERT OR UPDATE ON window_results
                    FOR EACH ROW WHEN (NEW.execution_id = '%s')
                    EXECUTE FUNCTION exactly_once_sink_delay()
                    """.formatted(executionId));
        }
    }

    private static void removeTransactionDelay(Connection connection) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            statement.execute("DROP TRIGGER IF EXISTS exactly_once_sink_delay ON window_results");
            statement.execute("DROP FUNCTION IF EXISTS exactly_once_sink_delay()");
        }
    }

    private static DelayedTransaction findDelayedTransaction(Connection connection) throws Exception {
        try (var statement = connection.prepareStatement("""
                SELECT pid, backend_xid::text FROM pg_stat_activity
                WHERE state = 'active' AND wait_event = 'PgSleep' AND backend_xid IS NOT NULL
                  AND query LIKE 'INSERT INTO window_results%'
                """)) {
            statement.setQueryTimeout(5);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                var result = new DelayedTransaction(rows.getInt(1), rows.getString(2));
                assertThat(rows.next()).as("only the single victim owns a delayed sink transaction").isFalse();
                return result;
            }
        }
    }

    private static void terminateBackend(Connection connection, int pid) throws Exception {
        try (var statement = connection.prepareStatement("SELECT pg_terminate_backend(?, 5000)")) {
            statement.setQueryTimeout(10);
            statement.setInt(1, pid);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getBoolean(1)).as("victim's Postgres session terminated").isTrue();
            }
        }
    }

    private static boolean transactionAborted(Connection connection, String xid) throws Exception {
        // The test has a fresh Postgres container, so backend_xid is in transaction-id epoch zero.
        try (var statement = connection.prepareStatement("SELECT pg_xact_status(?::xid8)")) {
            statement.setQueryTimeout(5);
            statement.setString(1, xid);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return "aborted".equals(rows.getString(1));
            }
        }
    }

    private static void assertNoOldGenerationWindows(Connection connection, String executionId, long generation)
            throws Exception {
        try (var statement = connection.prepareStatement(
                "SELECT count(*) FROM window_results WHERE execution_id = ? AND generation = ?")) {
            statement.setQueryTimeout(5);
            statement.setString(1, executionId);
            statement.setLong(2, generation);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).as("no window committed by the crashed/fenced generation").isZero();
            }
        }
    }

    private static void assertStaleGenerationRejected(String executionId, long generation, ReferenceResult reference)
            throws Exception {
        var expected = reference.windowsBySink().entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty()).findFirst().orElseThrow();
        var window = expected.getValue().getFirst();
        var overwrite = new WindowResult(window.nodeId(), window.key(), window.windowStart(), window.windowEnd(),
                Map.of("count", -1.0), -1);
        var extra = new WindowResult(window.nodeId(), "fenced-old-worker", window.windowStart(), window.windowEnd(),
                window.values(), window.eventCount());
        var dataSource = new PGSimpleDataSource();
        dataSource.setURL(IntegrationInfrastructure.jdbcUrl());
        dataSource.setUser("aether");
        dataSource.setPassword("aether");
        assertThat(new PostgresResultSink(dataSource).write(executionId, generation, List.of(
                new SinkRow(0, 0, new ListCollector.WindowOutput(expected.getKey(), overwrite)),
                new SinkRow(0, 0, new ListCollector.WindowOutput(expected.getKey(), extra)))))
                .as("nonempty write from the fenced old generation").isFalse();
    }

    private static void assertWindowsMatchReference(Connection connection, String executionId, long generation,
                                                    ReferenceResult reference) throws Exception {
        var expected = new HashMap<WindowIdentity, WindowResult>();
        reference.windowsBySink().forEach((sink, windows) -> windows.forEach(window ->
                expected.put(new WindowIdentity(sink, window.key(), window.windowStart()), window)));
        assertThat(expected.size()).as("truck workload closes many windows").isGreaterThan(100);
        var actual = new ArrayList<EngineWindowRow>();
        try (var statement = connection.prepareStatement("""
                SELECT sink_id, key, window_start, window_end, "values", event_count, generation
                FROM window_results WHERE execution_id = ?
                """)) {
            statement.setQueryTimeout(5);
            statement.setString(1, executionId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    actual.add(new EngineWindowRow(rows.getString(1), rows.getString(2), rows.getLong(3),
                            rows.getLong(4), Json.MAPPER.readValue(rows.getString(5), VALUES), rows.getLong(6)));
                    assertThat(rows.getLong(7)).as("window writer generation").isEqualTo(generation);
                }
            }
        }
        assertThat(actual).extracting(row -> new WindowIdentity(row.sinkId(), row.key(), row.windowStart()))
                .as("unique sink/key/window identities with no missing or extra windows")
                .doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected.keySet());
        for (var row : actual) {
            var window = expected.get(new WindowIdentity(row.sinkId(), row.key(), row.windowStart()));
            assertThat(row.windowEnd()).isEqualTo(window.windowEnd());
            assertThat(row.eventCount()).isEqualTo(window.eventCount());
            assertThat(row.values()).containsOnlyKeys(window.values().keySet());
            window.values().forEach((name, value) ->
                    assertThat(row.values().get(name)).as("%s/%s/%s/%s", row.sinkId(), row.key(), row.windowStart(), name)
                            .isCloseTo(value, within(1e-6)));
        }
    }

    private record DelayedTransaction(int pid, String xid) {}
    private record WindowIdentity(String sinkId, String key, long windowStart) {}
}
