package io.aetherstream.compiler.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aetherstream.common.Envelope;

record MapOp(String op, String field, String target, double factor, JsonNode value) {
    MapOp(JsonNode config) {
        this(config.path("op").asText(), config.path("field").asText(),
                config.path("as").asText(), config.path("factor").asDouble(), config.get("value"));
    }

    void apply(Envelope event, ObjectNode payload) {
        switch (op) {
            case "set" -> payload.set(target.substring(8), value.deepCopy());
            case "drop" -> payload.remove(field.substring(8));
            case "rename" -> {
                JsonNode old = FieldPath.get(event, field);
                if (old != null && !old.isNull()) {
                    if (field.startsWith("payload.")) payload.remove(field.substring(8));
                    payload.set(target.substring(8), old);
                }
            }
            case "scale" -> {
                JsonNode old = FieldPath.get(event, field);
                if (old != null && old.isNumber()) payload.put(target.substring(8), old.doubleValue() * factor);
            }
            default -> throw new IllegalArgumentException("Unknown map operation " + op);
        }
    }
}
