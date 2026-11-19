package io.aetherstream.api.glorious;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.WakeupException;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class PipelineRunsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOPIC = "truck-events";
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 2);

    private static JsonNode pipeline() throws Exception {
        return MAPPER.readTree("""
                {"name":"Observe","nodes":[{"id":"o","type":"output","params":{"name":"Live"}}],"edges":[]}
                """);
    }

    private static ConsumerRecord<String, String> record(int offset) {
        return new ConsumerRecord<>(TOPIC, 2, offset, "t", """
                {"type":"truck.position","eventTime":10000,"payload":{"truckId":"t","routeId":"r", "status":"DRIVING",
                  "speedKmh":65,"x":1,"y":2,"heading":90,"partition":5}}
                """);
    }

    @Test void realMetadataOverridesPayloadAndMalformedOrDeliveryEventsAreSkipped() {
        PipelineRuns runs = new PipelineRuns(MAPPER, () -> null, TOPIC, () -> 10000);
        assertThat(runs.position(record(1))).containsEntry("partition", 2).containsEntry("speedKmh", 65.0)
                .containsEntry("eventTime", 10000L);
        for (String value : List.of("not JSON", "null", "{\"type\":\"truck.delivery\"}",
                record(1).value().replace("\"speedKmh\":65,", ""))) {
            assertThat(runs.position(new ConsumerRecord<>(TOPIC, 2, 1, "t", value))).isNull();
        }
    }

    @Test void buffersLast500ResultsWithMonotonicCursorAndCountsDrops() throws Exception {
        Feed feed = new Feed();
        AtomicLong now = new AtomicLong(10000);
        PipelineRuns runs = new PipelineRuns(MAPPER, () -> feed.consumer, TOPIC, now::get);
        String id = runs.start(pipeline());
        try {
            for (int batch = 0; batch < 26; batch++) {
                now.set(10000L + batch * 1000L);
                List<ConsumerRecord<String, String>> rows = new ArrayList<>();
                for (int i = 0; i < 21; i++) rows.add(record(batch * 21 + i));
                feed.send(rows);
            }
            PipelineRuns.Snapshot snapshot = runs.results(id, 0);
            assertThat(snapshot.eventsIn()).isEqualTo(546);
            assertThat(snapshot.droppedRows()).isEqualTo(26);
            assertThat(snapshot.results()).hasSize(500);
            assertThat(snapshot.results().getFirst().seq()).isEqualTo(21);
            assertThat(snapshot.results().getLast().seq()).isEqualTo(520);
            assertThat(runs.results(id, 519).results()).extracting(PipelineRuns.Result::seq).containsExactly(520L);
        } finally {
            runs.shutdown();
        }
        assertThat(runs.results(id, 0).state()).isEqualTo("STOPPED");
        verify(feed.consumer).close(Duration.ofSeconds(2));
    }

    @Test void startingAnotherRunClosesPreviousConsumerBeforeOpeningNext() throws Exception {
        Feed first = new Feed();
        Feed second = new Feed();
        AtomicInteger opened = new AtomicInteger();
        PipelineRuns runs = new PipelineRuns(MAPPER, () -> {
            if (opened.getAndIncrement() == 0) return first.consumer;
            assertThat(first.closed.getCount()).isZero();
            return second.consumer;
        }, TOPIC, () -> 10000);
        String one = runs.start(pipeline());
        first.send(List.of(record(1)));
        String two = runs.start(pipeline());
        try {
            second.send(List.of(record(2)));
            assertThat(runs.results(one, 0).state()).isEqualTo("STOPPED");
            assertThat(runs.results(two, 0).state()).isEqualTo("RUNNING");
            assertThat(runs.results(two, 0).eventsIn()).isEqualTo(1);
        } finally {
            runs.shutdown();
        }
        assertThatThrownBy(() -> runs.results("missing", 0)).isInstanceOf(ResponseStatusException.class);
    }

    @Test void unreachableKafkaFailsRunInsteadOfClaimingEmptySuccess() throws Exception {
        Feed feed = new Feed();
        when(feed.consumer.partitionsFor(eq(TOPIC), any(Duration.class))).thenThrow(new TimeoutException("broker unavailable"));
        PipelineRuns runs = new PipelineRuns(MAPPER, () -> feed.consumer, TOPIC, () -> 10000);
        String id = runs.start(pipeline());
        assertThat(feed.closed.await(5, TimeUnit.SECONDS)).isTrue();
        PipelineRuns.Snapshot failed = runs.results(id, 0);
        assertThat(failed.state()).isEqualTo("FAILED");
        assertThat(failed.error()).contains("Kafka", "broker unavailable");
        assertThat(failed.eventsIn()).isZero();
        runs.shutdown();
    }

    /** Queue barriers observe completed batches without sleeps, brokers, or timing races. */
    private static final class Feed {
        final Consumer<String, String> consumer;
        final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
        final CountDownLatch closed = new CountDownLatch(1);

        @SuppressWarnings("unchecked")
        Feed() {
            consumer = mock(Consumer.class);
            when(consumer.partitionsFor(eq(TOPIC), any(Duration.class)))
                    .thenReturn(List.of(new PartitionInfo(TOPIC, 2, null, null, null)));
            when(consumer.listTopics(any(Duration.class))).thenReturn(Map.of(TOPIC, List.of()));
            when(consumer.poll(any(Duration.class))).thenAnswer(invocation -> {
                Object item = queue.take();
                if (item instanceof WakeupException wakeup) throw wakeup;
                if (item instanceof CountDownLatch latch) {
                    latch.countDown();
                    return ConsumerRecords.empty();
                }
                return item;
            });
            doAnswer(invocation -> { queue.add(new WakeupException()); return null; }).when(consumer).wakeup();
            doAnswer(invocation -> { closed.countDown(); return null; }).when(consumer).close(any(Duration.class));
        }

        void send(List<ConsumerRecord<String, String>> rows) throws Exception {
            queue.add(new ConsumerRecords<>(Map.of(PARTITION, rows)));
            CountDownLatch processed = new CountDownLatch(1);
            queue.add(processed);
            assertThat(processed.await(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
