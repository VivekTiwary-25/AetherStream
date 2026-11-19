package io.aetherstream.engine.worker;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manual assign + seek, no commits. A separate consumer refreshes end offsets so metadata requests
 * never delay record intake, even when downstream backpressure stops polling.
 */
public final class KafkaRecordSource implements RecordSource {
    private static final Logger log = LoggerFactory.getLogger(KafkaRecordSource.class);
    private final String topic;
    private final Consumer<String, String> consumer;
    private final Supplier<? extends Consumer<String, String>> endOffsetConsumerFactory;
    private volatile List<TopicPartition> assigned = List.of();
    private volatile Map<Integer, Long> cachedEndOffsets = Map.of();
    private volatile boolean monitoring;
    private volatile Consumer<String, String> endOffsetConsumer;
    private Thread monitor;

    public KafkaRecordSource(String bootstrap, String topic, String clientId) {
        this(topic, new KafkaConsumer<>(properties(bootstrap, clientId)),
                () -> new KafkaConsumer<>(properties(bootstrap, clientId + "-lag")));
    }

    KafkaRecordSource(String topic, Consumer<String, String> consumer,
                      Supplier<? extends Consumer<String, String>> endOffsetConsumerFactory) {
        this.topic = topic;
        this.consumer = consumer;
        this.endOffsetConsumerFactory = endOffsetConsumerFactory;
    }

    private static Properties properties(String bootstrap, String clientId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, clientId);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "500");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return props;
    }

    @Override
    public void assign(Collection<Integer> partitions, Map<Integer, Long> startOffsets) {
        List<TopicPartition> tps = new ArrayList<>();
        for (int p : partitions) {
            tps.add(new TopicPartition(topic, p));
        }
        assigned = List.copyOf(tps);
        cachedEndOffsets = Map.of();
        consumer.assign(assigned);
        for (TopicPartition tp : assigned) {
            consumer.seek(tp, startOffsets.getOrDefault(tp.partition(), 0L));
        }
        if (monitor == null && !assigned.isEmpty()) {
            monitoring = true;
            monitor = new Thread(this::monitorEndOffsets, "consumer-lag-" + topic);
            monitor.setDaemon(true);
            monitor.start();
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
    public Map<Integer, Long> nextOffsets() {
        Map<Integer, Long> out = new TreeMap<>();
        for (TopicPartition tp : assigned) {
            out.put(tp.partition(), consumer.position(tp, Duration.ofMillis(250)));
        }
        return out;
    }

    @Override
    public Map<Integer, Long> endOffsets() {
        return cachedEndOffsets;
    }

    private void monitorEndOffsets() {
        Consumer<String, String> offsets = null;
        try {
            offsets = endOffsetConsumerFactory.get();
            endOffsetConsumer = offsets;
            while (monitoring) {
                List<TopicPartition> current = assigned;
                try {
                    Map<Integer, Long> end = new TreeMap<>();
                    offsets.endOffsets(current, Duration.ofSeconds(1))
                            .forEach((tp, offset) -> end.put(tp.partition(), offset));
                    if (monitoring && current == assigned) {
                        cachedEndOffsets = Map.copyOf(end);
                    }
                } catch (RuntimeException e) {
                    if (monitoring) {
                        log.debug("consumer lag end-offset refresh failed", e);
                    }
                }
                Thread.sleep(TaskRuntime.LAG_REFRESH_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            if (monitoring) {
                log.warn("consumer lag monitor failed", e);
            }
        } finally {
            // Interrupt is used to stop sleep or a metadata request, not to abort consumer cleanup.
            Thread.interrupted();
            if (offsets != null) {
                try {
                    offsets.close(Duration.ofMillis(250));
                } catch (RuntimeException e) {
                    log.debug("closing consumer lag monitor failed", e);
                }
            }
            endOffsetConsumer = null;
        }
    }

    @Override
    public void wakeup() {
        consumer.wakeup();
    }

    @Override
    public void close() {
        monitoring = false;
        Consumer<String, String> offsets = endOffsetConsumer;
        if (offsets != null) {
            offsets.wakeup();
        }
        if (monitor != null) {
            monitor.interrupt();
            try {
                monitor.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        consumer.close(Duration.ofMillis(250));
    }
}
