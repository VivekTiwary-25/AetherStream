package io.aetherstream.api.glorious;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aetherstream.api.org.OrgContext;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Independent, latest-only observation of Kafka; never participates in engine recovery. */
@Service
public final class PipelineRuns {
    private final ObjectMapper mapper;
    private static final List<String> STRING_FIELDS = List.of("truckId", "routeId", "status");
    private static final List<String> NUMBER_FIELDS = List.of("speedKmh", "heading", "x", "y");
    private static final java.util.Set<String> STATUSES = java.util.Set.of("DRIVING", "UNLOADING", "IDLE", "STOPPED");
    private final Supplier<Consumer<String, String>> consumers;
    private final LongSupplier clock;
    private final String topic;
    private final Map<String, Run> runs = new LinkedHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private boolean shuttingDown;
    private Run active;

    @Autowired
    public PipelineRuns(ObjectMapper mapper,
            @Value("${aetherstream.kafka-bootstrap:kafka:9092}") String bootstrap,
            @Value("${KAFKA_TOPIC:truck-events}") String topic) {
        this(mapper, () -> consumer(bootstrap), topic, System::currentTimeMillis);
    }

    PipelineRuns(ObjectMapper mapper, Supplier<Consumer<String, String>> consumers,
            String topic, LongSupplier clock) {
        this.mapper = mapper;
        this.consumers = consumers;
        this.topic = topic;
        this.clock = clock;
    }

    private static Consumer<String, String> consumer(String bootstrap) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "glorious-pipelines-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 8000);
        props.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100);
        return new KafkaConsumer<>(props);
    }

    /** Caller validates before starting, so invalid submissions never stop a healthy run. */
    public synchronized String start(JsonNode pipeline) {
        if (shuttingDown)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "pipeline executor is shutting down");
        if (active != null) stopRun(active);
        Run run = new Run(UUID.randomUUID().toString(), OrgContext.current(), clock.getAsLong(), pipeline);
        runs.put(run.id, run);
        // Keep stopped runs pollable, but bound history as well as each result buffer.
        if (runs.size() > 20) runs.remove(runs.keySet().iterator().next());
        active = run;
        run.thread = new Thread(() -> consume(run), "glorious-pipeline-" + run.id);
        run.thread.setDaemon(true);
        run.thread.start();
        return run.id;
    }

    public synchronized Snapshot results(String id, long after) {
        return owned(id).snapshot(after);
    }

    public synchronized String stop(String id) {
        Run run = owned(id);
        stopRun(run);
        return run.state;
    }

    private Run owned(String id) {
        Run run = runs.get(id);
        if (run == null || !run.owner.equals(OrgContext.current()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "pipeline run not found");
        return run;
    }

    private void stopRun(Run run) {
        synchronized (run) {
            run.stopRequested = true;
            if (run.consumer != null) run.consumer.wakeup();
        }
        try {
            run.thread.join(12000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "interrupted while stopping pipeline");
        }
        if (run.thread.isAlive())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Kafka consumer has not stopped yet");
        synchronized (run) {
            if (!"FAILED".equals(run.state)) run.state = "STOPPED";
        }
    }

    @PreDestroy
    public synchronized void shutdown() {
        shuttingDown = true;
        if (active != null) stopRun(active);
    }

    private void consume(Run run) {
        Consumer<String, String> consumer = null;
        try {
            consumer = consumers.get();
            synchronized (run) { run.consumer = consumer; }
            if (run.stopRequested) return;
            if (consumer.partitionsFor(topic, Duration.ofSeconds(8)).isEmpty())
                throw new IllegalStateException("Kafka topic " + topic + " has no partitions");
            consumer.subscribe(List.of(topic));
            long nextHealthCheck = clock.getAsLong() + 10000;
            while (!run.stopRequested) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(250))) {
                    if (run.stopRequested) break;
                    Map<String, Object> row = position(record);
                    if (row == null) continue; // Malformed simulator records and other event types are not positions.
                    long now = clock.getAsLong();
                    synchronized (run) { run.eventsIn++; }
                    run.evaluator.accept(row, now);
                }
                long now = clock.getAsLong();
                run.evaluator.tick(now);
                synchronized (run) { run.droppedRows = run.evaluator.droppedRows(); }
                if (now >= nextHealthCheck) {
                    // poll alone can return empty forever when the broker is down. Force a bounded metadata request.
                    if (!consumer.listTopics(Duration.ofSeconds(8)).containsKey(topic))
                        throw new IllegalStateException("Kafka topic " + topic + " is unavailable");
                    nextHealthCheck = now + 10000;
                }
            }
        } catch (WakeupException e) {
            if (!run.stopRequested) run.fail("Kafka consumer interrupted unexpectedly");
        } catch (Exception e) {
            run.fail("Live Kafka pipeline failed: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        } finally {
            // Detach before closing: wakeup is thread-safe, but must never race a closed consumer.
            synchronized (run) { run.consumer = null; }
            if (consumer != null) {
                try {
                    consumer.close(Duration.ofSeconds(2));
                } catch (Exception e) {
                    run.fail("Kafka consumer close failed: " + e.getClass().getSimpleName()
                            + (e.getMessage() == null ? "" : ": " + e.getMessage()));
                }
            }
            synchronized (run) {
                run.droppedRows = run.evaluator.droppedRows();
                if (run.stopRequested && !"FAILED".equals(run.state)) run.state = "STOPPED";
            }
        }
    }

    Map<String, Object> position(ConsumerRecord<String, String> record) {
        try {
            JsonNode event = mapper.readTree(record.value());
            if (event == null || !"truck.position".equals(event.path("type").asText())) return null;
            JsonNode payload = event.path("payload");
            if (!event.path("eventTime").isIntegralNumber() || !event.path("eventTime").canConvertToLong()) return null;
            Map<String, Object> row = new LinkedHashMap<>();
            for (String field : STRING_FIELDS) {
                if (!payload.path(field).isTextual()) return null;
                row.put(field, payload.get(field).textValue());
            }
            if (!STATUSES.contains(row.get("status"))) return null;
            for (String field : NUMBER_FIELDS) {
                JsonNode value = payload.path(field);
                if (!value.isNumber() || !Double.isFinite(value.doubleValue())) return null;
                row.put(field, value.doubleValue());
            }
            row.put("eventTime", event.get("eventTime").longValue());
            row.put("partition", record.partition()); // Trusted Kafka metadata, not a payload/display mapping.
            return row;
        } catch (Exception e) {
            return null;
        }
    }

    public record Result(long seq, long ts, String output, String kind, Map<String, Object> data) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Snapshot(String runId, String state, String error, long startedAt,
                           long eventsIn, long droppedRows, List<Result> results) {}

    private final class Run {
        final String id;
        final String owner;
        final long startedAt;
        final PipelineEvaluator evaluator;
        final Deque<Result> buffer = new ArrayDeque<>();
        volatile boolean stopRequested;
        volatile Consumer<String, String> consumer;
        Thread thread;
        String state = "RUNNING";
        String error;
        long eventsIn;
        long droppedRows;

        Run(String id, String owner, long startedAt, JsonNode pipeline) {
            this.id = id;
            this.owner = owner;
            this.startedAt = startedAt;
            evaluator = new PipelineEvaluator(pipeline, this::emit);
        }

        synchronized void emit(PipelineEvaluator.Emission emission) {
            if (stopRequested) return;
            if (buffer.size() == 500) buffer.removeFirst();
            // Evaluator frames are immutable; retain the map instead of copying every result again.
            buffer.addLast(new Result(sequence.incrementAndGet(), clock.getAsLong(), emission.output(), emission.kind(), emission.data()));
        }

        synchronized void fail(String message) {
            if (!"FAILED".equals(state)) {
                state = "FAILED";
                error = message;
            }
        }

        synchronized Snapshot snapshot(long after) {
            List<Result> selected = new ArrayList<>();
            for (Result result : buffer) if (result.seq() > after) selected.add(result);
            return new Snapshot(id, state, error, startedAt, eventsIn, droppedRows, selected);
        }
    }
}
