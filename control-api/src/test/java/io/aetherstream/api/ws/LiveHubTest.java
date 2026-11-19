package io.aetherstream.api.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aetherstream.api.LiveSources;
import io.aetherstream.api.recovery.RecoveryHistory;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class LiveHubTest {
    @Test
    void blockedSnapshotWriterDoesNotBlockAnotherBrowserOrTruckPolls() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        LiveSources sources = mock(LiveSources.class);
        IncrementalResultRepository results = mock(IncrementalResultRepository.class);
        when(sources.cluster()).thenReturn(mapper.readTree("{\"workers\":[]}"));
        when(sources.simulator()).thenReturn(mapper.readTree("{\"simTime\":42,\"trucks\":[]}"));
        when(sources.summary(null, 0)).thenReturn(Map.of());
        WebSocketSession slow = session("slow");
        WebSocketSession fast = session("fast");
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        LinkedBlockingQueue<TextMessage> received = new LinkedBlockingQueue<>();
        doAnswer(invocation -> {
            blocked.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(slow).sendMessage(any());
        doAnswer(invocation -> { received.add(invocation.getArgument(0)); return null; })
                .when(fast).sendMessage(any());
        LiveHub hub = new LiveHub(sources, results, mapper, new RecoveryHistory());
        try {
            hub.afterConnectionEstablished(slow);
            assertThat(blocked.await(1, TimeUnit.SECONDS)).isTrue();
            hub.afterConnectionEstablished(fast);
            TextMessage snapshot = received.poll(1, TimeUnit.SECONDS);
            assertThat(snapshot).isNotNull();
            assertThat(mapper.readTree(snapshot.getPayload()).path("type").asText()).isEqualTo("snapshot");
            TextMessage trucks = received.poll(1, TimeUnit.SECONDS);
            assertThat(trucks).isNotNull();
            assertThat(mapper.readTree(trucks.getPayload()).path("type").asText()).isEqualTo("trucks");
            assertThat(release.getCount()).isEqualTo(1);
        } finally {
            release.countDown();
            hub.close();
        }
    }

    private WebSocketSession session(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
