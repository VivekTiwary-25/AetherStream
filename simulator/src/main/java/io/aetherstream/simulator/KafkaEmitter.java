package io.aetherstream.simulator;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Kafka I/O is confined to one worker, never a simulation or HTTP thread. */
public final class KafkaEmitter implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(KafkaEmitter.class);
    private static final long RETRY_DELAY_MS = 2_000;
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(2);

    // Intentionally unbounded: an outage must neither lose accepted events nor stop simulation time.
    // Prolonged outages (especially repeated chaos calls) can exhaust heap; this is not a durable spool.
    private final LinkedBlockingQueue<Simulation.Emission> queue = new LinkedBlockingQueue<>();
    private final Properties properties;
    private final String topic;
    private final Thread worker;
    private volatile boolean closed;
    private boolean started;

    public KafkaEmitter(SimulatorConfig config) {
        topic = config.kafkaTopic();
        properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.kafkaBootstrap());
        properties.put(ProducerConfig.CLIENT_ID_CONFIG, "aetherstream-simulator");
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 1_000L);
        properties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 1_000);
        properties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 3_000);
        worker = Thread.ofPlatform().daemon(true).name("simulator-kafka").unstarted(this::run);
    }

    public synchronized void start() {
        if (closed) {
            throw new IllegalStateException("Kafka emitter is closed");
        }
        if (!started) {
            started = true;
            worker.start();
        }
    }

    /** Call under the Simulation monitor, in the same critical section that generated these events. */
    public void submit(List<Simulation.Emission> emissions) {
        if (closed) {
            throw new RejectedExecutionException("Kafka emitter is closed");
        }
        for (Simulation.Emission emission : emissions) {
            // offer never waits for broker availability or queue capacity.
            if (!queue.offer(emission)) {
                throw new RejectedExecutionException("Kafka outage buffer exhausted");
            }
        }
    }

    private void run() {
        KafkaProducer<String, String> producer = null;
        Simulation.Emission pending = null;
        try {
            while (!closed) {
                try {
                    if (producer == null) {
                        // Construction can itself fail for an unresolved bootstrap hostname.
                        producer = new KafkaProducer<>(properties);
                        if (producer.partitionsFor(topic).isEmpty()) {
                            throw new IllegalStateException("Kafka topic has no available partitions: " + topic);
                        }
                        LOG.info("Kafka ready for topic {}", topic);
                    }
                    if (pending == null) {
                        pending = queue.poll(250, TimeUnit.MILLISECONDS);
                    }
                    if (pending != null) {
                        // Only one unacknowledged record: no later event can pass a failed record.
                        producer.send(new ProducerRecord<>(topic, pending.key(), pending.json())).get();
                        pending = null;
                    }
                } catch (RuntimeException | ExecutionException e) {
                    if (closed) {
                        break;
                    }
                    LOG.warn("Kafka unavailable; retaining unsent events and retrying in {} ms: {}",
                            RETRY_DELAY_MS, e.toString());
                    closeProducer(producer);
                    producer = null;
                    Thread.sleep(RETRY_DELAY_MS);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            // Kafka close must get a chance to release resources even after worker interruption.
            boolean interrupted = Thread.interrupted();
            closeProducer(producer);
            long unsent = (long) queue.size() + (pending == null ? 0 : 1);
            if (unsent > 0) {
                LOG.warn("Kafka emitter stopped with {} unacknowledged in-memory events; they are not durable", unsent);
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void closeProducer(KafkaProducer<String, String> producer) {
        if (producer != null) {
            try {
                producer.close(CLOSE_TIMEOUT);
            } catch (RuntimeException e) {
                LOG.warn("Could not close Kafka producer cleanly: {}", e.toString());
            }
        }
    }

    @Override
    public void close() {
        Thread toJoin;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            toJoin = started ? worker : null;
        }
        if (toJoin != null) {
            toJoin.interrupt();
            try {
                toJoin.join(5_000);
                if (toJoin.isAlive()) {
                    LOG.warn("Kafka worker did not stop within the shutdown deadline");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
