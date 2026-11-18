package io.aetherstream.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aetherstream.api.ws.IncrementalResultRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(classes = ControlApiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class WebSocketServerTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @MockitoBean CoordinatorClient coordinator;
    @MockitoBean SimulatorClient simulator;
    @MockitoBean ResultRepository results;
    @MockitoBean IncrementalResultRepository incremental;
    @MockitoBean JdbcTemplate jdbc;

    @Test
    void standardClientReceivesSnapshotWithinThreeSecondsAndLiveUpdates() throws Exception {
        JsonNode cluster = mapper.readTree("""
                {"execution":{"id":"exec-1","state":"RUNNING","generation":1,"lastCompletedCheckpoint":2},
                 "workers":[{"workerId":"w-1","alive":true,"partitions":[0,1],"recordsPerSec":40.0}]}
                """);
        JsonNode simulatorState = mapper.readTree("""
                {"running":true,"simTime":1234,"trucks":[{"truckId":"truck-001","x":10,"y":20}]}
                """);
        Map<String, Object> execution = Map.of("id", "exec-1", "pipelineId", "demo", "pipelineVersion", 1,
                "state", "RUNNING", "generation", 1, "lastCompletedCheckpoint", 2, "createdAt", "2026-11-19T00:00:00Z");
        when(coordinator.state()).thenReturn(cluster);
        when(simulator.state()).thenReturn(simulatorState);
        when(results.currentExecutionId()).thenReturn("exec-1");
        when(results.summary("exec-1", 40.0)).thenReturn(Map.of("executionId", "exec-1", "windowsWritten", 7));
        when(jdbc.query(startsWith("SELECT id,pipeline_id"), org.mockito.ArgumentMatchers.<RowMapper<Map<String, Object>>>any(), eq("exec-1")))
                .thenReturn(List.of(execution));
        when(incremental.counts("exec-1")).thenReturn(new IncrementalResultRepository.Counts(0, 0));
        Messages messages = new Messages();
        try (HttpClient client = HttpClient.newHttpClient()) {
            WebSocket socket = connect(client, messages);
            try {
                JsonNode snapshot = mapper.readTree(messages.next());
                assertThat(snapshot.path("type").asText()).isEqualTo("snapshot");
                assertThat(snapshot.path("ts").asLong()).isPositive();
                assertThat(snapshot.path("data").path("execution").path("pipelineVersion").asInt()).isEqualTo(1);
                assertThat(snapshot.path("data").path("workers")).isEqualTo(cluster.path("workers"));
                assertThat(snapshot.path("data").path("summary").path("windowsWritten").asInt()).isEqualTo(7);
                assertThat(snapshot.path("data").path("simulator")).isEqualTo(simulatorState);
                JsonNode trucks = nextType(messages, "trucks");
                assertThat(trucks.path("data").path("simTime").asLong()).isEqualTo(1234);
                JsonNode flow = nextType(messages, "flow");
                assertThat(flow.path("data").path("eventsPerSec").asDouble()).isEqualTo(40.0);
                assertThat(flow.path("data").path("byPartition").path("0").asDouble()).isEqualTo(20.0);
                assertThat(flow.path("data").path("byPartition").path("1").asDouble()).isEqualTo(20.0);
            } finally { socket.abort(); }
        }
    }

    @Test
    void failedUpstreamsProduceNullSnapshotPartsAndPollingRecovers() throws Exception {
        when(coordinator.state()).thenThrow(new UpstreamException("offline"));
        when(simulator.state()).thenThrow(new UpstreamException("offline"));
        when(results.currentExecutionId()).thenThrow(new IllegalStateException("offline"));
        when(results.summary(any(), org.mockito.ArgumentMatchers.anyDouble())).thenThrow(new IllegalStateException("offline"));
        Messages messages = new Messages();
        try (HttpClient client = HttpClient.newHttpClient()) {
            WebSocket socket = connect(client, messages);
            try {
                JsonNode snapshot = mapper.readTree(messages.next());
                assertThat(snapshot.path("type").asText()).isEqualTo("snapshot");
                for (String part : List.of("execution", "workers", "summary", "simulator")) {
                    assertThat(snapshot.path("data").path(part).isNull()).as(part).isTrue();
                }
                // Override throwing stubs without invoking them while arranging recovery.
                org.mockito.Mockito.doReturn(mapper.readTree("{\"simTime\":5678,\"trucks\":[]}"))
                        .when(simulator).state();
                assertThat(nextType(messages, "trucks").path("data").path("simTime").asLong()).isEqualTo(5678);
            } finally { socket.abort(); }
        }
    }

    private WebSocket connect(HttpClient client, Messages messages) throws Exception {
        return client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(3))
                .header("Origin", "https://external.example")
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), messages).get(3, TimeUnit.SECONDS);
    }

    private JsonNode nextType(Messages messages, String type) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            String text = messages.received.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            assertThat(text).as("message of type %s", type).isNotNull();
            JsonNode message = mapper.readTree(text);
            if (type.equals(message.path("type").asText())) return message;
        }
        throw new AssertionError("No " + type + " message within three seconds");
    }

    private static final class Messages implements WebSocket.Listener {
        final LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();
        final StringBuilder fragments = new StringBuilder();

        String next() throws InterruptedException {
            String message = received.poll(3, TimeUnit.SECONDS);
            assertThat(message).as("snapshot within three seconds").isNotNull();
            return message;
        }

        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            fragments.append(data);
            if (last) {
                received.add(fragments.toString());
                fragments.setLength(0);
            }
            socket.request(1);
            return CompletableFuture.completedFuture(null);
        }
    }
}
