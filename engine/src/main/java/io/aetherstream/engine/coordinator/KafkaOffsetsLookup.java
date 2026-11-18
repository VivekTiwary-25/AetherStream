package io.aetherstream.engine.coordinator;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;

/** Looks up beginning/end offsets with a short-lived KafkaConsumer (no group, no subscription). */
public class KafkaOffsetsLookup implements OffsetsLookup {
    private final String bootstrap;

    public KafkaOffsetsLookup(String bootstrap) {
        this.bootstrap = bootstrap;
    }

    @Override
    public Map<Integer, Long> offsets(String topic, int partitions, String startFrom) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, "aether-coordinator-offsets");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        List<TopicPartition> tps = new ArrayList<>();
        for (int p = 0; p < partitions; p++) {
            tps.add(new TopicPartition(topic, p));
        }
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(props)) {
            Duration timeout = Duration.ofSeconds(15);
            Map<TopicPartition, Long> raw = "earliest".equals(startFrom)
                    ? consumer.beginningOffsets(tps, timeout)
                    : consumer.endOffsets(tps, timeout);
            Map<Integer, Long> result = new TreeMap<>();
            for (Map.Entry<TopicPartition, Long> e : raw.entrySet()) {
                result.put(e.getKey().partition(), e.getValue());
            }
            return result;
        }
    }
}
