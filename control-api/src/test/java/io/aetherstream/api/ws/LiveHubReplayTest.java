package io.aetherstream.api.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aetherstream.api.LiveSources;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class LiveHubReplayTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void connectionsShareSequenceAndReceiveIdenticalLiveEnvelopes() throws Exception {
        try (Feed feed = new Feed()) {
            Browser first = feed.connect(null);
            assertThat(first.next().path("seq").longValue()).isEqualTo(1);
            Browser second = feed.connect(null);
            JsonNode snapshot = second.next();
            assertThat(snapshot.path("type").asText()).isEqualTo("snapshot");
            assertThat(snapshot.path("seq").longValue()).isEqualTo(2);
            feed.publish("WORKER_DEAD");
            JsonNode event = first.next();
            assertThat(event.path("seq").longValue()).isEqualTo(3);
            assertThat(event.path("data").path("event").asText()).isEqualTo("WORKER_DEAD");
            assertThat(second.next()).isEqualTo(event);
        }
    }

    @Test
    void reconnectReplaysRecoveryWithOriginalSequenceAndTimestampBeforeSnapshot() throws Exception {
        try (Feed feed = new Feed()) {
            Browser original = feed.connect(null);
            long cursor = original.next().path("seq").longValue();
            feed.hub.afterConnectionClosed(original.session, CloseStatus.NORMAL);
            feed.publish("WORKER_DEAD");
            feed.publish("RECOVERING");
            feed.publish("RECOVERED");
            Browser reconnect = feed.connect(cursor);
            List<JsonNode> replay = List.of(reconnect.next(), reconnect.next(), reconnect.next());
            assertThat(replay).extracting(node -> node.path("data").path("event").asText())
                    .containsExactly("WORKER_DEAD", "RECOVERING", "RECOVERED");
            assertThat(replay).extracting(node -> node.path("seq").longValue()).containsExactly(2L, 3L, 4L);
            JsonNode snapshot = reconnect.next();
            assertThat(snapshot.path("type").asText()).isEqualTo("snapshot");
            assertThat(snapshot.path("seq").longValue()).isEqualTo(5);

            Browser again = feed.connect(2L);
            assertThat(again.next()).isEqualTo(replay.get(1));
            assertThat(again.next()).isEqualTo(replay.get(2));
            assertThat(again.next()).isEqualTo(snapshot);
            assertThat(again.next().path("seq").longValue()).isEqualTo(6);
            feed.publish("CHECKPOINT_COMPLETED");
            assertThat(again.next().path("seq").longValue()).isEqualTo(7);
        }
    }

    @Test
    void oldestReplayableCursorReceivesExactlyTheLast500Messages() throws Exception {
        try (Feed feed = new Feed()) {
            for (int i = 1; i <= 501; i++) feed.publish("event-" + i);
            Browser browser = feed.connect(1L);
            for (long seq = 2; seq <= 501; seq++) {
                JsonNode replay = browser.next();
                assertThat(replay.path("seq").longValue()).isEqualTo(seq);
                assertThat(replay.path("data").path("event").asText()).isEqualTo("event-" + seq);
            }
            JsonNode snapshot = browser.next();
            assertThat(snapshot.path("type").asText()).isEqualTo("snapshot");
            assertThat(snapshot.path("seq").longValue()).isEqualTo(502);
        }
    }

    @Test
    void expiredCursorGetsResyncThenSnapshotInsteadOfPartialReplay() throws Exception {
        try (Feed feed = new Feed()) {
            for (int i = 1; i <= 501; i++) feed.publish("event-" + i);
            Browser browser = feed.connect(0L);
            JsonNode resync = browser.next();
            assertThat(resync.path("type").asText()).isEqualTo("resync");
            assertThat(resync.path("seq").longValue()).isEqualTo(502);
            assertThat(resync.path("data").isNull()).isTrue();
            JsonNode snapshot = browser.next();
            assertThat(snapshot.path("type").asText()).isEqualTo("snapshot");
            assertThat(snapshot.path("seq").longValue()).isEqualTo(503);
            feed.publish("RECOVERED");
            assertThat(browser.next().path("seq").longValue()).isEqualTo(504);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {32_000, 64_000})
    void oversizedReplayResyncsWithoutPartialReplayOrBufferClosure(int payloadCharacters) throws Exception {
        try (Feed feed = new Feed()) {
            // Three UTF-8 bytes per character: exercise both the half-budget threshold and a
            // history larger than the whole send buffer, without one oversized individual frame.
            String payload = "\u20ac".repeat(payloadCharacters);
            for (int i = 0; i < 3; i++) feed.publish(payload);
            Browser browser = new Browser("reconnect", "?since=0");
            CountDownLatch sending = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            doAnswer(invocation -> {
                sending.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                browser.messages.add(((TextMessage) invocation.getArgument(0)).getPayload());
                return null;
            }).when(browser.session).sendMessage(any());
            feed.hub.afterConnectionEstablished(browser.session);
            try {
                assertThat(sending.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                release.countDown();
            }
            JsonNode resync = browser.next();
            assertThat(resync.path("type").asText()).isEqualTo("resync");
            assertThat(resync.path("seq").longValue()).isEqualTo(4);
            assertThat(resync.path("data").isNull()).isTrue();
            JsonNode snapshot = browser.next();
            assertThat(snapshot.path("type").asText()).isEqualTo("snapshot");
            assertThat(snapshot.path("seq").longValue()).isEqualTo(5);
            feed.publish("RECOVERED");
            JsonNode live = browser.next();
            assertThat(live.path("data").path("event").asText()).isEqualTo("RECOVERED");
            assertThat(live.path("seq").longValue()).isEqualTo(6);
            verify(browser.session, never()).close(any(CloseStatus.class));
        }
    }

    @Test
    void cursorFromPreviousServerProcessGetsResync() throws Exception {
        try (Feed feed = new Feed()) {
            Browser browser = feed.connect(900L);
            JsonNode resync = browser.next();
            assertThat(resync.path("type").asText()).isEqualTo("resync");
            assertThat(resync.path("seq").longValue()).isEqualTo(1);
            assertThat(browser.next().path("type").asText()).isEqualTo("snapshot");
        }
    }

    @Test
    void eventsPublishedWhileSnapshotLoadsAreReplayedBeforeAtomicLiveCutover() throws Exception {
        try (Feed feed = new Feed()) {
            CountDownLatch loading = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            when(feed.sources.summary(null, 0)).thenAnswer(invocation -> {
                loading.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                return Map.of();
            });
            feed.publish("WORKER_DEAD");
            Browser browser = feed.connect(0L);
            try {
                assertThat(loading.await(5, TimeUnit.SECONDS)).isTrue();
                feed.publish("RECOVERING");
            } finally {
                release.countDown();
            }
            assertThat(browser.next().path("data").path("event").asText()).isEqualTo("WORKER_DEAD");
            assertThat(browser.next().path("data").path("event").asText()).isEqualTo("RECOVERING");
            assertThat(browser.next().path("type").asText()).isEqualTo("snapshot");
            feed.publish("RECOVERED");
            JsonNode live = browser.next();
            assertThat(live.path("data").path("event").asText()).isEqualTo("RECOVERED");
            assertThat(live.path("seq").longValue()).isEqualTo(4);
        }
    }

    @Test
    void twoProducersKeepUniqueIdenticalOrderForFastAndBlockedBrowsers() throws Exception {
        try (Feed feed = new Feed(); var producers = Executors.newFixedThreadPool(2)) {
            Browser slow = feed.connect(null);
            slow.next();
            Browser fast = feed.connect(null);
            fast.next();
            CountDownLatch blocked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            doAnswer(invocation -> {
                blocked.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                slow.messages.add(((TextMessage) invocation.getArgument(0)).getPayload());
                return null;
            }).when(slow.session).sendMessage(any());
            CyclicBarrier start = new CyclicBarrier(2);
            var left = producers.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                for (int i = 0; i < 100; i++) feed.publish("left-" + i);
                return null;
            });
            var right = producers.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                for (int i = 0; i < 100; i++) feed.publish("right-" + i);
                return null;
            });
            List<JsonNode> delivered = new ArrayList<>();
            try {
                assertThat(blocked.await(5, TimeUnit.SECONDS)).isTrue();
                left.get(5, TimeUnit.SECONDS);
                right.get(5, TimeUnit.SECONDS);
                for (long seq = 3; seq <= 202; seq++) {
                    JsonNode event = fast.next();
                    assertThat(event.path("seq").longValue()).isEqualTo(seq);
                    delivered.add(event);
                }
                for (String producer : List.of("left-", "right-")) {
                    assertThat(delivered.stream().map(node -> node.path("data").path("event").asText())
                            .filter(event -> event.startsWith(producer)).toList())
                            .containsExactlyElementsOf(java.util.stream.IntStream.range(0, 100)
                                    .mapToObj(i -> producer + i).toList());
                }
            } finally {
                release.countDown();
            }
            for (JsonNode expected : delivered) assertThat(slow.next()).isEqualTo(expected);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "bad", "9223372036854775808"})
    void invalidCursorClosesWithBadData(String since) throws Exception {
        try (Feed feed = new Feed()) {
            Browser browser = new Browser("invalid", "?since=" + since);
            CountDownLatch closed = new CountDownLatch(1);
            doAnswer(invocation -> {
                assertThat((CloseStatus) invocation.getArgument(0)).isEqualTo(CloseStatus.BAD_DATA);
                closed.countDown();
                return null;
            }).when(browser.session).close(any(CloseStatus.class));
            feed.hub.afterConnectionEstablished(browser.session);
            assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(browser.messages).isEmpty();
        }
    }

    private final class Feed implements AutoCloseable {
        final LiveSources sources = mock(LiveSources.class);
        final LiveHub hub = new LiveHub(sources, mock(IncrementalResultRepository.class), mapper);
        int connections;

        Feed() {
            // Drive publications directly; no timer races in ordering/retention boundary tests.
            ((ScheduledThreadPoolExecutor) ReflectionTestUtils.getField(hub, "scheduler")).shutdownNow();
        }

        Browser connect(Long since) throws Exception {
            Browser browser = new Browser("browser-" + ++connections, since == null ? "" : "?since=" + since);
            hub.afterConnectionEstablished(browser.session);
            return browser;
        }

        void publish(String event) {
            ReflectionTestUtils.invokeMethod(hub, "broadcast", "execution", Map.of("event", event));
        }

        @Override public void close() { hub.close(); }
    }

    private final class Browser {
        final WebSocketSession session = mock(WebSocketSession.class);
        final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();

        Browser(String id, String query) throws Exception {
            when(session.getId()).thenReturn(id);
            when(session.getUri()).thenReturn(URI.create("ws://localhost/ws" + query));
            when(session.isOpen()).thenReturn(true);
            doAnswer(invocation -> {
                messages.add(((TextMessage) invocation.getArgument(0)).getPayload());
                return null;
            }).when(session).sendMessage(any());
        }

        JsonNode next() throws Exception {
            String payload = messages.poll(5, TimeUnit.SECONDS);
            assertThat(payload).as("next ordered WebSocket frame").isNotNull();
            return mapper.readTree(payload);
        }
    }
}
