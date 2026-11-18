package io.aetherstream.simulator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.aetherstream.common.Json;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Properties;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Best-effort startup fence; manual assignment never joins or commits an execution's group. */
final class KafkaEventTime {
    private static final Logger LOG = LoggerFactory.getLogger(KafkaEventTime.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    static OptionalLong latest(SimulatorConfig config) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.kafkaBootstrap());
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none");
        properties.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "3000");
        properties.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, "3000");
        KafkaConsumer<String, String> consumer = null;
        try {
            consumer = new KafkaConsumer<>(properties);
            return latest(consumer, config.kafkaTopic(), TIMEOUT);
        } catch (RuntimeException e) {
            LOG.warn("Skipping Kafka startup event-time fence: {}", e.toString());
            return OptionalLong.empty();
        } finally {
            if (consumer != null) {
                try {
                    consumer.close(Duration.ofMillis(200));
                } catch (RuntimeException e) {
                    LOG.warn("Closing Kafka startup consumer failed: {}", e.toString());
                }
            }
        }
    }

    static OptionalLong latest(Consumer<String, String> consumer, String topic, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        OptionalLong latest = OptionalLong.empty();
        try {
            List<TopicPartition> partitions = consumer.partitionsFor(topic, remaining(deadline)).stream()
                    .map(p -> new TopicPartition(topic, p.partition())).toList();
            Map<TopicPartition, Long> ends = consumer.endOffsets(partitions, remaining(deadline));
            Map<TopicPartition, Long> starts = consumer.beginningOffsets(partitions, remaining(deadline));
            Map<TopicPartition, Long> pending = new HashMap<>();
            for (TopicPartition partition : partitions) {
                long end = ends.get(partition);
                if (end > starts.get(partition)) pending.put(partition, end - 1);
            }
            consumer.assign(pending.keySet());
            pending.forEach(consumer::seek);
            while (!pending.isEmpty()) {
                for (var record : consumer.poll(remaining(deadline))) {
                    TopicPartition partition = new TopicPartition(record.topic(), record.partition());
                    Long offset = pending.get(partition);
                    if (offset == null || record.offset() < offset) continue;
                    pending.remove(partition);
                    try {
                        JsonNode event = record.value() == null ? null : Json.MAPPER.readTree(record.value());
                        JsonNode time = event == null ? null : event.get("eventTime");
                        if (time != null && time.isIntegralNumber() && time.canConvertToLong()) {
                            latest = OptionalLong.of(latest.isEmpty() ? time.longValue()
                                    : Math.max(latest.getAsLong(), time.longValue()));
                        }
                    } catch (JsonProcessingException e) {
                        LOG.warn("Ignoring unparseable Kafka tail at {}:{}", partition, record.offset());
                    }
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("Kafka startup event-time fence incomplete: {}", e.toString());
        }
        return latest;
    }

    private static Duration remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new org.apache.kafka.common.errors.TimeoutException("Startup tail deadline exceeded");
        return Duration.ofNanos(nanos);
    }
}
