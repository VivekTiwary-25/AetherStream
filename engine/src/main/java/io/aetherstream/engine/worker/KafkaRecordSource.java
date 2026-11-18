package io.aetherstream.engine.worker;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;

/** KafkaConsumer with manual assign + seek, no offset commits (offsets live in checkpoints). */
public final class KafkaRecordSource implements RecordSource {
    private final String topic;
    private final KafkaConsumer<String, String> consumer;
    private List<TopicPartition> assigned = List.of();

    public KafkaRecordSource(String bootstrap, String topic, String clientId) {
        this.topic = topic;
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, clientId);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "500");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        this.consumer = new KafkaConsumer<>(props);
    }

    @Override
    public void assign(Collection<Integer> partitions, Map<Integer, Long> startOffsets) {
        List<TopicPartition> tps = new ArrayList<>();
        for (int p : partitions) {
            tps.add(new TopicPartition(topic, p));
        }
        assigned = List.copyOf(tps);
        consumer.assign(assigned);
        for (TopicPartition tp : assigned) {
            consumer.seek(tp, startOffsets.getOrDefault(tp.partition(), 0L));
        }
    }

    @Override
    public List<SourceRecord> poll(Duration timeout) {
        List<SourceRecord> out = new ArrayList<>();
        for (ConsumerRecord<String, String> r : consumer.poll(timeout)) {
            out.add(new SourceRecord(r.partition(), r.offset(), r.value()));
        }
        return out;
    }

    @Override
    public Map<Integer, Long> endOffsets() {
        Map<Integer, Long> out = new TreeMap<>();
        if (assigned.isEmpty()) {
            return out;
        }
        consumer.endOffsets(assigned, Duration.ofSeconds(1)).forEach((tp, o) -> out.put(tp.partition(), o));
        return out;
    }

    @Override
    public void close() {
        consumer.close();
    }
}
