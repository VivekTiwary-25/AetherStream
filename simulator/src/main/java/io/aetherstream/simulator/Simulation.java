package io.aetherstream.simulator;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aetherstream.common.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/** Deterministic, thread-free simulation. Callers serialize access, including API calls. */
public final class Simulation {
    public static final long START_EPOCH_MS = 1795251600000L;
    public static final long POSITION_INTERVAL_MS = 2000;
    public record Emission(String key, String json) {}

    private final long seed;
    private final double malformedRate;
    private final boolean stuckTruckDemo;
    private final int initialTrucks;
    private final City city;
    private final EventFactory events;
    private final SplittableRandom chaos;
    private final Map<String, Truck> trucks = new LinkedHashMap<>();
    private final Map<String, Long> lastEventTimes = new LinkedHashMap<>();
    private final Map<String, Long> retiredSequences = new LinkedHashMap<>();
    // Retain emitted records for exact replay, including deliberate duplicates.
    // Full history has an uptime-dependent memory cost; the wire contract places no limit on N.
    private final List<Emission> history = new ArrayList<>();
    private long now = START_EPOCH_MS;
    private long emitted;
    private long lateSequence;
    private int nextTruckIndex;
    private String scenario = "none";
    private long scenarioEnd = Long.MAX_VALUE;
    private long nextSurge = Long.MAX_VALUE;
    private int surgeRemaining;
    private Truck breakdownTruck;

    public Simulation(long seed) { this(seed, 8, 0.0025, true); }

    public Simulation(long seed, int initialTrucks, double malformedRate, boolean stuckTruckDemo) {
        this(seed, initialTrucks, malformedRate, stuckTruckDemo, START_EPOCH_MS, "");
    }

    /** Runtime starts here directly, without replaying events from the pure simulation epoch. */
    Simulation(long seed, int initialTrucks, double malformedRate, boolean stuckTruckDemo,
               long initialEpochMs, String sequencePrefix) {
        if (initialTrucks < 0) throw new IllegalArgumentException("initialTrucks must be nonnegative");
        if (!Double.isFinite(malformedRate) || malformedRate < 0 || malformedRate > 1) {
            throw new IllegalArgumentException("malformedRate must be between 0 and 1");
        }
        this.seed = seed;
        this.malformedRate = malformedRate;
        this.stuckTruckDemo = stuckTruckDemo;
        this.initialTrucks = initialTrucks;
        now = initialEpochMs;
        city = new City(seed);
        events = new EventFactory(seed, sequencePrefix);
        chaos = new SplittableRandom(seed ^ 0x5DEECE66DL);
        for (int i = 0; i < initialTrucks; i++) addTruck(null, null);
    }

    public long now() { return now; }

    /** Advance to an absolute epoch millisecond; fine and coarse steps preserve per-truck events. */
    public List<Emission> step(long simNowMs) {
        if (simNowMs < now) throw new IllegalArgumentException("Simulation time cannot go backwards");
        var output = new ArrayList<Emission>();
        long boundary;
        while ((boundary = Math.min(scenarioEnd, nextSurge)) <= simNowMs) {
            advanceFleet(boundary, output);
            now = boundary;
            if (boundary == scenarioEnd) clearScenario();
            if (boundary == nextSurge) {
                addTruck(null, null);
                nextSurge = --surgeRemaining == 0 ? Long.MAX_VALUE : boundary + 10_000;
            }
        }
        advanceFleet(simNowMs, output);
        now = simNowMs;
        remember(output);
        return output;
    }

    private void advanceFleet(long simNowMs, List<Emission> output) {
        for (Truck truck : trucks.values()) {
            long next;
            while ((next = Math.min(truck.nextPosition, truck.nextChange())) <= simNowMs) {
                Truck.Delivery delivery = truck.advance(next);
                if (delivery != null) {
                    output.add(normal(truck, "truck.delivery", next, events.delivery(truck, delivery)));
                }
                if (next == truck.nextPosition) {
                    ObjectNode payload = events.position(truck);
                    if (truck.corruption.nextDouble() < malformedRate) payload.remove("speedKmh");
                    output.add(normal(truck, "truck.position", next, payload));
                    truck.nextPosition += POSITION_INTERVAL_MS;
                }
            }
            truck.advance(simNowMs);
        }
    }

    public String addTruck(Double x, Double y) {
        City.Point start = city.nearestStreet(x == null ? 1000 : x, y == null ? 1000 : y);
        Truck truck = new Truck(city, seed, ++nextTruckIndex, start, now, stuckTruckDemo);
        trucks.put(truck.id, truck);
        truck.setRushHour(scenario.equals("rush-hour"), now);
        return truck.id;
    }

    public boolean removeTruck(String truckId) {
        Truck removed = trucks.remove(truckId);
        if (removed == null) return false;
        retiredSequences.put(truckId, removed.sequence);
        return true;
    }

    /** Replace the active scenario at the current simulation time; never rewind event identity or time. */
    public void applyScenario(String name, String truckId) {
        Truck chosen = null;
        if ("breakdown".equals(name)) {
            chosen = trucks.get(truckId == null ? "truck-005" : truckId);
            if (chosen == null) throw new IllegalArgumentException("Unknown breakdown truck");
        } else if (!"rush-hour".equals(name) && !"surge".equals(name) && !"reset".equals(name)) {
            throw new IllegalArgumentException("Unknown scenario: " + name);
        }
        clearScenario();
        scenario = name;
        switch (name) {
            case "rush-hour" -> {
                scenarioEnd = now + 600_000;
                for (Truck truck : trucks.values()) truck.setRushHour(true, now);
            }
            case "breakdown" -> {
                scenarioEnd = now + 900_000;
                breakdownTruck = chosen;
                chosen.pause(now, scenarioEnd);
            }
            case "surge" -> {
                surgeRemaining = 6;
                nextSurge = now + 10_000;
            }
            case "reset" -> {
                for (Truck truck : trucks.values()) retiredSequences.put(truck.id, truck.sequence);
                trucks.clear();
                for (int index = 1; index <= initialTrucks; index++) {
                    Truck truck = new Truck(city, seed, index, City.DEPOT, now, false);
                    truck.sequence = retiredSequences.getOrDefault(truck.id, 0L);
                    truck.nextPosition = Math.max(now, lastEventTimes.getOrDefault(truck.id, now - 1) + 1);
                    trucks.put(truck.id, truck);
                }
                scenario = "none";
            }
        }
    }

    private void clearScenario() {
        for (Truck truck : trucks.values()) truck.setRushHour(false, now);
        if (breakdownTruck != null) breakdownTruck.clearBreakdown(now);
        breakdownTruck = null;
        scenario = "none";
        scenarioEnd = Long.MAX_VALUE;
        nextSurge = Long.MAX_VALUE;
        surgeRemaining = 0;
    }

    public List<Emission> lateEvents(int count, long lateByMs) {
        validateCount(count);
        if (lateByMs < 0) throw new IllegalArgumentException("lateByMs must be nonnegative");
        long timestamp;
        try { timestamp = Math.subtractExact(now, lateByMs); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("lateByMs is too large"); }
        var output = new ArrayList<Emission>();
        for (int i = 0; i < count; i++) {
            Truck truck = randomTruck();
            output.add(events.envelope(truck, "truck.position", timestamp, events.position(truck), "-late-" + ++lateSequence));
        }
        remember(output);
        return output;
    }

    public List<Emission> duplicates(int count) {
        if (count < 0) throw new IllegalArgumentException("count must be nonnegative");
        int start = Math.max(0, history.size() - count);
        List<Emission> output = List.copyOf(history.subList(start, history.size()));
        remember(output);
        return output;
    }

    public List<Emission> malformed(int count) {
        validateCount(count);
        var output = new ArrayList<Emission>();
        for (int i = 0; i < count; i++) {
            Truck truck = randomTruck();
            ObjectNode payload = events.position(truck).put("speedKmh", -1);
            output.add(normal(truck, "truck.position", now, payload));
        }
        remember(output);
        return output;
    }

    public ObjectNode state(boolean running, double timeScale) {
        ObjectNode state = Json.MAPPER.createObjectNode().put("running", running).put("timeScale", timeScale)
                .put("simTime", now).put("emitted", emitted).put("scenario", scenario);
        var truckStates = state.putArray("trucks");
        for (Truck truck : trucks.values()) truckStates.add(events.position(truck));
        var stops = state.putArray("stops");
        for (City.Stop stop : city.stops()) {
            stops.addObject().put("stopId", stop.stopId()).put("x", stop.x()).put("y", stop.y());
        }
        state.putObject("depot").put("x", 1000).put("y", 1000);
        return state;
    }

    private Emission normal(Truck truck, String type, long time, ObjectNode payload) {
        long timestamp = Math.max(time, lastEventTimes.getOrDefault(truck.id, Long.MIN_VALUE) + 1);
        lastEventTimes.put(truck.id, timestamp);
        return events.envelope(truck, type, timestamp, payload, "");
    }

    private void validateCount(int count) {
        if (count < 0) throw new IllegalArgumentException("count must be nonnegative");
        if (count > 0 && trucks.isEmpty()) throw new IllegalArgumentException("No trucks available");
    }

    private Truck randomTruck() {
        int selected = chaos.nextInt(trucks.size());
        for (Truck truck : trucks.values()) if (selected-- == 0) return truck;
        throw new IllegalStateException("No trucks available");
    }

    private void remember(List<Emission> records) {
        history.addAll(records);
        emitted += records.size();
    }
}
