package io.aetherstream.reference;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.serialization.StringDeserializer;

/** Reads complete checkpoint ranges without subscribing to or committing a consumer group. */
public final class KafkaRangeReader {
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private final Function<Map<String, Object>, Consumer<String, String>> consumerFactory;
    private final long timeoutNanos;

    public KafkaRangeReader() {
        this(properties -> new KafkaConsumer<String, String>(properties), READ_TIMEOUT);
    }

    KafkaRangeReader(Function<Map<String, Object>, Consumer<String, String>> consumerFactory,
                     Duration timeout) {
        this.consumerFactory = Objects.requireNonNull(consumerFactory, "consumerFactory");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        timeoutNanos = timeout.toNanos();
    }

    public Map<Integer, List<RawRecord>> read(String bootstrap, String topic,
                                             Map<Integer, Long> fromInclusive,
                                             Map<Integer, Long> toExclusive) {
        if (bootstrap == null || bootstrap.isBlank() || topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("bootstrap and topic must not be blank");
        }
        Objects.requireNonNull(fromInclusive, "fromInclusive");
        Objects.requireNonNull(toExclusive, "toExclusive");
        if (!fromInclusive.keySet().equals(toExclusive.keySet())) {
            throw new IllegalArgumentException("offset maps must have identical partition sets");
        }
        for (Integer partition : fromInclusive.keySet()) {
            Long from = fromInclusive.get(partition);
            Long to = toExclusive.get(partition);
            if (partition == null || partition < 0 || from == null || to == null
                    || from < 0 || to < from) {
                throw new IllegalArgumentException("partitions and offsets must be nonnegative, with from <= to");
            }
        }

        Map<Integer, List<RawRecord>> result = new LinkedHashMap<>();
        Set<TopicPartition> pending = new LinkedHashSet<>();
        for (int partition : fromInclusive.keySet().stream().sorted().toList()) {
            result.put(partition, new ArrayList<>());
            if (fromInclusive.get(partition) < toExclusive.get(partition)) {
                pending.add(new TopicPartition(topic, partition));
            }
        }
        if (pending.isEmpty()) {
            return result;
        }

        long started = System.nanoTime();
        Map<String, Object> properties = new HashMap<>();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none");
        Consumer<String, String> consumer = consumerFactory.apply(properties);
        Throwable failure = null;
        try {
            remaining(started);
            consumer.assign(pending);
            Map<TopicPartition, Long> beginnings = consumer.beginningOffsets(pending, remaining(started));
            Map<TopicPartition, Long> ends = consumer.endOffsets(pending, remaining(started));
            for (TopicPartition partition : pending) {
                long from = fromInclusive.get(partition.partition());
                long to = toExclusive.get(partition.partition());
                if (from < beginnings.get(partition) || to > ends.get(partition)) {
                    throw new IllegalStateException("Kafka range [" + from + "," + to
                            + ") is unavailable for " + partition);
                }
                remaining(started);
                consumer.seek(partition, from);
            }
            while (!pending.isEmpty()) {
                ConsumerRecords<String, String> records = consumer.poll(remaining(started));
                Iterator<TopicPartition> iterator = pending.iterator();
                while (iterator.hasNext()) {
                    TopicPartition partition = iterator.next();
                    int number = partition.partition();
                    long from = fromInclusive.get(number);
                    long to = toExclusive.get(number);
                    for (ConsumerRecord<String, String> record : records.records(partition)) {
                        if (record.offset() >= from && record.offset() < to) {
                            result.get(number).add(new RawRecord(number, record.offset(), record.value()));
                        }
                    }
                    // Position advances over compacted/transactional gaps, unlike record counts.
                    if (consumer.position(partition, remaining(started)) >= to) {
                        consumer.pause(Set.of(partition));
                        iterator.remove();
                    }
                }
            }
            remaining(started);
            return result;
        } catch (RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            Duration closeBudget = Duration.ofNanos(Math.max(0, timeoutNanos - (System.nanoTime() - started)));
            try {
                consumer.close(closeBudget);
            } catch (RuntimeException | Error exception) {
                if (failure == null) {
                    throw exception;
                }
                failure.addSuppressed(exception);
            }
        }
    }

    private Duration remaining(long started) {
        long nanos = timeoutNanos - (System.nanoTime() - started);
        if (nanos <= 0) {
            throw new TimeoutException("Timed out reading complete Kafka ranges");
        }
        return Duration.ofNanos(nanos);
    }
}
