package io.aetherstream.tests.support;

import io.aetherstream.simulator.Simulation;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.producer.ProducerRecord;

/** Fixed seed-42 workload, with no wall-clock sleeps or background simulator. */
public final class WorkloadGenerator {
    private WorkloadGenerator() {}

    public static List<ProducerRecord<String, String>> generate() {
        Simulation simulation = new Simulation(42, 8, 0, true);
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
        return records;
    }

    private static void append(List<ProducerRecord<String, String>> records, List<Simulation.Emission> emissions) {
        for (Simulation.Emission emission : emissions) {
            records.add(new ProducerRecord<>(IntegrationInfrastructure.TOPIC, emission.key(), emission.json()));
        }
    }
}
