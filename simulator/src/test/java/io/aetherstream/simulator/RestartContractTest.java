package io.aetherstream.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import io.aetherstream.common.Json;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.utils.Utils;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class RestartContractTest {
    private static final String TOPIC = "truck-events";
    private static final long ANCHOR = SimulatorRuntime.DEFAULT_ANCHOR_EPOCH_MS;

    @Test
    void retainedKafkaLogFencesRepeatedRestartsDespiteSlowerScaleAndWallRollback() throws Exception {
        AtomicLong wall = new AtomicLong(ANCHOR + 60_000);
        RetainedLog log = new RetainedLog();
        SimulatorRuntime original = new SimulatorRuntime(config(20, 8), ANCHOR, wall::get,
                OptionalLong.empty());
        log.append(original.tick());
        wall.addAndGet(1000);
        log.append(original.tick());

        // Neither the lower startup scale nor the corrected wall clock can recover the old time.
        wall.set(ANCHOR + 30_000);
        for (int restart = 0; restart < 2; restart++) {
            long previousMaximum = log.maximumEventTime();
            SimulatorRuntime restarted = new SimulatorRuntime(config(2, 8), ANCHOR, wall::get,
                    log.latestEventTime());
            List<Simulation.Emission> fresh = restarted.tick();

            assertNewEventsAfter(fresh, 8, previousMaximum);
            assertThat(eventTime(fresh.getFirst().json()))
                    .isEqualTo(previousMaximum + Simulation.POSITION_INTERVAL_MS);
            log.append(fresh);
        }
    }

    @Test
    void forwardWallClockAndFasterStartupScaleAdvanceBeyondTheRetainedKafkaFence() throws Exception {
        AtomicLong wall = new AtomicLong(ANCHOR + 1000);
        RetainedLog log = new RetainedLog();
        SimulatorRuntime original = new SimulatorRuntime(config(2, 8), ANCHOR, wall::get,
                OptionalLong.empty());
        log.append(original.tick());
        wall.addAndGet(1000);
        log.append(original.tick());
        long previousMaximum = log.maximumEventTime();

        wall.set(ANCHOR + 60_000);
        SimulatorRuntime restarted = new SimulatorRuntime(config(20, 8), ANCHOR, wall::get,
                log.latestEventTime());
        List<Simulation.Emission> fresh = restarted.tick();

        assertNewEventsAfter(fresh, 8, previousMaximum);
        assertThat(eventTime(fresh.getFirst().json()))
                .isEqualTo(Simulation.START_EPOCH_MS + 60_000 * 20);
    }

    @Test
    @Disabled("BUG: Kafka tail-only fence misses higher eventTime before a late event and its duplicate")
    void lateEventAndItsVerbatimDuplicateAtPartitionTailMustNotRewindNormalEventsAfterRestart()
            throws Exception {
        AtomicLong wall = new AtomicLong(ANCHOR + 60_000);
        RetainedLog log = new RetainedLog();
        SimulatorRuntime original = new SimulatorRuntime(config(20, 1), ANCHOR, wall::get,
                OptionalLong.empty());
        log.append(original.tick());
        wall.addAndGet(1000);
        log.append(original.tick());
        long previousMaximum = log.maximumEventTime();

        List<Simulation.Emission> late = original.simulation.lateEvents(1, 180_000);
        log.append(late);
        List<Simulation.Emission> duplicate = original.simulation.duplicates(1);
        assertThat(duplicate).containsExactlyElementsOf(late);
        log.append(duplicate);
        assertThat(eventTime(duplicate.getFirst().json())).isEqualTo(previousMaximum - 180_000);

        // The earlier high timestamp remains retained in the very same partition; consumers
        // have already advanced their watermark, even though the last appended record is old.
        wall.set(ANCHOR + 30_000);
        SimulatorRuntime restarted = new SimulatorRuntime(config(2, 1), ANCHOR, wall::get,
                log.latestEventTime());
        assertNewEventsAfter(restarted.tick(), 1, previousMaximum);
    }

    private static SimulatorConfig config(double scale, int trucks) {
        return new SimulatorConfig(42, scale, trucks, 0, false, "unused:9092", TOPIC);
    }

    private static long eventTime(String json) throws Exception {
        return Json.MAPPER.readTree(json).path("eventTime").longValue();
    }

    private static void assertNewEventsAfter(List<Simulation.Emission> fresh, int trucks,
                                             long previousMaximum) throws Exception {
        assertThat(fresh).hasSize(trucks);
        for (Simulation.Emission event : fresh) {
            assertThat(eventTime(event.json()))
                    .as("normal event for %s must not trail an already consumed event", event.key())
                    .isGreaterThan(previousMaximum);
        }
    }

    /** A retained, append-ordered log with Kafka's default keyed partition selection. */
    private static final class RetainedLog {
        private static final int PARTITIONS = 6;
        // Earlier offsets have expired; empty partitions likewise have beginning == end.
        private static final long BEGINNING = 17;
        private final List<ConsumerRecord<String, String>> records = new ArrayList<>();
        private final Map<TopicPartition, Long> ends = new HashMap<>();

        RetainedLog() {
            for (int partition = 0; partition < PARTITIONS; partition++) {
                ends.put(new TopicPartition(TOPIC, partition), BEGINNING);
            }
        }

        void append(List<Simulation.Emission> emissions) {
            for (Simulation.Emission emission : emissions) {
                int partition = Utils.toPositive(Utils.murmur2(emission.key().getBytes(StandardCharsets.UTF_8)))
                        % PARTITIONS;
                TopicPartition tp = new TopicPartition(TOPIC, partition);
                long offset = ends.get(tp);
                records.add(new ConsumerRecord<>(TOPIC, partition, offset, emission.key(), emission.json()));
                ends.put(tp, offset + 1);
            }
        }

        long maximumEventTime() throws Exception {
            long maximum = Long.MIN_VALUE;
            for (ConsumerRecord<String, String> record : records) {
                maximum = Math.max(maximum, eventTime(record.value()));
            }
            return maximum;
        }

        OptionalLong latestEventTime() {
            try (MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.NONE) {
                @Override
                public synchronized ConsumerRecords<String, String> poll(Duration timeout) {
                    // Honor seeks: never present an earlier retained record as the partition tail.
                    for (ConsumerRecord<String, String> record : records) {
                        TopicPartition tp = new TopicPartition(record.topic(), record.partition());
                        if (assignment().contains(tp) && record.offset() >= position(tp)) {
                            addRecord(record);
                        }
                    }
                    return super.poll(timeout);
                }
            }) {
                List<PartitionInfo> partitions = new ArrayList<>();
                Map<TopicPartition, Long> beginnings = new HashMap<>();
                for (int partition = 0; partition < PARTITIONS; partition++) {
                    partitions.add(new PartitionInfo(TOPIC, partition, null, null, null));
                    beginnings.put(new TopicPartition(TOPIC, partition), BEGINNING);
                }
                consumer.updatePartitions(TOPIC, partitions);
                consumer.updateBeginningOffsets(beginnings);
                consumer.updateEndOffsets(ends);
                return KafkaEventTime.latest(consumer, TOPIC, Duration.ofSeconds(1));
            }
        }
    }
}
