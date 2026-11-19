package io.aetherstream.api.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aetherstream.api.LiveSources;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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

@SpringBootTest(classes = WebSocketReplayServerTest.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WebSocketReplayServerTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import({LiveHub.class, WebSocketConfig.class, io.aetherstream.api.recovery.RecoveryHistory.class})
    static class Application {}

    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @MockitoBean LiveSources sources;
    @MockitoBean IncrementalResultRepository results;

    @Test
    void recoveryWhileDisconnectedIsReplayedInOrderOverRealWebSocket() throws Exception {
        ObjectNode running = cluster("RUNNING", 1, true);
        AtomicReference<ObjectNode> state = new AtomicReference<>(running);
        LinkedBlockingQueue<JsonNode> observations = new LinkedBlockingQueue<>();
        when(sources.cluster()).thenAnswer(invocation -> {
            ObjectNode current = state.get();
            observations.add(current);
            return current;
        });
        when(sources.execution()).thenAnswer(invocation -> state.get().path("execution"));
        when(sources.currentExecutionId()).thenReturn("replay-exec");
        when(sources.summary(eq("replay-exec"), anyDouble())).thenReturn(Map.of());

        try (HttpClient client = HttpClient.newHttpClient()) {
            Messages original = new Messages();
            WebSocket first = connect(client, original, "");
            WebSocket reconnect = null;
            try {
                JsonNode snapshot = next(original);
                assertThat(snapshot.path("type").asText()).isEqualTo("snapshot");
                assertThat(snapshot.path("seq").longValue()).isEqualTo(1);
                JsonNode started;
                do {
                    started = next(original);
                } while (!started.path("data").path("event").asText().equals("STARTED"));
                long cursor = started.path("seq").longValue();
                first.sendClose(WebSocket.NORMAL_CLOSURE, "reconnect test").get(5, TimeUnit.SECONDS);
                original.closed.get(5, TimeUnit.SECONDS);

                ObjectNode recovering = cluster("RECOVERING", 2, false);
                state.set(recovering);
                awaitTwoPolls(observations, recovering);
                ObjectNode recovered = cluster("RUNNING", 2, false);
                state.set(recovered);
                awaitTwoPolls(observations, recovered);

                Messages replay = new Messages();
                reconnect = connect(client, replay, "?since=" + cursor);
                List<String> events = new ArrayList<>();
                long previous = cursor;
                while (true) {
                    JsonNode frame = next(replay);
                    long seq = frame.path("seq").longValue();
                    assertThat(seq).isGreaterThan(previous);
                    previous = seq;
                    if (frame.path("type").asText().equals("snapshot")) {
                        assertThat(frame.path("data").path("execution").path("state").asText())
                                .isEqualTo("RUNNING");
                        assertThat(frame.path("data").path("execution").path("generation").asLong())
                                .isEqualTo(2);
                        break;
                    }
                    if (frame.path("type").asText().equals("execution")) {
                        String event = frame.path("data").path("event").asText();
                        events.add(event);
                        if (event.equals("WORKER_DEAD")) {
                            assertThat(frame.path("data").path("workerId").asText()).isEqualTo("worker-1");
                        }
                        if (event.equals("RECOVERED")) {
                            assertThat(frame.path("data").path("durationMs").asLong()).isPositive();
                        }
                    }
                }
                assertThat(events).containsExactly("WORKER_DEAD", "RECOVERING", "RECOVERED");
                assertThat(next(replay).path("seq").longValue()).isGreaterThan(previous);
            } finally {
                first.abort();
                if (reconnect != null) reconnect.abort();
            }
        }
    }

    private ObjectNode cluster(String status, int generation, boolean alive) {
        ObjectNode cluster = mapper.createObjectNode();
        cluster.putObject("execution").put("id", "replay-exec").put("state", status)
                .put("generation", generation).put("restoredFromCheckpoint", generation == 1 ? 0 : 3)
                .put("lastCompletedCheckpoint", 3);
        cluster.putArray("workers").addObject().put("workerId", "worker-1").put("alive", alive);
        return cluster;
    }

    private void awaitTwoPolls(LinkedBlockingQueue<JsonNode> observations, JsonNode expected) throws Exception {
        // The next single-flight poll proves the previous observation has been fully published.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        int matches = 0;
        while (matches < 2) {
            JsonNode observed = observations.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            assertThat(observed).as("polling continues with no connected browser").isNotNull();
            if (observed == expected) matches++;
        }
    }

    private WebSocket connect(HttpClient client, Messages messages, String query) throws Exception {
        return client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://localhost:" + port + "/ws" + query), messages)
                .get(5, TimeUnit.SECONDS);
    }

    private JsonNode next(Messages messages) throws Exception {
        String payload = messages.frames.poll(5, TimeUnit.SECONDS);
        assertThat(payload).as("real WebSocket frame").isNotNull();
        return mapper.readTree(payload);
    }

    private static final class Messages implements WebSocket.Listener {
        final LinkedBlockingQueue<String> frames = new LinkedBlockingQueue<>();
        final CompletableFuture<Void> closed = new CompletableFuture<>();
        final StringBuilder fragments = new StringBuilder();

        @Override public void onOpen(WebSocket socket) { socket.request(1); }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            fragments.append(data);
            if (last) {
                frames.add(fragments.toString());
                fragments.setLength(0);
            }
            socket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            closed.complete(null);
            return CompletableFuture.completedFuture(null);
        }
    }
}
