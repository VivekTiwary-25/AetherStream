package io.aetherstream.simulator;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.aetherstream.common.Json;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

class SimulatorRuntimeTest {
    private static final long ANCHOR = SimulatorRuntime.DEFAULT_ANCHOR_EPOCH_MS;

    private static SimulatorConfig config(double scale) {
        return new SimulatorConfig(42, scale, 8, 0, false, "unused:9092", "truck-events");
    }

    @Test
    void twoRestartsKeepTruckTimesStrictAndIdsUniqueIncludingChangedStartupScale() throws Exception {
        AtomicLong wall = new AtomicLong(ANCHOR + 3_600_000);
        Map<String, Long> lastTimes = new HashMap<>();
        Set<String> ids = new HashSet<>();
        OptionalLong kafkaTail = OptionalLong.empty();
        for (int run = 0; run < 3; run++) {
            double scale = run == 1 ? 20 : 10;
            SimulatorRuntime runtime = new SimulatorRuntime(config(scale), ANCHOR, wall::get, kafkaTail);
            long floor = Simulation.START_EPOCH_MS + (wall.get() - ANCHOR) * (long) scale;
            if (kafkaTail.isPresent()) floor = Math.max(floor, kafkaTail.getAsLong() + Simulation.POSITION_INTERVAL_MS);
            assertEquals(floor, runtime.simulation.now());
            List<Simulation.Emission> first = runtime.tick();
            assertEquals(8, first.size(), "No historical catch-up from the pure simulation epoch");
            verify(first, lastTimes, ids);
            for (int tick = 0; tick < 60; tick++) {
                wall.addAndGet(1000);
                verify(runtime.tick(), lastTimes, ids);
            }
            // A restart can happen with no wall-clock advancement at all.
            kafkaTail = OptionalLong.of(lastTimes.values().stream().mapToLong(Long::longValue).max().orElseThrow());
        }
        assertEquals(8, lastTimes.size());
    }

    @Test
    void wallAnchorAdvancesOfflineRestartsAndRunIdsSurviveIdenticalStartTimes() throws Exception {
        AtomicLong wall = new AtomicLong(ANCHOR + 1000);
        SimulatorRuntime first = new SimulatorRuntime(config(10), ANCHOR, wall::get, OptionalLong.empty());
        JsonNode before = Json.MAPPER.readTree(first.tick().getFirst().json());
        wall.addAndGet(1000);
        SimulatorRuntime second = new SimulatorRuntime(config(10), ANCHOR, wall::get, OptionalLong.empty());
        JsonNode after = Json.MAPPER.readTree(second.tick().getFirst().json());
        assertEquals(10_000, after.path("eventTime").longValue() - before.path("eventTime").longValue());
        assertNotEquals(before.path("eventId"), after.path("eventId"));
        SimulatorRuntime sameInstant = new SimulatorRuntime(config(10), ANCHOR, wall::get, OptionalLong.empty());
        assertNotEquals(after.path("eventId"), Json.MAPPER.readTree(sameInstant.tick().getFirst().json()).path("eventId"));
    }

    @Test
    void controlReanchorsAtCurrentTimeAndPauseAndWallRollbackNeverRewind() throws Exception {
        AtomicLong wall = new AtomicLong(ANCHOR + 1000);
        SimulatorRuntime runtime = new SimulatorRuntime(config(10), ANCHOR, wall::get, OptionalLong.empty());
        Map<String, Long> lastTimes = new HashMap<>();
        Set<String> ids = new HashSet<>();
        verify(runtime.tick(), lastTimes, ids);
        long initial = runtime.simulation.now();
        try (HttpApi api = new HttpApi(new InetSocketAddress("127.0.0.1", 0), runtime.simulation, runtime, ignored -> {});
             HttpClient client = HttpClient.newHttpClient()) {
            api.start();
            wall.addAndGet(500);
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + api.port() + "/control"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"timeScale\":2}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals(2, Json.MAPPER.readTree(response.body()).path("timeScale").doubleValue());
            wall.addAndGet(1000);
            synchronized (runtime.simulation) {
                verify(runtime.tick(), lastTimes, ids);
                assertEquals(initial + 7000, runtime.simulation.now());
                runtime.setRunning(false);
                wall.addAndGet(60_000);
                assertTrue(runtime.tick().isEmpty());
                runtime.setRunning(true);
                assertTrue(runtime.tick().isEmpty());
                wall.addAndGet(-5000);
                assertTrue(runtime.tick().isEmpty());
                assertEquals(initial + 7000, runtime.simulation.now());
                wall.addAndGet(6000);
                verify(runtime.tick(), lastTimes, ids);
                assertEquals(initial + 9000, runtime.simulation.now());
            }
        }
    }

    @Test
    void restartAfterLateEventAndVerbatimDuplicateDoesNotRewind() throws Exception {
        verifyRestartAfterChaosTail(true);
    }

    @Test
    void restartImmediatelyAfterPostChaosLateDoesNotRewind() throws Exception {
        verifyRestartAfterChaosTail(false);
    }

    private static void verifyRestartAfterChaosTail(boolean duplicate) throws Exception {
        AtomicLong wall = new AtomicLong(ANCHOR + 1000);
        SimulatorRuntime first = new SimulatorRuntime(config(20), ANCHOR, wall::get, OptionalLong.empty());
        List<Simulation.Emission> log = new ArrayList<>(first.tick());
        wall.addAndGet(10_000);
        log.addAll(first.tick());
        Map<String, Long> times = new HashMap<>();
        Set<String> ids = new HashSet<>();
        verify(log, times, ids);
        long maximum = times.values().stream().mapToLong(Long::longValue).max().orElseThrow();

        try (HttpApi api = new HttpApi(new InetSocketAddress("127.0.0.1", 0),
                first.simulation, first, log::addAll);
             HttpClient client = HttpClient.newHttpClient()) {
            api.start();
            URI base = URI.create("http://127.0.0.1:" + api.port());
            HttpResponse<String> late = client.send(HttpRequest.newBuilder(base.resolve("/chaos/late"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"count\":20,\"lateByMs\":180000}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, late.statusCode());
            assertEquals(20, Json.MAPPER.readTree(late.body()).path("sent").intValue());
            Simulation.Emission last = log.getLast();
            JsonNode event = Json.MAPPER.readTree(last.json());
            assertTrue(event.path("eventId").textValue().contains("-late-"));
            assertEquals(maximum - 180_000, event.path("eventTime").longValue());
            if (duplicate) {
                HttpResponse<String> replay = client.send(HttpRequest.newBuilder(base.resolve("/chaos/duplicates"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"count\":1}")).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, replay.statusCode());
                assertEquals(1, Json.MAPPER.readTree(replay.body()).path("sent").intValue());
                assertEquals(last, log.getLast());
            }
        }

        OptionalLong recovered = recoverTail(log);
        assertEquals(OptionalLong.of(maximum), recovered);
        // A lower startup scale makes the Kafka fence, rather than wall time, authoritative.
        SimulatorRuntime restarted = new SimulatorRuntime(config(1), ANCHOR, wall::get, recovered);
        List<Simulation.Emission> resumed = restarted.tick();
        verify(resumed, times, ids);
        for (Simulation.Emission emission : resumed) {
            assertTrue(Json.MAPPER.readTree(emission.json()).path("eventTime").longValue() > maximum);
        }
    }

    private static OptionalLong recoverTail(List<Simulation.Emission> log) {
        String topic = "truck-events";
        TopicPartition partition = new TopicPartition(topic, 0);
        try (MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.NONE)) {
            consumer.updatePartitions(topic, List.of(new PartitionInfo(topic, 0, null, null, null)));
            consumer.updateBeginningOffsets(Map.of(partition, 0L));
            consumer.updateEndOffsets(Map.of(partition, (long) log.size()));
            consumer.schedulePollTask(() -> {
                for (long offset = consumer.position(partition); offset < log.size(); offset++) {
                    Simulation.Emission emission = log.get((int) offset);
                    consumer.addRecord(new ConsumerRecord<>(topic, 0, offset, emission.key(), emission.json()));
                }
            });
            return KafkaEventTime.latest(consumer, topic, Duration.ofSeconds(1));
        }
    }

    private static void verify(List<Simulation.Emission> records, Map<String, Long> times, Set<String> ids)
            throws Exception {
        for (Simulation.Emission record : records) {
            JsonNode event = Json.MAPPER.readTree(record.json());
            long time = event.path("eventTime").longValue();
            String id = event.path("eventId").textValue();
            assertTrue(time > times.getOrDefault(record.key(), Long.MIN_VALUE), record.json());
            assertTrue(ids.add(id), id);
            assertTrue(id.matches("s42-truck-[0-9]{3}-[0-9]{6,}"), id);
            times.put(record.key(), time);
        }
    }
}
