package io.aetherstream.simulator;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.aetherstream.common.Json;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
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
