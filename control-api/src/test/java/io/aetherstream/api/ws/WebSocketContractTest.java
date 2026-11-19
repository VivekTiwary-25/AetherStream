package io.aetherstream.api.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aetherstream.api.LiveSources;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(classes = WebSocketContractTest.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class WebSocketContractTest {
    private static final String EXECUTION_ID = "exec-contract";
    private static final long SIM_TIME = 1_795_251_600_000L;
    private static final Duration RECEIVE_TIMEOUT = Duration.ofSeconds(6);

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import({LiveHub.class, WebSocketConfig.class})
    static class Application {}

    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @MockitoBean LiveSources sources;
    @MockitoBean IncrementalResultRepository results;

    private final AtomicReference<ObjectNode> execution = new AtomicReference<>();
    private final AtomicReference<ArrayNode> workers = new AtomicReference<>();
    private JsonNode simulator;
    private Map<String, Object> summary;
    private long connectedAfter;

    @BeforeEach
    void arrangeOnlyMockedUpstreams() throws Exception {
        execution.set(execution("RUNNING", 1, null, 3));
        workers.set(workers(true));
        simulator = mapper.readTree("""
                {"running":true,"timeScale":10,"simTime":1795251600000,
                 "trucks":[{"truckId":"truck-001","x":412.5,"y":980.0,"speedKmh":31.4,
                            "heading":90.0,"status":"DRIVING","routeId":"r-3"}],
                 "stops":[{"stopId":"stop-12","x":300.0,"y":700.0}],
                 "depot":{"x":1000,"y":1000},"emitted":12345}
                """);
        summary = Map.of("executionId", EXECUTION_ID, "windowsWritten", 7L, "alerts", 1L,
                "rejected", 2L, "late", 1L, "deliveriesLastMinute", 3L,
                "avgSpeedLastMinute", 31.4, "eventsPerSec", 40.0);
        when(sources.cluster()).thenAnswer(invocation -> {
            ObjectNode cluster = mapper.createObjectNode();
            cluster.set("execution", execution.get());
            cluster.set("workers", workers.get());
            return cluster;
        });
        when(sources.execution()).thenAnswer(invocation -> execution.get());
        when(sources.currentExecutionId()).thenReturn(EXECUTION_ID);
        when(sources.simulator()).thenReturn(simulator);
        when(sources.summary(eq(EXECUTION_ID), anyDouble())).thenReturn(summary);
        when(results.counts(EXECUTION_ID)).thenReturn(new IncrementalResultRepository.Counts(2, 1));
        when(results.windows(eq(EXECUTION_ID), any(Instant.class))).thenReturn(List.of());
        when(results.alerts(eq(EXECUTION_ID), any(Instant.class))).thenReturn(List.of());
        when(results.deadLetters(eq(EXECUTION_ID), any(Instant.class))).thenReturn(List.of());
    }

    @Test
    void plainWebSocketHandshakeSendsSnapshotFirstAndAllLiveMessageShapes() throws Exception {
        Map<String, Object> window = Map.of("sinkId", "speedSink", "key", "truck-001",
                "windowStart", SIM_TIME, "windowEnd", SIM_TIME + 60_000,
                "values", Map.of("avgSpeedKmh", 31.4, "count", 2.0), "eventCount", 2L);
        Map<String, Object> alert = Map.of("alertId", "stuck|truck-001|0", "key", "truck-001",
                "ruleId", "stuck-truck", "message", "truck-001 has not moved for 10 min",
                "eventTime", SIM_TIME + 60_000);
        Map<String, Object> late = Map.of("eventId", "late-1", "kind", "LATE", "reason", "window already closed",
                "partition", 0, "offset", 14L, "eventTime", SIM_TIME);
        Map<String, Object> rejected = Map.of("eventId", "bad-1", "kind", "REJECTED", "reason", "unknown type",
                "partition", 1, "offset", 15L, "eventTime", SIM_TIME);
        when(results.windows(eq(EXECUTION_ID), any(Instant.class)))
                .thenReturn(List.of(row(List.of(EXECUTION_ID, "speedSink", "truck-001", SIM_TIME), window)));
        when(results.alerts(eq(EXECUTION_ID), any(Instant.class)))
                .thenReturn(List.of(row(List.of(EXECUTION_ID, "alertSink", "stuck|truck-001|0"), alert)));
        when(results.deadLetters(eq(EXECUTION_ID), any(Instant.class))).thenReturn(List.of(
                row(List.of(EXECUTION_ID, "late-1", "LATE"), late),
                row(List.of(EXECUTION_ID, "bad-1", "REJECTED"), rejected)));

        Messages messages = new Messages();
        try (HttpClient client = HttpClient.newHttpClient()) {
            WebSocket socket = connect(client, messages);
            try {
                assertThat(socket.getSubprotocol()).as("plain WebSocket, not STOMP").isEmpty();
                JsonNode snapshot = next(messages);
                assertThat(snapshot.path("type").textValue()).isEqualTo("snapshot");
                JsonNode data = snapshot.path("data");
                fields(data, "execution", "workers", "summary", "simulator");
                assertExecution(data.path("execution"));
                assertThat(data.path("execution")).isEqualTo(mapper.readTree(execution.get().toString()));
                assertWorkers(data.path("workers"));
                assertThat(data.path("summary")).isEqualTo(mapper.readTree(mapper.writeValueAsString(summary)));
                text(data.path("summary"), "executionId");
                integers(data.path("summary"), "windowsWritten", "alerts", "rejected", "late", "deliveriesLastMinute");
                numbers(data.path("summary"), "avgSpeedLastMinute", "eventsPerSec");
                assertThat(data.path("simulator")).isEqualTo(simulator);

                Set<String> expected = Set.of("trucks", "flow", "windows", "alerts", "deadLetters", "workers", "execution");
                Map<String, JsonNode> received = new HashMap<>();
                long deadline = System.nanoTime() + RECEIVE_TIMEOUT.toNanos();
                while (!received.keySet().containsAll(expected)) {
                    JsonNode message = next(messages, deadline);
                    received.putIfAbsent(message.path("type").textValue(), message.path("data"));
                }
                JsonNode trucks = received.get("trucks");
                integers(trucks, "simTime");
                assertThat(trucks.path("simTime").longValue()).isEqualTo(SIM_TIME);
                assertThat(trucks.path("trucks").isArray()).isTrue();
                JsonNode truck = trucks.path("trucks").get(0);
                text(truck, "truckId", "status");
                numbers(truck, "x", "y", "speedKmh", "heading");
                assertThat(trucks.path("trucks")).isEqualTo(simulator.path("trucks"));

                JsonNode flow = received.get("flow");
                numbers(flow, "eventsPerSec", "rejectedPerSec", "latePerSec");
                assertThat(flow.path("eventsPerSec").doubleValue()).isEqualTo(40.0);
                assertThat(flow.path("byPartition").isObject()).isTrue();
                numbers(flow.path("byPartition"), "0", "1");
                assertThat(flow.path("byPartition").path("0").doubleValue()).isEqualTo(20.0);
                assertThat(flow.path("byPartition").path("1").doubleValue()).isEqualTo(20.0);
                assertThat(flow.path("rejectedPerSec").doubleValue()).isZero();
                assertThat(flow.path("latePerSec").doubleValue()).isZero();

                JsonNode windows = received.get("windows");
                assertThat(windows.isArray()).isTrue();
                assertThat(windows.size()).isEqualTo(1);
                text(windows.get(0), "sinkId", "key");
                integers(windows.get(0), "windowStart", "windowEnd", "eventCount");
                assertThat(windows.get(0).path("values").isObject()).isTrue();
                numbers(windows.get(0).path("values"), "avgSpeedKmh", "count");
                assertThat(windows).isEqualTo(mapper.readTree(mapper.writeValueAsString(List.of(window))));

                JsonNode alerts = received.get("alerts");
                assertThat(alerts.isArray()).isTrue();
                assertThat(alerts.size()).isEqualTo(1);
                text(alerts.get(0), "alertId", "key", "ruleId", "message");
                integers(alerts.get(0), "eventTime");
                assertThat(alerts).isEqualTo(mapper.readTree(mapper.writeValueAsString(List.of(alert))));

                JsonNode deadLetters = received.get("deadLetters");
                assertThat(deadLetters.isArray()).isTrue();
                assertThat(deadLetters.size()).isEqualTo(2);
                for (JsonNode deadLetter : deadLetters) {
                    text(deadLetter, "eventId", "kind", "reason");
                    integers(deadLetter, "partition", "offset", "eventTime");
                    assertThat(deadLetter.path("kind").textValue()).isIn("LATE", "REJECTED");
                }
                assertThat(deadLetters).isEqualTo(mapper.readTree(mapper.writeValueAsString(List.of(late, rejected))));
                assertWorkers(received.get("workers"));
                assertEvent(received.get("execution"), "STARTED", execution.get());

                // Unchanged sources still produce periodic truck and worker messages.
                await(messages, message -> message.path("type").asText().equals("trucks"));
                await(messages, message -> message.path("type").asText().equals("workers"));
            } finally {
                socket.abort();
            }
        }
    }

    @Test
    void snapshotAllowsNoCurrentExecution() throws Exception {
        execution.set(null);
        when(sources.currentExecutionId()).thenReturn(null);
        Map<String, Object> emptySummary = Map.of("windowsWritten", 0L, "alerts", 0L);
        when(sources.summary(null, 40.0)).thenReturn(emptySummary);
        Messages messages = new Messages();
        try (HttpClient client = HttpClient.newHttpClient()) {
            WebSocket socket = connect(client, messages);
            try {
                JsonNode snapshot = next(messages);
                assertThat(snapshot.path("type").textValue()).isEqualTo("snapshot");
                assertThat(snapshot.path("data").path("execution").isNull()).isTrue();
                assertWorkers(snapshot.path("data").path("workers"));
                assertThat(snapshot.path("data").path("simulator")).isEqualTo(simulator);
            } finally {
                socket.abort();
            }
        }
    }

    @Test
    void broadcastsEveryExecutionEventWithExecutionInfoAndRelevantIdentifiers() throws Exception {
        Messages messages = new Messages();
        try (HttpClient client = HttpClient.newHttpClient()) {
            WebSocket socket = connect(client, messages);
            try {
                assertThat(next(messages).path("type").textValue()).isEqualTo("snapshot");
                assertEvent(awaitEvent(messages, "STARTED"), "STARTED", execution.get());

                execution.set(execution("RUNNING", 1, null, 4));
                JsonNode checkpoint = awaitEvent(messages, "CHECKPOINT_COMPLETED");
                assertEvent(checkpoint, "CHECKPOINT_COMPLETED", execution.get());
                integers(checkpoint, "checkpointId");
                assertThat(checkpoint.path("checkpointId").longValue()).isEqualTo(4);

                workers.set(workers(false));
                JsonNode death = awaitEvent(messages, "WORKER_DEAD");
                assertEvent(death, "WORKER_DEAD", execution.get());
                text(death, "workerId");
                assertThat(death.path("workerId").textValue()).isEqualTo("w-1");

                execution.set(execution("RECOVERING", 2, 4L, 4));
                JsonNode recovering = awaitEvent(messages, "RECOVERING");
                assertEvent(recovering, "RECOVERING", execution.get());
                integers(recovering, "checkpointId");
                assertThat(recovering.path("checkpointId").longValue()).isEqualTo(4);

                execution.set(execution("RUNNING", 2, 4L, 4));
                JsonNode recovered = awaitEvent(messages, "RECOVERED");
                assertEvent(recovered, "RECOVERED", execution.get());
                integers(recovered, "checkpointId", "durationMs");
                assertThat(recovered.path("checkpointId").longValue()).isEqualTo(4);
                assertThat(recovered.path("durationMs").longValue()).isNotNegative();

                workers.set(workers(true));
                // Wait for re-registration before the generation change, as in the live rejoin.
                await(messages, message -> message.path("type").asText().equals("workers")
                        && message.path("data").get(0).path("alive").asBoolean());
                execution.set(execution("RECOVERING", 3, 4L, 4));
                JsonNode rebalanced = awaitEvent(messages, "REBALANCED");
                assertEvent(rebalanced, "REBALANCED", execution.get());
                assertThat(rebalanced.path("workerId").asText()).isEqualTo("w-1");
                assertThat(rebalanced.path("oldGeneration").asLong()).isEqualTo(2);
                assertThat(rebalanced.path("newGeneration").asLong()).isEqualTo(3);
                assertThat(rebalanced.has("durationMs")).isFalse();
                execution.set(execution("RUNNING", 3, 4L, 5));
                long deadline = System.nanoTime() + RECEIVE_TIMEOUT.toNanos();
                while (true) {
                    JsonNode message = next(messages, deadline);
                    if (!message.path("type").asText().equals("execution")) continue;
                    JsonNode event = message.path("data");
                    assertThat(event.path("event").asText())
                            .isNotIn("RECOVERING", "RECOVERED", "REBALANCED");
                    if (event.path("event").asText().equals("CHECKPOINT_COMPLETED")) break;
                }
                execution.set(execution("STOPPED", 3, 4L, 5));
                assertEvent(awaitEvent(messages, "STOPPED"), "STOPPED", execution.get());
            } finally {
                socket.abort();
            }
        }
    }

    @Test
    void recoveredDurationUsesServerClockFromFirstDeathNotSimulatorOrRecoveryEntry() {
        AtomicLong serverNanos = new AtomicLong(TimeUnit.MILLISECONDS.toNanos(100));
        ExecutionEvents events = new ExecutionEvents(mapper, serverNanos::get);
        events.observe(execution("RUNNING", 1, null, 4), workers(true));
        serverNanos.set(TimeUnit.MILLISECONDS.toNanos(1_000));
        assertThat(events.observe(execution("RUNNING", 1, null, 4), workers(false)))
                .extracting(event -> event.path("event").textValue()).containsExactly("WORKER_DEAD");
        serverNanos.set(TimeUnit.MILLISECONDS.toNanos(2_500));
        events.observe(execution("RECOVERING", 2, 4L, 4), workers(false));
        serverNanos.set(TimeUnit.MILLISECONDS.toNanos(7_125));
        ObjectNode running = execution("RUNNING", 2, 4L, 4);
        // An upstream value must not replace the elapsed time measured by this server.
        running.put("durationMs", 999_999);
        List<ObjectNode> recovered = events.observe(running, workers(false));
        assertThat(recovered).hasSize(1);
        integers(recovered.getFirst(), "durationMs");
        assertThat(recovered.getFirst().path("event").textValue()).isEqualTo("RECOVERED");
        assertThat(recovered.getFirst().path("durationMs").longValue()).isEqualTo(6_125);
    }

    @Test
    void lateCommitBehindTheCursorIsDeliveredOverTheSocketExactlyOnce() throws Exception {
        Instant writtenAt = Instant.parse("2026-11-19T00:00:00Z");
        Map<String, Object> rejected = Map.of("eventId", "bad-1", "kind", "REJECTED",
                "reason", "unknown type", "partition", 1, "offset", 15L, "eventTime", SIM_TIME);
        Map<String, Object> late = Map.of("eventId", "late-commit", "kind", "LATE",
                "reason", "window already closed", "partition", 0, "offset", 16L, "eventTime", SIM_TIME);
        var first = new IncrementalResultRepository.Row(writtenAt,
                List.of(EXECUTION_ID, "bad-1", "REJECTED"), rejected);
        var committedLater = new IncrementalResultRepository.Row(writtenAt.minusSeconds(30),
                List.of(EXECUTION_ID, "late-commit", "LATE"), late);
        AtomicReference<List<IncrementalResultRepository.Row>> committed =
                new AtomicReference<>(List.of(first));
        when(results.deadLetters(eq(EXECUTION_ID), any(Instant.class))).thenAnswer(invocation -> {
            Instant since = invocation.getArgument(1);
            return committed.get().stream().filter(row -> !row.writtenAt().isBefore(since)).toList();
        });

        Messages messages = new Messages();
        try (HttpClient client = HttpClient.newHttpClient()) {
            WebSocket socket = connect(client, messages);
            try {
                JsonNode initial = await(messages, message -> message.path("type").asText().equals("deadLetters"));
                assertThat(initial.path("data").get(0).path("eventId").asText()).isEqualTo("bad-1");
                // This row becomes visible only after a newer written_at has advanced the cursor.
                committed.set(List.of(committedLater, first));
                JsonNode delivered = await(messages, message -> message.path("type").asText().equals("deadLetters"));
                assertThat(delivered.path("data"))
                        .isEqualTo(mapper.readTree(mapper.writeValueAsString(List.of(late))));

                int polls = 0;
                long deadline = System.nanoTime() + RECEIVE_TIMEOUT.toNanos();
                while (polls < 3) {
                    JsonNode message = next(messages, deadline);
                    assertThat(message.path("type").asText()).isNotEqualTo("deadLetters");
                    if (message.path("type").asText().equals("flow")) polls++;
                }
            } finally {
                socket.abort();
            }
        }
    }

    private WebSocket connect(HttpClient client, Messages messages) throws Exception {
        connectedAfter = System.currentTimeMillis();
        return client.newWebSocketBuilder().connectTimeout(RECEIVE_TIMEOUT)
                .header("Origin", "https://contract-client.example")
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), messages)
                .get(RECEIVE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private JsonNode next(Messages messages) throws Exception {
        return next(messages, System.nanoTime() + RECEIVE_TIMEOUT.toNanos());
    }

    private JsonNode next(Messages messages, long deadline) throws Exception {
        long remaining = deadline - System.nanoTime();
        assertThat(remaining).as("message before receive deadline").isPositive();
        String payload = messages.received.poll(remaining, TimeUnit.NANOSECONDS);
        assertThat(payload).as("server WebSocket message").isNotNull();
        JsonNode message = mapper.readTree(payload);
        fields(message, "type", "ts", "data");
        text(message, "type");
        integers(message, "ts");
        assertThat(message.path("ts").longValue()).isBetween(connectedAfter, System.currentTimeMillis());
        return message;
    }

    private JsonNode await(Messages messages, Predicate<JsonNode> matches) throws Exception {
        long deadline = System.nanoTime() + RECEIVE_TIMEOUT.toNanos();
        while (true) {
            JsonNode message = next(messages, deadline);
            if (matches.test(message)) return message;
        }
    }

    private JsonNode awaitEvent(Messages messages, String event) throws Exception {
        return await(messages, message -> message.path("type").asText().equals("execution")
                && message.path("data").path("event").asText().equals(event)).path("data");
    }

    private ObjectNode execution(String state, long generation, Long restored, long checkpoint) {
        ObjectNode node = mapper.createObjectNode().put("id", EXECUTION_ID)
                .put("pipelineId", "logistics-demo").put("pipelineVersion", 1).put("state", state)
                .put("generation", generation).put("lastCompletedCheckpoint", checkpoint)
                .put("createdAt", "2026-11-19T00:00:00Z");
        if (restored == null) node.putNull("restoredFromCheckpoint");
        else node.put("restoredFromCheckpoint", restored);
        return node;
    }

    private ArrayNode workers(boolean alive) {
        ObjectNode worker = mapper.createObjectNode().put("workerId", "w-1").put("alive", alive)
                .put("state", alive ? "RUNNING" : "DEAD").put("backpressured", false)
                .put("recordsPerSec", 40.0).put("lastHeartbeatMs", SIM_TIME);
        worker.putArray("partitions").add(0).add(1);
        worker.putObject("sourceQueue").put("size", 3).put("capacity", 1000);
        worker.putObject("sinkQueue").put("size", 0).put("capacity", 1000);
        return mapper.createArrayNode().add(worker);
    }

    private static IncrementalResultRepository.Row row(List<Object> key, Map<String, Object> data) {
        return new IncrementalResultRepository.Row(Instant.parse("2026-11-19T00:00:00Z"), key, data);
    }

    private static void assertExecution(JsonNode node) {
        text(node, "id", "pipelineId", "state", "createdAt");
        integers(node, "pipelineVersion", "generation", "lastCompletedCheckpoint");
        assertThat(node.has("restoredFromCheckpoint")).isTrue();
        assertThat(node.path("restoredFromCheckpoint").isNull()
                || node.path("restoredFromCheckpoint").isIntegralNumber()).isTrue();
        assertThat(node.path("state").textValue()).isIn("CREATED", "RUNNING", "RECOVERING", "STOPPED", "FAILED");
    }

    private void assertEvent(JsonNode actual, String event, JsonNode expectedExecution) throws Exception {
        assertExecution(actual);
        text(actual, "event");
        assertThat(actual.path("event").textValue()).isEqualTo(event);
        JsonNode wireExecution = mapper.readTree(expectedExecution.toString());
        wireExecution.fields().forEachRemaining(field ->
                assertThat(actual.get(field.getKey())).as(field.getKey()).isEqualTo(field.getValue()));
    }

    private static void assertWorkers(JsonNode nodes) {
        assertThat(nodes.isArray()).isTrue();
        assertThat(nodes.size()).isEqualTo(1);
        for (JsonNode worker : nodes) {
            text(worker, "workerId", "state");
            numbers(worker, "recordsPerSec");
            integers(worker, "lastHeartbeatMs");
            assertThat(worker.path("alive").isBoolean()).isTrue();
            assertThat(worker.path("backpressured").isBoolean()).isTrue();
            assertThat(worker.path("partitions").isArray()).isTrue();
            for (JsonNode partition : worker.path("partitions")) assertThat(partition.isIntegralNumber()).isTrue();
            for (String queue : List.of("sourceQueue", "sinkQueue")) integers(worker.path(queue), "size", "capacity");
        }
    }

    private static void fields(JsonNode node, String... names) {
        assertThat(node.isObject()).isTrue();
        for (String name : names) assertThat(node.has(name)).as("field %s", name).isTrue();
    }

    private static void text(JsonNode node, String... names) {
        fields(node, names);
        for (String name : names) assertThat(node.path(name).isTextual()).as("string %s", name).isTrue();
    }

    private static void integers(JsonNode node, String... names) {
        fields(node, names);
        for (String name : names) assertThat(node.path(name).isIntegralNumber()).as("integer %s", name).isTrue();
    }

    private static void numbers(JsonNode node, String... names) {
        fields(node, names);
        for (String name : names) assertThat(node.path(name).isNumber()).as("number %s", name).isTrue();
    }

    private static final class Messages implements WebSocket.Listener {
        private final LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();
        private final StringBuilder fragments = new StringBuilder();

        @Override
        public void onOpen(WebSocket socket) {
            socket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence text, boolean last) {
            fragments.append(text);
            if (last) {
                received.add(fragments.toString());
                fragments.setLength(0);
            }
            socket.request(1);
            return CompletableFuture.completedFuture(null);
        }
    }
}
