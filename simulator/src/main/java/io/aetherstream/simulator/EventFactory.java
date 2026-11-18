package io.aetherstream.simulator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aetherstream.common.Json;
import java.util.Locale;

final class EventFactory {
    private final long seed;
    private final String sequencePrefix;

    EventFactory(long seed, String sequencePrefix) {
        this.seed = seed;
        this.sequencePrefix = sequencePrefix;
    }

    ObjectNode position(Truck truck) {
        return Json.MAPPER.createObjectNode()
                .put("truckId", truck.id).put("x", truck.laneX()).put("y", truck.laneY())
                .put("speedKmh", truck.speed).put("heading", truck.heading)
                .put("routeId", truck.routeId).put("status", truck.status);
    }

    ObjectNode delivery(Truck truck, Truck.Delivery delivery) {
        return Json.MAPPER.createObjectNode().put("truckId", truck.id)
                .put("stopId", delivery.stop().stopId()).put("x", delivery.stop().x())
                .put("y", delivery.stop().y()).put("parcels", delivery.parcels());
    }

    Simulation.Emission envelope(Truck truck, String type, long time, ObjectNode payload, String suffix) {
        String id = "s" + seed + "-" + truck.id + "-"
                + sequencePrefix + String.format(Locale.ROOT, "%06d", ++truck.sequence) + suffix;
        ObjectNode envelope = Json.MAPPER.createObjectNode().put("eventId", id).put("schemaVersion", 1)
                .put("type", type).put("eventTime", time).put("key", truck.id);
        envelope.set("payload", payload);
        try {
            return new Simulation.Emission(truck.id, Json.MAPPER.writeValueAsString(envelope));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize simulator event", e);
        }
    }
}
