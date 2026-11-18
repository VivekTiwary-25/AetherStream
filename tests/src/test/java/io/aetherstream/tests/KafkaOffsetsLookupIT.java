package io.aetherstream.tests;

import static org.assertj.core.api.Assertions.assertThat;

import io.aetherstream.engine.coordinator.KafkaOffsetsLookup;
import io.aetherstream.tests.support.IntegrationInfrastructure;
import java.util.List;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class KafkaOffsetsLookupIT {
    @Test
    void returnsEarliestAndLatestOffsetsForAllPartitions() {
        KafkaOffsetsLookup lookup = new KafkaOffsetsLookup(IntegrationInfrastructure.bootstrap());
        var earliestBefore = lookup.offsets(IntegrationInfrastructure.TOPIC, 6, "earliest");
        var latestBefore = lookup.offsets(IntegrationInfrastructure.TOPIC, 6, "latest");
        List<ProducerRecord<String, String>> records = java.util.stream.IntStream.range(0, 10)
                .mapToObj(i -> new ProducerRecord<>(IntegrationInfrastructure.TOPIC, 2, "truck-" + i, "event-" + i))
                .toList();
        IntegrationInfrastructure.produce(records);

        assertThat(earliestBefore).containsOnlyKeys(0, 1, 2, 3, 4, 5);
        assertThat(latestBefore).containsOnlyKeys(0, 1, 2, 3, 4, 5);
        assertThat(lookup.offsets(IntegrationInfrastructure.TOPIC, 6, "earliest")).isEqualTo(earliestBefore);
        var latestAfter = lookup.offsets(IntegrationInfrastructure.TOPIC, 6, "latest");
        assertThat(latestAfter).containsOnlyKeys(0, 1, 2, 3, 4, 5);
        for (int partition = 0; partition < 6; partition++) {
            assertThat(latestAfter.get(partition) - latestBefore.get(partition))
                    .as("latest offset delta for partition %s", partition)
                    .isEqualTo(partition == 2 ? records.size() : 0L);
        }
    }
}
