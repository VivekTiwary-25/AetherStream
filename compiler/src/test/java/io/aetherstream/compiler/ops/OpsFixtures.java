package io.aetherstream.compiler.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aetherstream.common.Json;
import io.aetherstream.common.pipeline.ExecutionPlan;
import io.aetherstream.common.pipeline.PlannedOperator;
import java.util.ArrayList;
import java.util.List;

final class OpsFixtures {
    private OpsFixtures() {}

    static ObjectNode position(String id, String key, long time, double speed) {
        ObjectNode event = Json.MAPPER.createObjectNode();
        event.put("eventId", id).put("schemaVersion", 1).put("type", "truck.position")
                .put("eventTime", time).put("key", key);
        event.putObject("payload").put("truckId", key).put("x", 10).put("y", 20)
                .put("speedKmh", speed).put("heading", 90).put("routeId", "r-3").put("status", "STOPPED");
        return event;
    }

    static ObjectNode delivery(String id, String key, long time, int parcels) {
        ObjectNode event = position(id, key, time, 0);
        event.put("type", "truck.delivery");
        ObjectNode payload = (ObjectNode) event.get("payload");
        payload.remove(List.of("speedKmh", "heading", "routeId", "status"));
        payload.put("stopId", "stop-12").put("parcels", parcels);
        return event;
    }

    static JsonNode json(String value) {
        try { return Json.MAPPER.readTree(value); }
        catch (Exception e) { throw new AssertionError(e); }
    }

    static PlannedOperator op(String id, String type, String config, String input, String output) {
        return new PlannedOperator(id, type, json(config), input == null ? List.of() : List.of(input), output);
    }

    static ExecutionPlan plan(List<PlannedOperator> operators) {
        return new ExecutionPlan("logistics-demo", 1, "truck-events", 6, "earliest", operators);
    }

    static ExecutionPlan demo() {
        List<PlannedOperator> nodes = new ArrayList<>();
        nodes.add(op("src", "source", "{\"topic\":\"truck-events\"}", null, "EVENT"));
        nodes.add(op("del", "filter", "{\"conditions\":[{\"field\":\"type\",\"op\":\"==\",\"value\":\"truck.delivery\"}]}", "src", "EVENT"));
        nodes.add(op("kb2", "keyBy", "{\"field\":\"key\"}", "del", "KEYED"));
        nodes.add(op("del1m", "window", """
                {"kind":"tumbling","sizeMs":60000,"outOfOrdernessMs":5000,"aggregations":[
                  {"name":"deliveries","fn":"count"},{"name":"parcels","fn":"sum","field":"payload.parcels"}]}
                """, "kb2", "WINDOW_RESULT"));
        nodes.add(op("delSink", "sink", "{\"kind\":\"postgres.windows\"}", "del1m", "NONE"));
        nodes.add(op("pos", "filter", "{\"conditions\":[{\"field\":\"type\",\"op\":\"==\",\"value\":\"truck.position\"}]}", "src", "EVENT"));
        nodes.add(op("kb1", "keyBy", "{\"field\":\"key\"}", "pos", "KEYED"));
        nodes.add(op("speed1m", "window", """
                {"kind":"tumbling","sizeMs":60000,"outOfOrdernessMs":5000,"aggregations":[
                  {"name":"avgSpeedKmh","fn":"avg","field":"payload.speedKmh"},
                  {"name":"maxSpeedKmh","fn":"max","field":"payload.speedKmh"},{"name":"count","fn":"count"}]}
                """, "kb1", "WINDOW_RESULT"));
        nodes.add(op("speedSink", "sink", "{\"kind\":\"postgres.windows\"}", "speed1m", "NONE"));
        nodes.add(op("stuck", "rule", """
                {"ruleId":"stuck-truck","condition":{"field":"avgSpeedKmh","op":"<","value":1.0},
                 "forWindows":10,"message":"{key} has not moved for {minutes} min"}
                """, "speed1m", "ALERT"));
        nodes.add(op("alertSink", "sink", "{\"kind\":\"postgres.alerts\"}", "stuck", "NONE"));
        return plan(nodes);
    }
}
