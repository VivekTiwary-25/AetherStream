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
        List<ProducerRecord<String, String>> records = java.util.stream.IntStream.range(0, 10)
                .mapToObj(i -> new ProducerRecord<>(IntegrationInfrastructure.TOPIC, 2, "truck-" + i, "event-" + i))
                .toList();
        IntegrationInfrastructure.produce(records);
        KafkaOffsetsLookup lookup = new KafkaOffsetsLookup(IntegrationInfrastructure.bootstrap());

        assertThat(lookup.offsets(IntegrationInfrastructure.TOPIC, 6, "earliest"))
                .containsEntry(0, 0L).containsEntry(1, 0L).containsEntry(2, 0L)
                .containsEntry(3, 0L).containsEntry(4, 0L).containsEntry(5, 0L);
        assertThat(lookup.offsets(IntegrationInfrastructure.TOPIC, 6, "latest"))
                .containsEntry(0, 0L).containsEntry(1, 0L).containsEntry(2, 10L)
                .containsEntry(3, 0L).containsEntry(4, 0L).containsEntry(5, 0L);
    }
}
