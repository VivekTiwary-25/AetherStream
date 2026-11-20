package io.aetherstream.api.glorious;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.test.KafkaClusterTestKit;
import org.apache.kafka.common.test.TestKitNodes;
import org.apache.kafka.common.utils.Utils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Real KRaft broker, network producer, and PipelineRuns consumer loop; no Docker or mocks. */
@Timeout(90)
class PipelineRunsKafkaIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int PARTITIONS = 6;
    @TempDir static Path brokerDirectory;
    private static KafkaClusterTestKit broker;

    @BeforeAll
    static void startBroker() throws Exception {
        long started = System.nanoTime();
        broker = new KafkaClusterTestKit.Builder(new TestKitNodes.Builder()
                .setBaseDirectory(brokerDirectory).setCombined(true)
                .setNumBrokerNodes(1).setNumControllerNodes(1).build())
                .setConfigProp("offsets.topic.replication.factor", "1")
                .setConfigProp("offsets.topic.num.partitions", "1")
                .setConfigProp("transaction.state.log.replication.factor", "1")
                .setConfigProp("transaction.state.log.min.isr", "1")
                .setConfigProp("group.initial.rebalance.delay.ms", "0")
                .setConfigProp("auto.create.topics.enable", "false")
                .build();
        broker.format();
        broker.startup();
        broker.waitForReadyBrokers();
        report(started, "KRaft broker ready; Java=" + System.getProperty("java.version")
                + "; Kafka=" + org.apache.kafka.common.utils.AppInfoParser.getVersion());
    }

    @AfterAll
    static void stopBroker() throws Exception {
        if (broker != null) broker.close();
    }

    @Test
    void speedingAlertsExcludeTheSixtyKmhBoundaryAndSlowerTrucks() throws Exception {
        long started = System.nanoTime();
        try (Session session = new Session()) {
            session.start("speeding");
            double[] speeds = {0, 59.99, 60, 60.01, 75, 100};
            for (int i = 0; i < speeds.length; i++)
                session.send("speed-" + i, speeds[i], System.currentTimeMillis());
            session.await(6, 6);
            var snapshot = session.stop();
            assertThat(snapshot.eventsIn()).isEqualTo(6);
            assertThat(snapshot.results()).hasSize(6).allSatisfy(result ->
                    assertThat(result.output()).isEqualTo("Speeding trucks"));
            var alerts = snapshot.results().stream().filter(result -> result.kind().equals("alert")).toList();
            assertThat(alerts).hasSize(3);
            assertThat(alerts).extracting(result -> result.data().get("truckId"))
                    .containsExactlyInAnyOrder("speed-3", "speed-4", "speed-5");
            assertThat(alerts).extracting(result -> result.data().get("value"))
                    .containsExactlyInAnyOrder(60.01, 75.0, 100.0);
            assertThat(alerts).allSatisfy(result -> {
                assertThat(result.data()).containsEntry("field", "speedKmh")
                        .containsEntry("message", "Truck over 60 km/h");
                assertThat(((Number) result.data().get("value")).doubleValue()).isGreaterThan(60);
            });
            var rows = snapshot.results().stream().filter(result -> result.kind().equals("row")).toList();
            assertThat(rows).hasSize(3);
            assertThat(rows).extracting(result -> result.data().get("speedKmh"))
                    .containsExactlyInAnyOrder(60.01, 75.0, 100.0);
            report(started, "speeding PASS: 6 inputs; only 60.01, 75, 100 emit (3 alerts + 3 rows);"
                    + " 0, 59.99, 60 excluded; 6 historical records skipped");
        }
    }

    @Test
    void thirtySecondWindowsEmitExactPerTruckAveragesOnlyAfterClosing() throws Exception {
        long started = System.nanoTime();
        try (Session session = new Session()) {
            session.start("avg-speed-30s");
            // Future event-time bucket avoids racing wall-clock idle finalization on a slow host.
            // Its size stays the example's real 30 seconds; a Kafka watermark event closes it.
            long windowStart = (Math.floorDiv(System.currentTimeMillis(), 30_000) + 1) * 30_000;
            long windowEnd = windowStart + 30_000;
            session.send("average-a", 10, windowStart + 1_000);
            session.send("average-a", 30, windowStart + 2_000);
            session.send("average-b", 50, windowStart + 3_000);
            session.send("average-b", 90, windowStart + 4_000);
            assertThat(session.await(4, 0).results()).as("window still open").isEmpty();
            // Wait for all four inputs before advancing the watermark across Kafka partitions.
            session.send("window-boundary", 0, windowEnd);
            session.await(5, 2);
            var snapshot = session.stop();
            assertThat(snapshot.eventsIn()).isEqualTo(5);
            assertThat(snapshot.results()).hasSize(2).allSatisfy(result -> {
                assertThat(result.kind()).isEqualTo("average");
                assertThat(result.output()).isEqualTo("30-second average speed");
                assertThat(result.data()).containsEntry("field", "speedKmh").containsEntry("count", 2L)
                        .containsEntry("windowStart", windowStart).containsEntry("windowEnd", windowEnd);
            });
            assertThat(snapshot.results()).extracting(result -> result.data().get("group"))
                    .containsExactlyInAnyOrder("average-a", "average-b");
            Map<Object, Object> averages = new LinkedHashMap<>();
            snapshot.results().forEach(result -> averages.put(result.data().get("group"), result.data().get("avg")));
            assertThat(averages).containsExactlyInAnyOrderEntriesOf(Map.of("average-a", 20.0, "average-b", 70.0));
            report(started, "avg-speed-30s PASS: no output before closure; [10,30] => 20 and [50,90] => 70;"
                    + " count=2 each, window=" + windowStart + ".." + windowEnd
                    + "; closed by real Kafka event-time watermark (not a 30-second wall-clock wait)");
        }
    }

    @Test
    void partitionTwoProjectionMatchesActualKafkaRecordMetadata() throws Exception {
        long started = System.nanoTime();
        try (Session session = new Session()) {
            session.start("partition-2");
            List<Integer> actualPartitions = new ArrayList<>();
            List<Map<String, Object>> expected = new ArrayList<>();
            // Choose keys covering every partition, but let the real producer route them normally.
            for (int partition = 0; partition < PARTITIONS; partition++) {
                for (int sample = 0; sample < 2; sample++) {
                    String truckId = keyForPartition(partition, sample);
                    double speed = 10 + partition * 10 + sample;
                    RecordMetadata metadata = session.send(truckId, speed, System.currentTimeMillis());
                    actualPartitions.add(metadata.partition());
                    assertThat(metadata.partition()).isEqualTo(partition);
                    if (metadata.partition() == 2) expected.add(Map.of("truckId", truckId, "speedKmh", speed));
                }
            }
            assertThat(actualPartitions).contains(0, 1, 2, 3, 4, 5);
            session.await(12, 2);
            var snapshot = session.stop();
            assertThat(snapshot.eventsIn()).isEqualTo(12);
            assertThat(snapshot.results()).hasSize(2).allSatisfy(result -> {
                assertThat(result.kind()).isEqualTo("row");
                assertThat(result.output()).isEqualTo("Partition 2 speeds");
                assertThat(result.data()).containsOnlyKeys("truckId", "speedKmh");
            });
            assertThat(snapshot.results()).extracting(PipelineRuns.Result::data)
                    .containsExactlyInAnyOrderElementsOf(expected);
            report(started, "partition-2 PASS: 12 keyed inputs across actual Kafka partitions 0..5;"
                    + " only partition 2's 2 records emitted, exactly truckId+speedKmh=" + expected);
        }
    }

    private static String keyForPartition(int partition, int sample) {
        for (int candidate = 0; ; candidate++) {
            String key = "partition-" + partition + "-sample-" + sample + "-truck-" + candidate;
            if (Utils.toPositive(Utils.murmur2(key.getBytes(StandardCharsets.UTF_8))) % PARTITIONS == partition)
                return key;
        }
    }

    private static JsonNode example(String name) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("schemas/examples"))) root = root.getParent();
        assertThat(root).as("repository schemas/examples directory").isNotNull();
        JsonNode pipeline = MAPPER.readTree(root.resolve("schemas/examples/" + name + ".json").toFile()).path("pipeline");
        assertThat(new PipelineValidator().validate(pipeline).errors()).as(name).isEmpty();
        return pipeline;
    }

    /** Same envelope and payload fields/types as simulator EventFactory.position/envelope. */
    private static String position(String truckId, double speed, long eventTime, long sequence) throws Exception {
        ObjectNode event = MAPPER.createObjectNode().put("eventId", "s42-" + truckId + "-integration-"
                        + String.format(java.util.Locale.ROOT, "%06d", sequence))
                .put("schemaVersion", 1).put("type", "truck.position")
                .put("eventTime", eventTime).put("key", truckId);
        event.putObject("payload").put("truckId", truckId).put("x", 12.5).put("y", 24.5)
                .put("speedKmh", speed).put("heading", 90.0).put("routeId", "route-1").put("status", "DRIVING");
        return MAPPER.writeValueAsString(event);
    }

    private static void report(long started, String evidence) {
        System.out.printf("KAFKA-E2E %.3fs %s%n", (System.nanoTime() - started) / 1_000_000_000.0, evidence);
    }

    private static final class Session implements AutoCloseable {
        private final String topic = "truck-events-" + UUID.randomUUID();
        private final KafkaProducer<String, String> producer;
        private final PipelineRuns runs;
        private final CountDownLatch assigned = new CountDownLatch(1);
        private final AtomicReference<Map<TopicPartition, Long>> initialPositions = new AtomicReference<>();
        private String runId;
        private long sequence;

        Session() throws Exception {
            try (Admin admin = broker.admin()) {
                admin.createTopics(List.of(new NewTopic(topic, PARTITIONS, (short) 1))).all().get(15, TimeUnit.SECONDS);
                assertThat(admin.describeTopics(List.of(topic)).allTopicNames().get(15, TimeUnit.SECONDS)
                        .get(topic).partitions()).hasSize(PARTITIONS);
            }
            Properties props = new Properties();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.bootstrapServers());
            props.put(ProducerConfig.ACKS_CONFIG, "all");
            props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000);
            props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
            props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 15_000);
            producer = new KafkaProducer<>(props, new StringSerializer(), new StringSerializer());
            runs = new PipelineRuns(MAPPER, () -> {
                // Real KafkaConsumer; the override only observes readiness on its owning thread.
                // Group isolation/latest/no auto-commit mirror PipelineRuns' production factory.
                Properties consumerProps = new Properties();
                consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.bootstrapServers());
                consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "glorious-pipelines-e2e-" + UUID.randomUUID());
                consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
                consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
                consumerProps.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
                consumerProps.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 8_000);
                consumerProps.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
                consumerProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100);
                return new KafkaConsumer<String, String>(consumerProps, new StringDeserializer(), new StringDeserializer()) {
                    @Override public ConsumerRecords<String, String> poll(Duration timeout) {
                        ConsumerRecords<String, String> records = super.poll(timeout);
                        if (assigned.getCount() != 0 && assignment().size() == PARTITIONS) {
                            Map<TopicPartition, Long> positions = new LinkedHashMap<>();
                            for (TopicPartition partition : assignment()) positions.put(partition, position(partition));
                            initialPositions.set(Map.copyOf(positions));
                            assigned.countDown();
                        }
                        return records;
                    }
                };
            }, topic, System::currentTimeMillis);
        }

        void start(String name) throws Exception {
            // Nonempty history makes latest-offset behavior observable on all six partitions.
            for (int partition = 0; partition < PARTITIONS; partition++) {
                String truckId = "historical-" + partition;
                producer.send(new ProducerRecord<>(topic, partition, truckId,
                        position(truckId, 999, System.currentTimeMillis(), ++sequence))).get(15, TimeUnit.SECONDS);
            }
            runId = runs.start(example(name));
            assertThat(assigned.await(25, TimeUnit.SECONDS)).as("Kafka assignment: %s", runs.results(runId, 0)).isTrue();
            assertThat(initialPositions.get()).hasSize(PARTITIONS);
            assertThat(initialPositions.get().values()).containsOnly(1L);
            assertThat(runs.results(runId, 0).eventsIn()).as("latest skips pre-run history").isZero();
        }

        RecordMetadata send(String truckId, double speed, long eventTime) throws Exception {
            return producer.send(new ProducerRecord<>(topic, truckId, position(truckId, speed, eventTime, ++sequence)))
                    .get(15, TimeUnit.SECONDS);
        }

        PipelineRuns.Snapshot await(long events, int results) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            PipelineRuns.Snapshot snapshot;
            do {
                snapshot = runs.results(runId, 0);
                assertThat(snapshot.error()).as("Kafka pipeline failure").isNull();
                if (snapshot.eventsIn() >= events && snapshot.results().size() >= results) return snapshot;
                Thread.sleep(20);
            } while (System.nanoTime() < deadline);
            throw new AssertionError("Timed out awaiting events=" + events + ", results=" + results + ": " + snapshot);
        }

        PipelineRuns.Snapshot stop() {
            assertThat(runs.stop(runId)).isEqualTo("STOPPED");
            var snapshot = runs.results(runId, 0);
            assertThat(snapshot.error()).isNull();
            assertThat(snapshot.droppedRows()).isZero();
            return snapshot;
        }

        @Override public void close() {
            try {
                runs.shutdown();
            } finally {
                producer.close(Duration.ofSeconds(5));
            }
        }
    }
}
