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
    void differentSeedsChangeEventDataNotJustTheEventIdPrefix() throws Exception {
        List<Simulation.Emission> first = new Simulation(42, 1, 0, false).step(START + 600_000);
        List<Simulation.Emission> second = new Simulation(43, 1, 0, false).step(START + 600_000);
        int count = 100;
        assertTrue(first.size() >= count && second.size() >= count);
        List<JsonNode> firstEvents = new ArrayList<>();
        List<JsonNode> secondEvents = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            var left = (com.fasterxml.jackson.databind.node.ObjectNode) Json.MAPPER.readTree(first.get(i).json());
            var right = (com.fasterxml.jackson.databind.node.ObjectNode) Json.MAPPER.readTree(second.get(i).json());
            left.remove("eventId");
            right.remove("eventId");
            firstEvents.add(left);
            secondEvents.add(right);
        }
        assertNotEquals(firstEvents, secondEvents, "The seed must affect event data, not merely identifiers");
    }

    @Test
    void addingOtherTrucksDoesNotPerturbAnExistingTrucksSeededStream() {
        Simulation solo = new Simulation(42, 1, 0, false);
        Simulation fleet = new Simulation(42, 8, 0, false);
        assertEquals(solo.step(START + 600_000),
                fleet.step(START + 600_000).stream().filter(e -> e.key().equals("truck-001")).toList());
        fleet.addTruck(513.0, 713.0);
        assertEquals(solo.step(START + 1_200_000),
                fleet.step(START + 1_200_000).stream().filter(e -> e.key().equals("truck-001")).toList());
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
        assertTrue(Math.abs(600 - initial.path("x").asDouble()) < 1);
        assertEquals(713, initial.path("y").asDouble());
        double previousX = initial.path("x").asDouble();
        double previousY = initial.path("y").asDouble();
        for (int elapsed = 100; elapsed <= 30_000; elapsed += 100) {
            simulation.step(START + elapsed);
            JsonNode truck = simulation.state(true, 10).path("trucks").get(0);
            double x = truck.path("x").asDouble();
            double y = truck.path("y").asDouble();
            // Lane changes at corners add less than two metres to the centreline motion.
            assertTrue(Math.abs(x - previousX) + Math.abs(y - previousY) <= 50.0 / 3.6 / 10 + 2);
            assertTrue(Math.abs(x - City.grid(x)) < 1 || Math.abs(y - City.grid(y)) < 1);
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

    @Test
    void scenariosAreDeterministicAcrossStepSizesAndResetKeepsEventIdentity() throws Exception {
        Simulation coarse = new Simulation(42, 8, 0, true);
        Simulation fine = new Simulation(42, 8, 0, true);
        Set<String> ids = new HashSet<>();
        Map<String, Long> times = new HashMap<>();
        String[] scenarios = {"rush-hour", "breakdown", "surge", "reset", "breakdown", "reset"};
        long[] durations = {650_000, 750_000, 90_000, 400_000, 100_000, 200_000};
        for (int index = 0; index < scenarios.length; index++) {
            coarse.applyScenario(scenarios[index], index == 4 ? "truck-007" : null);
            fine.applyScenario(scenarios[index], index == 4 ? "truck-007" : null);
            long start = coarse.now();
            long end = start + durations[index];
            List<Simulation.Emission> actual = coarse.step(end);
            List<Simulation.Emission> expected = new ArrayList<>();
            for (long time = start; time < end; time += 1000) expected.addAll(fine.step(time));
            expected.addAll(fine.step(end));
            assertEquals(group(expected), group(actual));
            assertEquals(fine.state(true, 10), coarse.state(true, 10));
            for (Simulation.Emission record : actual) {
                JsonNode event = Json.MAPPER.readTree(record.json());
                validate(event, record.key());
                assertTrue(ids.add(event.path("eventId").asText()));
                long time = event.path("eventTime").asLong();
                assertTrue(time > times.getOrDefault(record.key(), Long.MIN_VALUE));
                assertTrue(time <= end);
                times.put(record.key(), time);
            }
        }
    }

    @Test
    void rushHourLowersCentreAverageAndExpiresAfterTenMinutes() throws Exception {
        Simulation normal = new Simulation(42, 8, 0, false);
        Simulation rush = new Simulation(42, 8, 0, false);
        rush.applyScenario("rush-hour", null);
        double normalAverage = centreAverage(normal.step(START + 599_000));
        double rushAverage = centreAverage(rush.step(START + 599_000));
        assertTrue(rushAverage < normalAverage, rushAverage + " < " + normalAverage);
        rush.step(START + 600_000);
        assertEquals("none", rush.state(true, 10).path("scenario").asText());
    }

    @Test
    void rushHourChangesSpeedAndMotionTogetherWithoutMovingTrucksAtActivation() {
        Simulation normal = new Simulation(42, 8, 0, false);
        normal.step(START + 5000);
        JsonNode before = normal.state(true, 10).path("trucks");
        normal.applyScenario("rush-hour", null);
        JsonNode after = normal.state(true, 10).path("trucks");
        for (int index = 0; index < before.size(); index++) {
            assertEquals(before.get(index).path("x"), after.get(index).path("x"));
            assertEquals(before.get(index).path("y"), after.get(index).path("y"));
            assertEquals(before.get(index).path("speedKmh").asDouble() * 0.6,
                    after.get(index).path("speedKmh").asDouble(), 1e-9);
        }
    }

    @Test
    void breakdownHoldsDemoTruckPastAlertThresholdAndResumes() throws Exception {
        Simulation simulation = new Simulation(42, 8, 0, false);
        simulation.step(START + 1_300_000);
        simulation.applyScenario("breakdown", "truck-007");
        JsonNode stopped = simulation.state(true, 10).path("trucks").get(6);
        long first = -1;
        long last = -1;
        boolean resumed = false;
        for (Simulation.Emission record : simulation.step(START + 2_300_000)) {
            JsonNode event = Json.MAPPER.readTree(record.json());
            if (!record.key().equals("truck-007") || !event.path("type").asText().equals("truck.position")) continue;
            JsonNode payload = event.path("payload");
            long time = event.path("eventTime").asLong();
            if (time < START + 2_200_000) {
                assertEquals(0, payload.path("speedKmh").asDouble());
                assertEquals("STOPPED", payload.path("status").asText());
                assertEquals(stopped.path("x"), payload.path("x"));
                assertEquals(stopped.path("y"), payload.path("y"));
                if (first < 0) first = time;
                if (last >= 0) assertEquals(2000, time - last);
                last = time;
            } else if (payload.path("speedKmh").asDouble() > 0) {
                resumed = true;
            }
        }
        assertTrue(last - first > 10 * 60_000);
        assertTrue(resumed);
        assertEquals("none", simulation.state(true, 10).path("scenario").asText());
    }

    @Test
    void surgeIsStaggeredAndResetRestoresInitialFleetAndCancelsPendingJoins() throws Exception {
        Simulation simulation = new Simulation(42, 8, 0, true);
        simulation.applyScenario("surge", null);
        for (int index = 1; index <= 6; index++) {
            simulation.step(START + index * 10_000 - 1);
            assertEquals(8 + index - 1, simulation.state(true, 10).path("trucks").size());
            simulation.step(START + index * 10_000);
            assertEquals(8 + index, simulation.state(true, 10).path("trucks").size());
        }
        simulation.removeTruck("truck-002");
        simulation.applyScenario("reset", null);
        assertEquals(8, simulation.state(true, 10).path("trucks").size());
        assertEquals("truck-002", simulation.state(true, 10).path("trucks").get(1).path("truckId").asText());
        simulation.applyScenario("surge", null);
        simulation.step(simulation.now() + 15_000);
        simulation.applyScenario("reset", null);
        for (Simulation.Emission record : simulation.step(START + 1_300_000)) {
            assertNotEquals("STOPPED", Json.MAPPER.readTree(record.json()).path("payload").path("status").asText());
        }
        assertEquals(8, simulation.state(true, 10).path("trucks").size());
    }

    @Test
    void trafficUsesFasterAvenuesEasesAtCornersAndSeparatesDepotTrucks() {
        assertEquals(50, City.speedLimit(new City.Point(600, 0), new City.Point(600, 200)));
        assertEquals(35, City.speedLimit(new City.Point(800, 0), new City.Point(800, 200)));
        City city = new City(42);
        Truck truck = new Truck(city, 42, 1, City.DEPOT, START, false);
        double initialSpeed = truck.speed;
        long arrival = truck.nextChange();
        truck.advance(START + (arrival - START) / 2);
        double midBlockSpeed = truck.speed;
        assertTrue(midBlockSpeed > initialSpeed);
        truck.advance(arrival - 1);
        assertTrue(truck.speed < midBlockSpeed);
        Simulation fleet = new Simulation(42, 14, 0, false);
        for (long elapsed = 0; elapsed <= 60_000; elapsed += 2000) {
            fleet.step(START + elapsed);
            Set<String> points = new HashSet<>();
            for (JsonNode state : fleet.state(true, 10).path("trucks")) {
                assertTrue(points.add(state.path("x").asText() + "," + state.path("y").asText()));
            }
        }
    }

    @Test
    void replacingBreakdownReleasesTruckButDoesNotCancelAutomaticDemo() {
        Simulation simulation = new Simulation(42, 8, 0, true);
        simulation.step(START + 299_000);
        simulation.applyScenario("breakdown", "truck-007");
        simulation.step(START + 300_000);
        simulation.applyScenario("surge", null);
        simulation.step(START + 1_199_000);
        assertEquals("STOPPED", simulation.state(true, 10).path("trucks").get(6).path("status").asText());
        simulation.step(START + 1_201_000);
        assertNotEquals("STOPPED", simulation.state(true, 10).path("trucks").get(6).path("status").asText());
        simulation.applyScenario("breakdown", "truck-001");
        simulation.applyScenario("rush-hour", null);
        assertNotEquals("STOPPED", simulation.state(true, 10).path("trucks").get(0).path("status").asText());
    }

    private static double centreAverage(List<Simulation.Emission> records) throws Exception {
        double total = 0;
        int count = 0;
        for (Simulation.Emission record : records) {
            JsonNode event = Json.MAPPER.readTree(record.json());
            JsonNode payload = event.path("payload");
            if (event.path("type").asText().equals("truck.position")
                    && City.inCentre(payload.path("x").asDouble(), payload.path("y").asDouble())) {
                total += payload.path("speedKmh").asDouble();
                count++;
            }
        }
        assertTrue(count > 0);
        return total / count;
    }

    private static Map<String, List<Simulation.Emission>> group(List<Simulation.Emission> records) {
        Map<String, List<Simulation.Emission>> grouped = new HashMap<>();
        for (Simulation.Emission record : records) grouped.computeIfAbsent(record.key(), ignored -> new ArrayList<>()).add(record);
        return grouped;
    }

    private static void validate(JsonNode event, String key) {
        assertTrue(event.isObject());
        Set<String> fields = new HashSet<>();
        event.fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("eventId", "schemaVersion", "type", "eventTime", "key", "payload"), fields);
        assertTrue(event.path("eventId").isTextual());
        assertFalse(event.path("eventId").asText().isEmpty());
        assertTrue(event.path("schemaVersion").isIntegralNumber());
        assertEquals(1, event.path("schemaVersion").intValue());
        assertTrue(event.path("eventTime").isIntegralNumber());
        assertTrue(event.path("eventTime").canConvertToLong());
        assertTrue(event.path("type").isTextual());
        assertTrue(event.path("key").isTextual());
        assertEquals(key, event.path("key").asText());
        JsonNode payload = event.path("payload");
        assertTrue(payload.isObject());
        assertTrue(payload.path("truckId").isTextual());
        assertEquals(key, payload.path("truckId").asText());
        Set<String> payloadFields = new HashSet<>();
        payload.fieldNames().forEachRemaining(payloadFields::add);
        for (String coordinate : List.of("x", "y")) {
            assertTrue(payload.path(coordinate).isNumber());
            assertTrue(payload.path(coordinate).asDouble() >= 0 && payload.path(coordinate).asDouble() <= 2000);
        }
        if (event.path("type").asText().equals("truck.position")) {
            assertEquals(Set.of("truckId", "x", "y", "speedKmh", "heading", "routeId", "status"), payloadFields);
            assertTrue(payload.path("speedKmh").isNumber());
            assertTrue(payload.path("speedKmh").asDouble() >= 0);
            assertTrue(payload.path("heading").isNumber());
            assertTrue(payload.path("heading").asDouble() >= 0 && payload.path("heading").asDouble() <= 360);
            assertTrue(payload.path("routeId").isTextual());
            assertTrue(Set.of("DRIVING", "UNLOADING", "IDLE", "STOPPED").contains(payload.path("status").asText()));
        } else {
            assertEquals("truck.delivery", event.path("type").asText());
            assertEquals(Set.of("truckId", "stopId", "x", "y", "parcels"), payloadFields);
            assertTrue(payload.path("stopId").isTextual());
            assertTrue(payload.path("parcels").isIntegralNumber());
            assertTrue(payload.path("parcels").intValue() >= 1);
        }
    }
}
