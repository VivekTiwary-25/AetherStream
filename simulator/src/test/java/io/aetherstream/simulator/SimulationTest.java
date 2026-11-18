package io.aetherstream.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import io.aetherstream.common.Json;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class SimulationTest {
    private static final long START = Simulation.START_EPOCH_MS;

    @Test
    void seed42IsByteIdenticalForThirtyMinutesAndTheSameApiCalls() {
        Simulation first = new Simulation(42);
        Simulation second = new Simulation(42);
        for (long elapsed = 0; elapsed <= 30 * 60_000; elapsed += 1000) {
            assertEquals(first.step(START + elapsed), second.step(START + elapsed));
            if (elapsed == 120_000) {
                assertEquals(first.addTruck(513.0, 713.0), second.addTruck(513.0, 713.0));
                assertEquals(first.lateEvents(20, 180_000), second.lateEvents(20, 180_000));
                assertEquals(first.duplicates(20), second.duplicates(20));
                assertEquals(first.malformed(5), second.malformed(5));
            }
            if (elapsed == 600_000) assertEquals(first.removeTruck("truck-003"), second.removeTruck("truck-003"));
        }
    }

    @Test
    void allNormalEventsMeetWireContractAndHaveStrictPerTruckTimes() throws Exception {
        Simulation simulation = new Simulation(42, 8, 0, true);
        Map<String, Long> times = new HashMap<>();
        Map<String, Long> sequences = new HashMap<>();
        Set<String> ids = new HashSet<>();
        Set<String> delivered = new HashSet<>();
        for (long elapsed = 0; elapsed <= 20 * 60_000; elapsed += 1000) {
            for (Simulation.Emission record : simulation.step(START + elapsed)) {
                JsonNode event = Json.MAPPER.readTree(record.json());
                validate(event, record.key());
                String id = event.path("eventId").asText();
                assertTrue(ids.add(id), "Unique logical event: " + id);
                long sequence = sequences.merge(record.key(), 1L, Long::sum);
                assertEquals("s42-" + record.key() + "-" + String.format(java.util.Locale.ROOT, "%06d", sequence), id);
                long time = event.path("eventTime").longValue();
                assertTrue(time > times.getOrDefault(record.key(), Long.MIN_VALUE));
                assertTrue(time <= START + elapsed);
                times.put(record.key(), time);
                if (event.path("type").asText().equals("truck.delivery")) delivered.add(record.key());
            }
        }
        assertEquals(Set.of("truck-001", "truck-002", "truck-003", "truck-004", "truck-005", "truck-006", "truck-007", "truck-008"), delivered);
    }

    @Test
    void demoTruckIsStoppedForFifteenMinutesThenResumes() throws Exception {
        Simulation simulation = new Simulation(42, 8, 0, true);
        long consecutive = 0;
        boolean resumed = false;
        for (long elapsed = 0; elapsed <= 21 * 60_000; elapsed += 1000) {
            for (Simulation.Emission record : simulation.step(START + elapsed)) {
                JsonNode event = Json.MAPPER.readTree(record.json());
                if (!record.key().equals("truck-007") || !event.path("type").asText().equals("truck.position")) continue;
                long offset = event.path("eventTime").asLong() - START;
                JsonNode payload = event.path("payload");
                if (offset >= 300_000 && offset < 1_200_000) {
                    assertEquals("STOPPED", payload.path("status").asText());
                    assertEquals(0, payload.path("speedKmh").asDouble());
                    consecutive += 2000;
                }
                if (offset >= 1_200_000 && payload.path("status").asText().equals("DRIVING")) resumed = true;
            }
        }
        assertEquals(900_000, consecutive);
        assertTrue(resumed);
    }

    @Test
    void disabledDemoDoesNotStopTruckSeven() throws Exception {
        Simulation simulation = new Simulation(42, 8, 0, false);
        for (Simulation.Emission record : simulation.step(START + 21 * 60_000)) {
            assertNotEquals("STOPPED", Json.MAPPER.readTree(record.json()).path("payload").path("status").asText());
        }
    }

    @Test
    void chaosUsesLateTimesFreshIdsAndVerbatimDuplicates() throws Exception {
        Simulation simulation = new Simulation(42, 8, 0, false);
        List<Simulation.Emission> original = simulation.step(START + 60_000);
        assertEquals(original.subList(original.size() - 20, original.size()), simulation.duplicates(20));
        Set<String> ids = new HashSet<>();
        for (int call = 0; call < 2; call++) {
            List<Simulation.Emission> late = simulation.lateEvents(20, 180_000);
            assertEquals(20, late.size());
            for (int i = 0; i < late.size(); i++) {
                Simulation.Emission record = late.get(i);
                JsonNode event = Json.MAPPER.readTree(record.json());
                validate(event, record.key());
                assertEquals(simulation.now() - 180_000, event.path("eventTime").longValue());
                assertTrue(event.path("eventId").asText().endsWith("-late-" + (call * 20 + i + 1)));
                assertTrue(ids.add(event.path("eventId").asText()));
            }
        }
        for (Simulation.Emission record : simulation.malformed(5)) {
            JsonNode event = Json.MAPPER.readTree(record.json());
            assertEquals(record.key(), event.path("key").asText());
            assertEquals(record.key(), event.path("payload").path("truckId").asText());
            assertTrue(event.path("payload").path("speedKmh").asDouble() < 0);
        }
    }

    @Test
    void seededCorruptionPreservesPartitionKeysAndCanBeDisabled() throws Exception {
        Simulation simulation = new Simulation(42, 2, 1, false);
        for (Simulation.Emission record : simulation.step(START + 10_000)) {
            JsonNode event = Json.MAPPER.readTree(record.json());
            assertEquals(record.key(), event.path("key").asText());
            assertEquals(record.key(), event.path("payload").path("truckId").asText());
            assertFalse(event.path("payload").has("speedKmh"));
        }
    }

    @Test
    void addedTruckSnapsToStreetAndMovesContinuouslyAndRemovalStopsEvents() throws Exception {
        Simulation simulation = new Simulation(42, 0, 0, false);
        String id = simulation.addTruck(513.0, 713.0);
        JsonNode initial = simulation.state(true, 10).path("trucks").get(0);
        assertEquals(600, initial.path("x").asDouble());
        assertEquals(713, initial.path("y").asDouble());
        double previousX = initial.path("x").asDouble();
        double previousY = initial.path("y").asDouble();
        for (int elapsed = 100; elapsed <= 30_000; elapsed += 100) {
            simulation.step(START + elapsed);
            JsonNode truck = simulation.state(true, 10).path("trucks").get(0);
            double x = truck.path("x").asDouble();
            double y = truck.path("y").asDouble();
            assertTrue(Math.abs(x - previousX) + Math.abs(y - previousY) <= 50.0 / 3.6 / 10 + 0.001);
            assertTrue(Math.abs(x % 200) < 0.000001 || Math.abs(y % 200) < 0.000001);
            previousX = x;
            previousY = y;
        }
        assertTrue(simulation.removeTruck(id));
        assertTrue(simulation.step(START + 40_000).isEmpty());
        assertEquals("truck-002", simulation.addTruck(null, null));
        assertThrows(IllegalArgumentException.class, () -> simulation.step(START));
    }

    @Test
    void coarseAndFineSteppingHaveIdenticalPerTruckEvents() {
        Simulation coarse = new Simulation(42, 8, 0, true);
        Simulation fine = new Simulation(42, 8, 0, true);
        Map<String, List<Simulation.Emission>> expected = group(coarse.step(START + 30 * 60_000));
        List<Simulation.Emission> records = new ArrayList<>();
        for (long elapsed = 0; elapsed <= 30 * 60_000; elapsed += 1000) records.addAll(fine.step(START + elapsed));
        assertEquals(expected, group(records));
    }

    private static Map<String, List<Simulation.Emission>> group(List<Simulation.Emission> records) {
        Map<String, List<Simulation.Emission>> grouped = new HashMap<>();
        for (Simulation.Emission record : records) grouped.computeIfAbsent(record.key(), ignored -> new ArrayList<>()).add(record);
        return grouped;
    }

    private static void validate(JsonNode event, String key) {
        assertTrue(event.isObject());
        assertEquals(6, event.size());
        assertTrue(event.path("eventId").isTextual());
        assertFalse(event.path("eventId").asText().isEmpty());
        assertTrue(event.path("schemaVersion").isIntegralNumber());
        assertEquals(1, event.path("schemaVersion").intValue());
        assertTrue(event.path("eventTime").isIntegralNumber());
        assertTrue(event.path("key").isTextual());
        assertEquals(key, event.path("key").asText());
        JsonNode payload = event.path("payload");
        assertTrue(payload.isObject());
        assertTrue(payload.path("truckId").isTextual());
        assertEquals(key, payload.path("truckId").asText());
        for (String coordinate : List.of("x", "y")) {
            assertTrue(payload.path(coordinate).isNumber());
            assertTrue(payload.path(coordinate).asDouble() >= 0 && payload.path(coordinate).asDouble() <= 2000);
        }
        if (event.path("type").asText().equals("truck.position")) {
            assertTrue(payload.path("speedKmh").isNumber());
            assertTrue(payload.path("speedKmh").asDouble() >= 0);
            assertTrue(payload.path("heading").isNumber());
            assertTrue(payload.path("heading").asDouble() >= 0 && payload.path("heading").asDouble() <= 360);
            assertTrue(payload.path("routeId").isTextual());
            assertTrue(Set.of("DRIVING", "UNLOADING", "IDLE", "STOPPED").contains(payload.path("status").asText()));
        } else {
            assertEquals("truck.delivery", event.path("type").asText());
            assertTrue(payload.path("stopId").isTextual());
            assertTrue(payload.path("parcels").isIntegralNumber());
            assertTrue(payload.path("parcels").intValue() >= 1);
        }
    }
}
