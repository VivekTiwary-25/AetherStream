package io.aetherstream.simulator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.aetherstream.common.Json;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Properties;
import java.util.Set;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Best-effort startup fence; manual assignment never joins or commits an execution's group.
 * Scans tail windows for their maximum non-late time, walking backwards past all-chaos windows
 * within bounded startup attempts with backoff. Reads use captured end offsets and the retention floor.
 */
final class KafkaEventTime {
    private static final Logger LOG = LoggerFactory.getLogger(KafkaEventTime.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final int TAIL_WINDOW_RECORDS = 256;
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_BACKOFF_MS = 250;

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
        long latest = Long.MIN_VALUE;
        boolean found = false;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            long deadline = System.nanoTime() + timeout.multipliedBy(1L << (attempt - 1)).toNanos();
            try {
                List<TopicPartition> partitions = consumer.partitionsFor(topic, remaining(deadline)).stream()
                        .map(p -> new TopicPartition(topic, p.partition())).toList();
                Map<TopicPartition, Long> ends = consumer.endOffsets(partitions, remaining(deadline));
                Map<TopicPartition, Long> starts = consumer.beginningOffsets(partitions, remaining(deadline));
                Map<TopicPartition, Long> pending = new HashMap<>();
                Map<TopicPartition, Long> windowStarts = new HashMap<>();
                Set<TopicPartition> anchored = new HashSet<>();
                for (TopicPartition partition : partitions) {
                    long end = ends.get(partition);
                    long beginning = starts.get(partition);
                    if (end > beginning) {
                        pending.put(partition, end);
                        windowStarts.put(partition, Math.max(beginning, end - TAIL_WINDOW_RECORDS));
                    }
                }
                consumer.assign(pending.keySet());
                windowStarts.forEach(consumer::seek);
                while (!pending.isEmpty()) {
                    for (var record : consumer.poll(remaining(deadline))) {
                        TopicPartition partition = new TopicPartition(record.topic(), record.partition());
                        Long end = pending.get(partition);
                        if (end == null || record.offset() < windowStarts.get(partition)
                                || record.offset() >= end) continue;
                        try {
                            JsonNode event = record.value() == null ? null : Json.MAPPER.readTree(record.value());
                            JsonNode id = event == null ? null : event.get("eventId");
                            if (id != null && id.isTextual() && id.textValue().contains("-late-")) continue;
                            JsonNode time = event == null ? null : event.get("eventTime");
                            if (time != null && time.isIntegralNumber() && time.canConvertToLong()) {
                                anchored.add(partition);
                                latest = Math.max(latest, time.longValue());
                                found = true;
                            }
                        } catch (JsonProcessingException e) {
                            LOG.warn("Ignoring unparseable Kafka tail at {}:{}", partition, record.offset());
                        }
                    }
                    // Take the maximum over each captured window, not the last offset's timestamp.
                    // A whole window of chaos records must not hide the preceding normal timestamp.
                    for (var iterator = pending.entrySet().iterator(); iterator.hasNext();) {
                        var entry = iterator.next();
                        TopicPartition partition = entry.getKey();
                        if (consumer.position(partition, remaining(deadline)) < entry.getValue()) continue;
                        long start = windowStarts.get(partition);
                        long beginning = starts.get(partition);
                        if (anchored.contains(partition) || start == beginning) {
                            iterator.remove();
                        } else {
                            entry.setValue(start);
                            long previousStart = Math.max(beginning, start - TAIL_WINDOW_RECORDS);
                            windowStarts.put(partition, previousStart);
                            consumer.seek(partition, previousStart);
                        }
                    }
                }
                return found ? OptionalLong.of(latest) : OptionalLong.empty();
            } catch (RuntimeException e) {
                if (attempt == MAX_ATTEMPTS || Thread.currentThread().isInterrupted()) {
                    LOG.warn("Kafka startup event-time fence incomplete after {} attempts; clock may restart behind Kafka",
                            attempt, e);
                    break;
                }
                long backoff = RETRY_BACKOFF_MS << (attempt - 1);
                LOG.warn("Kafka startup event-time fence attempt {} failed; retrying in {} ms", attempt, backoff, e);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    LOG.warn("Kafka startup event-time fence interrupted; clock may restart behind Kafka", interrupted);
                    break;
                }
            }
        }
        return found ? OptionalLong.of(latest) : OptionalLong.empty();
    }

    private static Duration remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new org.apache.kafka.common.errors.TimeoutException("Startup tail deadline exceeded");
        return Duration.ofNanos(nanos);
    }
}
