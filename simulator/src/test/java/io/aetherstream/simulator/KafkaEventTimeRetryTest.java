package io.aetherstream.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class KafkaEventTimeRetryTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void retriesSlowKafkaAndBoundsAttemptsWhenItStaysUnavailable(boolean recovers) {
        String topic = "truck-events";
        var partition = new TopicPartition(topic, 0);
        var attempts = new AtomicInteger();
        try (var consumer = new MockConsumer<String, String>(OffsetResetStrategy.NONE) {
            @Override
            public List<PartitionInfo> partitionsFor(String name, Duration timeout) {
                if (attempts.incrementAndGet() < 3 || !recovers) {
                    throw new TimeoutException("slow Kafka startup");
                }
                return super.partitionsFor(name, timeout);
            }
        }) {
            consumer.updatePartitions(topic, List.of(new PartitionInfo(topic, 0, null, null, null)));
            consumer.updateBeginningOffsets(Map.of(partition, 0L));
            consumer.updateEndOffsets(Map.of(partition, 1L));
            consumer.schedulePollTask(() -> consumer.addRecord(
                    new ConsumerRecord<>(topic, 0, 0L, "truck-001", "{\"eventTime\":900000}")));
            assertEquals(recovers ? OptionalLong.of(900_000) : OptionalLong.empty(),
                    KafkaEventTime.latest(consumer, topic, Duration.ofSeconds(1)));
            assertEquals(3, attempts.get());
        }
    }
}
