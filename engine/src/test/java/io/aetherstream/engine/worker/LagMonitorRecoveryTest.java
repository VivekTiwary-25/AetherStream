package io.aetherstream.engine.worker;

import static io.aetherstream.engine.worker.WorkerFixtures.await;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Test;

class LagMonitorRecoveryTest {
    @Test
    void retriesFactoryAndMetadataFailuresWithoutReassigningOrBlockingIntake() throws Exception {
        var partition = new TopicPartition("events", 0);
        var records = new MockConsumer<String, String>(OffsetResetStrategy.EARLIEST);
        var samples = new AtomicInteger();
        var attempts = new AtomicInteger();
        var offsets = new MockConsumer<String, String>(OffsetResetStrategy.EARLIEST) {
            @Override
            public Map<TopicPartition, Long> endOffsets(Collection<TopicPartition> partitions, Duration timeout) {
                if (samples.incrementAndGet() == 1) throw new TimeoutException("temporary metadata failure");
                return Map.of(partition, 20L);
            }
        };
        var source = new KafkaRecordSource("events", records, () -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("temporary factory failure");
            return offsets;
        });
        try {
            source.assign(List.of(0), Map.of(0, 5L));
            await("first factory failure", 5000, () -> attempts.get() == 1);
            records.addRecord(new ConsumerRecord<>("events", 0, 5, "truck", "value"));
            assertThat(source.poll(Duration.ZERO)).containsExactly(new RecordSource.SourceRecord(0, 5, "value"));
            await("lag after factory and metadata retry", 12000, () -> source.endOffsets().equals(Map.of(0, 20L)));
            assertThat(TaskRuntime.calculateLag(source.endOffsets(), source.nextOffsets())).containsEntry(0, 14L);
            assertThat(attempts.get()).isEqualTo(2);
        } finally {
            source.close();
        }
        assertThat(offsets.closed()).isTrue();
        assertThat(records.closed()).isTrue();
    }
}
