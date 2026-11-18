package io.aetherstream.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.aetherstream.common.Alert;
import io.aetherstream.common.DeadLetter;
import io.aetherstream.common.Json;
import io.aetherstream.common.WindowResult;
import io.aetherstream.compiler.ops.ListCollector;
import io.aetherstream.engine.worker.KafkaRecordSource;
import io.aetherstream.engine.worker.PostgresResultSink;
import io.aetherstream.engine.worker.RecordSource.SourceRecord;
import io.aetherstream.engine.worker.SinkRow;
import io.aetherstream.tests.support.IntegrationInfrastructure;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.postgresql.ds.PGSimpleDataSource;

@Tag("integration")
@Timeout(120)
class WorkerAdaptersIT {
    private static final long WINDOW_START = 1_795_251_600_000L;
    private final String executionId = "worker-adapters-" + UUID.randomUUID();
    private PGSimpleDataSource dataSource;

    @AfterEach
    void cleanUpExecution() throws SQLException {
        if (dataSource == null) {
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            for (String table : List.of("window_results", "alerts", "dead_letters", "executions")) {
                String column = table.equals("executions") ? "id" : "execution_id";
                try (var statement = connection.prepareStatement("DELETE FROM " + table + " WHERE " + column + " = ?")) {
                    statement.setString(1, executionId);
                    statement.executeUpdate();
                }
            }
            connection.commit();
        }
    }

    @Test
    void kafkaSourceSeeksAssignedPartitionsAndPreservesTheirOffsetOrder() throws Exception {
        // A private topic keeps absolute offsets deterministic without changing the singleton topic's offsets.
        String topic = "worker-adapters-" + UUID.randomUUID();
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", IntegrationInfrastructure.bootstrap()))) {
            admin.createTopics(List.of(new NewTopic(topic, 6, (short) 1))).all().get(30, TimeUnit.SECONDS);
            try {
                List<ProducerRecord<String, String>> produced = new ArrayList<>();
                List<SourceRecord> expectedOne = new ArrayList<>();
                List<SourceRecord> expectedFour = new ArrayList<>();
                for (int offset = 0; offset < 50; offset++) {
                    for (int partition : List.of(1, 4)) {
                        String value = "event-p" + partition + "-o" + offset;
                        produced.add(new ProducerRecord<>(topic, partition, "truck-" + partition, value));
                        if (partition == 1 && offset >= 10) {
                            expectedOne.add(new SourceRecord(partition, offset, value));
                        } else if (partition == 4) {
                            expectedFour.add(new SourceRecord(partition, offset, value));
                        }
                    }
                }
                produced.add(new ProducerRecord<>(topic, 0, "unassigned", "must-not-be-read"));
                IntegrationInfrastructure.produce(produced);

                try (KafkaRecordSource source = new KafkaRecordSource(IntegrationInfrastructure.bootstrap(), topic, topic)) {
                    source.assign(List.of(1, 4), Map.of(1, 10L, 4, 0L));
                    List<SourceRecord> actual = new ArrayList<>();
                    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                    while (actual.size() < 90 && System.nanoTime() < deadline) {
                        actual.addAll(source.poll(Duration.ofMillis(200)));
                    }
                    assertThat(actual).hasSize(90);
                    assertThat(actual).allSatisfy(record -> assertThat(record.partition()).isIn(1, 4));
                    assertThat(actual.stream().filter(record -> record.partition() == 1).toList())
                            .containsExactlyElementsOf(expectedOne);
                    assertThat(actual.stream().filter(record -> record.partition() == 4).toList())
                            .containsExactlyElementsOf(expectedFour);
                    assertThat(source.endOffsets()).containsExactlyInAnyOrderEntriesOf(Map.of(1, 50L, 4, 50L));
                    assertThat(source.poll(Duration.ofMillis(300))).isEmpty();
                }
            } finally {
                admin.deleteTopics(List.of(topic)).all().get(30, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void postgresSinkUpsertsAllOutputKindsAndReplaysIdempotently() throws Exception {
        PostgresResultSink sink = createExecution();
        List<SinkRow> rows = batch("original");

        assertThat(sink.write(executionId, 3, rows)).isTrue();
        assertStoredBatch(rows);
        List<List<JsonNode>> beforeReplay = snapshot();

        assertThat(sink.write(executionId, 3, rows)).isTrue();
        assertStoredBatch(rows);
        assertThat(snapshot()).isEqualTo(beforeReplay);
    }

    @Test
    void postgresSinkRejectsStaleGenerationWithoutInsertingOrOverwritingRows() throws Exception {
        PostgresResultSink sink = createExecution();
        assertThat(sink.write(executionId, 3, batch("original"))).isTrue();
        List<List<JsonNode>> beforeFencing = snapshot();
        try (Connection connection = dataSource.getConnection()) {
            assertThat(bumpGeneration(connection)).isEqualTo(1);
        }

        assertThat(sink.write(executionId, 3, staleBatch())).isFalse();
        assertThat(snapshot()).isEqualTo(beforeFencing);
        assertGeneration(4);
    }

    @Test
    void generationBumpWaitsForInFlightSinkTransactionAndFencesItsNextBatch() throws Exception {
        PostgresResultSink sink = createExecution();
        assertThat(sink.write(executionId, 3, batch("original"))).isTrue();
        List<List<JsonNode>> beforeWrite = snapshot();
        String sinkApplication = executionId + "-sink";
        PGSimpleDataSource sinkDataSource = newDataSource();
        sinkDataSource.setApplicationName(sinkApplication);
        PostgresResultSink concurrentSink = new PostgresResultSink(sinkDataSource);

        try (var executor = Executors.newFixedThreadPool(2);
             Connection blocker = dataSource.getConnection();
             Connection bumper = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try {
                // Stall the real sink's first upsert, after it has acquired FOR SHARE on executions.
                try (var statement = blocker.prepareStatement(
                        "SELECT 1 FROM window_results WHERE execution_id = ? FOR UPDATE")) {
                    statement.setString(1, executionId);
                    try (var rows = statement.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                    }
                }
                int blockerPid = backendPid(blocker);
                int bumperPid = backendPid(bumper);
                var write = executor.submit(() -> concurrentSink.write(executionId, 3, batch("updated")));
                int sinkPid = awaitSinkBlockedBy(sinkApplication, blockerPid);

                var bump = executor.submit(() -> bumpGeneration(bumper));
                awaitBlockedBy(bumperPid, sinkPid);
                assertThat(write.isDone()).isFalse();
                assertThat(bump.isDone()).isFalse();
                assertGeneration(3);
                assertThat(snapshot()).isEqualTo(beforeWrite);

                blocker.commit();
                assertThat(write.get(15, TimeUnit.SECONDS)).isTrue();
                assertThat(bump.get(15, TimeUnit.SECONDS)).isEqualTo(1);
                assertStoredBatch(batch("updated"));
                assertGeneration(4);
                List<List<JsonNode>> committed = snapshot();
                assertThat(concurrentSink.write(executionId, 3, staleBatch())).isFalse();
                assertThat(snapshot()).isEqualTo(committed);
            } finally {
                // Release the row lock on assertion failure as well, before joining executor threads.
                blocker.rollback();
            }
        }
    }

    private PostgresResultSink createExecution() throws SQLException {
        dataSource = newDataSource();
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "INSERT INTO executions (id, pipeline_id, pipeline_version, plan, state, generation)"
                             + " VALUES (?, 'worker-adapters', 1, '{}'::jsonb, 'RUNNING', 3)")) {
            statement.setString(1, executionId);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
        return new PostgresResultSink(dataSource);
    }

    private static PGSimpleDataSource newDataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(IntegrationInfrastructure.jdbcUrl());
        source.setUser("aether");
        source.setPassword("aether");
        source.setConnectTimeout(10);
        source.setSocketTimeout(30);
        return source;
    }

    private static List<SinkRow> batch(String message) {
        List<SinkRow> rows = new ArrayList<>();
        rows.add(window("speedSink", "truck-007", WINDOW_START, message.equals("original") ? 0.5 : 0.75));
        rows.add(window("otherSpeedSink", "truck-007", WINDOW_START, 1.25));
        rows.add(window("speedSink", "truck-008", WINDOW_START, 32.5));
        rows.add(window("speedSink", "truck-007", WINDOW_START + 60_000, 2.5));
        Alert alert = new Alert("stuck|truck-007|" + WINDOW_START, "truck-007", "stuck-truck", message,
                WINDOW_START + 60_000);
        rows.add(new SinkRow(1, 20, new ListCollector.AlertOutput("alertSink", alert)));
        rows.add(new SinkRow(1, 20, new ListCollector.AlertOutput("otherAlertSink", alert)));
        rows.add(new SinkRow(4, 21, new ListCollector.DeadLetterOutput(new DeadLetter("event-21", DeadLetter.LATE,
                message, 4, 21, WINDOW_START, "{\"late\":true}"))));
        rows.add(new SinkRow(4, 22, new ListCollector.DeadLetterOutput(new DeadLetter("event-21", DeadLetter.REJECTED,
                message, 4, 22, null, null))));
        return rows;
    }

    private static SinkRow window(String sink, String key, long start, double speed) {
        return new SinkRow(1, 20, new ListCollector.WindowOutput(sink,
                new WindowResult("speed1m", key, start, start + 60_000,
                        Map.of("avgSpeedKmh", speed, "count", 30.0), 30)));
    }

    private static List<SinkRow> staleBatch() {
        List<SinkRow> rows = new ArrayList<>(batch("must-not-overwrite"));
        rows.add(window("newSink", "new-truck", WINDOW_START, 99.0));
        rows.add(new SinkRow(4, 99, new ListCollector.AlertOutput("newAlertSink",
                new Alert("new-alert", "new-truck", "rule", "must-not-insert", WINDOW_START))));
        rows.add(new SinkRow(4, 99, new ListCollector.DeadLetterOutput(
                new DeadLetter("new-event", DeadLetter.REJECTED, "must-not-insert", 4, 99, null, "bad JSON"))));
        return rows;
    }

    private void assertStoredBatch(List<SinkRow> batch) throws Exception {
        List<JsonNode> windows = new ArrayList<>();
        List<JsonNode> alerts = new ArrayList<>();
        List<JsonNode> deadLetters = new ArrayList<>();
        for (SinkRow row : batch) {
            var expected = Json.MAPPER.createObjectNode().put("execution_id", executionId).put("generation", 3);
            if (row.output() instanceof ListCollector.WindowOutput output) {
                WindowResult result = output.result();
                expected.put("sink_id", output.sinkNodeId()).put("key", result.key())
                        .put("window_start", result.windowStart()).put("window_end", result.windowEnd())
                        .put("event_count", result.eventCount());
                expected.set("values", Json.MAPPER.valueToTree(result.values()));
                windows.add(Json.MAPPER.readTree(expected.toString()));
            } else if (row.output() instanceof ListCollector.AlertOutput output) {
                Alert alert = output.alert();
                expected.put("sink_id", output.sinkNodeId()).put("alert_id", alert.alertId()).put("key", alert.key())
                        .put("rule_id", alert.ruleId()).put("message", alert.message()).put("event_time", alert.eventTime());
                alerts.add(Json.MAPPER.readTree(expected.toString()));
            } else if (row.output() instanceof ListCollector.DeadLetterOutput output) {
                DeadLetter letter = output.deadLetter();
                expected.put("event_id", letter.eventId()).put("kind", letter.kind()).put("reason", letter.reason())
                        .put("partition", letter.partition()).put("offset", letter.offset())
                        .put("event_time", letter.eventTime()).put("raw", letter.raw());
                deadLetters.add(Json.MAPPER.readTree(expected.toString()));
            }
        }
        List<List<JsonNode>> actual = snapshot();
        assertThat(actual.get(0)).containsExactlyInAnyOrderElementsOf(windows);
        assertThat(actual.get(1)).containsExactlyInAnyOrderElementsOf(alerts);
        assertThat(actual.get(2)).containsExactlyInAnyOrderElementsOf(deadLetters);
    }

    private List<List<JsonNode>> snapshot() throws Exception {
        List<List<JsonNode>> snapshot = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            for (String table : List.of("window_results", "alerts", "dead_letters")) {
                // written_at intentionally changes on successful replay; every persisted business column must match.
                try (var statement = connection.prepareStatement("SELECT to_jsonb(r) - 'written_at' FROM " + table
                        + " r WHERE execution_id = ? ORDER BY (to_jsonb(r) - 'written_at')::text")) {
                    statement.setString(1, executionId);
                    List<JsonNode> values = new ArrayList<>();
                    try (var rows = statement.executeQuery()) {
                        while (rows.next()) {
                            values.add(Json.MAPPER.readTree(rows.getString(1)));
                        }
                    }
                    snapshot.add(values);
                }
            }
        }
        return snapshot;
    }

    private int bumpGeneration(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("UPDATE executions SET generation = 4 WHERE id = ?")) {
            statement.setString(1, executionId);
            statement.setQueryTimeout(20);
            return statement.executeUpdate();
        }
    }

    private void assertGeneration(long expected) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT generation FROM executions WHERE id = ?")) {
            statement.setString(1, executionId);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isEqualTo(expected);
            }
        }
    }

    private static int backendPid(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT pg_backend_pid()")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private int awaitSinkBlockedBy(String application, int blockerPid) throws Exception {
        return awaitBlocked("SELECT pid FROM pg_stat_activity WHERE application_name = ?"
                + " AND ? = ANY(pg_blocking_pids(pid))", application, blockerPid);
    }

    private void awaitBlockedBy(int waiterPid, int blockerPid) throws Exception {
        assertThat(awaitBlocked("SELECT pid FROM pg_stat_activity WHERE pid = ?"
                + " AND ? = ANY(pg_blocking_pids(pid))", waiterPid, blockerPid)).isEqualTo(waiterPid);
    }

    private int awaitBlocked(String sql, Object waiter, int blockerPid) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        try (Connection observer = dataSource.getConnection(); var statement = observer.prepareStatement(sql)) {
            statement.setObject(1, waiter);
            statement.setInt(2, blockerPid);
            do {
                try (var rows = statement.executeQuery()) {
                    if (rows.next()) {
                        return rows.getInt(1);
                    }
                }
                Thread.sleep(20);
            } while (System.nanoTime() < deadline);
        }
        throw new AssertionError("backend " + waiter + " did not block on backend " + blockerPid);
    }
}
