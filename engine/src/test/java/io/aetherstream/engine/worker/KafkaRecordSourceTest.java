package io.aetherstream.engine.worker;

import static io.aetherstream.engine.worker.WorkerFixtures.await;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InterruptException;
import org.junit.jupiter.api.Test;

class KafkaRecordSourceTest {
    @Test
    void metadataRequestsDoNotBlockIntakeAndRefreshWithoutFurtherPolls() throws Exception {
        var records = new MockConsumer<String, String>(OffsetResetStrategy.EARLIEST);
        var offsets = new BlockingEndOffsets();
        var source = new KafkaRecordSource("events", records, () -> offsets);
        try {
            source.assign(List.of(0), Map.of(0, 100L));
            assertThat(offsets.entered.await(5, TimeUnit.SECONDS)).isTrue();
            records.addRecord(new ConsumerRecord<>("events", 0, 100, "key", "value"));
            // The metadata request remains blocked until intake and cached reads complete.
            assertThat(source.poll(Duration.ZERO)).containsExactly(new RecordSource.SourceRecord(0, 100, "value"));
            assertThat(source.nextOffsets()).containsExactlyEntriesOf(Map.of(0, 101L));
            assertThat(source.endOffsets()).isEmpty();
            offsets.release.countDown();
            await("initial cached end offset", 5_000, () -> source.endOffsets().equals(Map.of(0, 150L)));
            offsets.end.set(190);
            await("end offset grows without polling", 5_000, () -> source.endOffsets().equals(Map.of(0, 190L)));
            assertThat(TaskRuntime.calculateLag(source.endOffsets(), source.nextOffsets()))
                    .containsExactlyEntriesOf(Map.of(0, 89L));
            assertThat(offsets.owner).isNotSameAs(Thread.currentThread());
        } finally {
            offsets.release.countDown();
            source.close();
        }
        assertThat(records.closed()).isTrue();
        assertThat(offsets.closed()).isTrue();
        assertThat(offsets.closeThread).isSameAs(offsets.owner);
    }

    @Test
    void cancellationClosesMonitorOnItsOwnThreadDuringMetadataRequest() throws Exception {
        var records = new MockConsumer<String, String>(OffsetResetStrategy.EARLIEST);
        var offsets = new BlockingEndOffsets();
        var source = new KafkaRecordSource("events", records, () -> offsets);
        try {
            source.assign(List.of(0), Map.of(0, 0L));
            assertThat(offsets.entered.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            source.close();
            offsets.release.countDown();
        }
        assertThat(offsets.closed()).isTrue();
        assertThat(offsets.closeThread).isSameAs(offsets.owner);
        assertThat(records.closed()).isTrue();
    }

    private static final class BlockingEndOffsets extends MockConsumer<String, String> {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicLong end = new AtomicLong(150);
        volatile Thread owner;
        volatile Thread closeThread;

        BlockingEndOffsets() {
            super(OffsetResetStrategy.EARLIEST);
        }

        @Override
        public Map<TopicPartition, Long> endOffsets(Collection<TopicPartition> partitions, Duration timeout) {
            owner = Thread.currentThread();
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("metadata request was never released");
                }
            } catch (InterruptedException e) {
                throw new InterruptException(e);
            }
            Map<TopicPartition, Long> result = new TreeMap<>((a, b) -> Integer.compare(a.partition(), b.partition()));
            partitions.forEach(partition -> result.put(partition, end.get()));
            return result;
        }

        @Override
        public void close(Duration timeout) {
            closeThread = Thread.currentThread();
            super.close(timeout);
        }
    }
}
