package io.aetherstream.tests.support;

import static org.assertj.core.api.Assertions.assertThat;

import io.aetherstream.simulator.Simulation;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.utils.Utils;

/** Fixed seed-42 workload, with no wall-clock sleeps or background simulator. */
public final class WorkloadGenerator {
    private WorkloadGenerator() {}

    public static List<ProducerRecord<String, String>> generate() {
        // truck-016 is the first truck key hashing to partition 1 with Kafka's default partitioner.
        Simulation simulation = new Simulation(42, 16, 0, true);
        List<ProducerRecord<String, String>> records = new ArrayList<>();
        long duration = Duration.ofMinutes(40).toMillis();
        // Small steps keep trucks interleaved in event-time order within each partition.
        for (long elapsed = 0; elapsed <= duration; elapsed += Simulation.POSITION_INTERVAL_MS) {
            append(records, simulation.step(Simulation.START_EPOCH_MS + elapsed));
            if (elapsed == Duration.ofMinutes(20).toMillis()) {
                // Duplicate still-open windows, rather than merely making more late events.
                append(records, simulation.duplicates(20));
            }
        }
        append(records, simulation.lateEvents(20, Duration.ofMinutes(3).toMillis()));
        append(records, simulation.malformed(5));
        int[] partitionCounts = new int[6];
        for (var record : records) {
            int partition = Utils.toPositive(Utils.murmur2(record.key().getBytes(StandardCharsets.UTF_8))) % 6;
            partitionCounts[partition]++;
        }
        for (int partition = 0; partition < partitionCounts.length; partition++) {
            assertThat(partitionCounts[partition]).as("workload records in partition %s", partition).isPositive();
        }
        return records;
    }

    private static void append(List<ProducerRecord<String, String>> records, List<Simulation.Emission> emissions) {
        for (Simulation.Emission emission : emissions) {
            records.add(new ProducerRecord<>(IntegrationInfrastructure.TOPIC, emission.key(), emission.json()));
        }
    }
}
