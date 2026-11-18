package io.aetherstream.simulator;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Test;

class KafkaEventTimeTest {
    private static final String TOPIC = "truck-events";
    private static final TopicPartition P0 = new TopicPartition(TOPIC, 0);
    private static final TopicPartition P1 = new TopicPartition(TOPIC, 1);
    private static final TopicPartition P2 = new TopicPartition(TOPIC, 2);

    @Test
    void readsOnlyPartitionTailsAndSkipsRetainedButEmptyPartitions() {
        try (MockConsumer<String, String> consumer = consumer()) {
            consumer.updateBeginningOffsets(Map.of(P0, 10L, P1, 20L, P2, 30L));
            consumer.updateEndOffsets(Map.of(P0, 13L, P1, 22L, P2, 30L));
            consumer.schedulePollTask(() -> {
                assertEquals(12, consumer.position(P0));
                assertEquals(21, consumer.position(P1));
                assertFalse(consumer.assignment().contains(P2));
                consumer.addRecord(new ConsumerRecord<>(TOPIC, 0, 12, "truck-001", "{\"eventTime\":9000}"));
            });
            consumer.schedulePollTask(() -> consumer.addRecord(
                    new ConsumerRecord<>(TOPIC, 1, 21, "truck-002", "{\"eventTime\":15000}")));
            assertEquals(OptionalLong.of(15_000), KafkaEventTime.latest(consumer, TOPIC, Duration.ofSeconds(1)));
            assertTrue(consumer.committed(java.util.Set.of(P0, P1)).values().stream().allMatch(java.util.Objects::isNull));
        }
    }

    @Test
    void ignoresTombstonesAndMalformedTailsWithoutLosingOtherPartitions() {
        try (MockConsumer<String, String> consumer = consumer()) {
            consumer.updateBeginningOffsets(Map.of(P0, 0L, P1, 0L, P2, 0L));
            consumer.updateEndOffsets(Map.of(P0, 1L, P1, 1L, P2, 1L));
            consumer.schedulePollTask(() -> {
                consumer.addRecord(new ConsumerRecord<>(TOPIC, 0, 0, "truck-001", null));
                consumer.addRecord(new ConsumerRecord<>(TOPIC, 1, 0, "truck-002", "not json"));
                consumer.addRecord(new ConsumerRecord<>(TOPIC, 2, 0, "truck-003", "{\"eventTime\":12345}"));
            });
            assertEquals(OptionalLong.of(12_345), KafkaEventTime.latest(consumer, TOPIC, Duration.ofSeconds(1)));
        }
    }

    @Test
    void unreachableKafkaSkipsFence() {
        try (MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.NONE) {
            @Override
            public List<PartitionInfo> partitionsFor(String topic, Duration timeout) {
                throw new TimeoutException("broker unreachable");
            }
        }) {
            assertEquals(OptionalLong.empty(), KafkaEventTime.latest(consumer, TOPIC, Duration.ofSeconds(1)));
        }
    }

    private static MockConsumer<String, String> consumer() {
        MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.NONE);
        consumer.updatePartitions(TOPIC, List.of(new PartitionInfo(TOPIC, 0, null, null, null),
                new PartitionInfo(TOPIC, 1, null, null, null), new PartitionInfo(TOPIC, 2, null, null, null)));
        return consumer;
    }
}
