package io.aetherstream.compiler.ops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import io.aetherstream.common.DeadLetter;
import io.aetherstream.common.Envelope;
import io.aetherstream.common.Json;
import io.aetherstream.common.SourcePosition;

/** Strict wire validation; producer-supplied source metadata is never trusted. */
public final class EnvelopeValidator {
    private EnvelopeValidator() {}

    public sealed interface Result permits Accepted, Rejected {}
    public record Accepted(Envelope envelope) implements Result {}
    public record Rejected(DeadLetter deadLetter) implements Result {}

    public static Result parse(String topic, int partition, long offset, String raw) {
        JsonNode root;
        try {
            root = raw == null ? null : Json.MAPPER.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw);
        } catch (JsonProcessingException e) {
            return rejected(null, partition, offset, raw, "unparseable JSON");
        }
        if (root == null || root.isMissingNode()) {
            return rejected(null, partition, offset, raw, "unparseable JSON");
        }
        try {
            require(root.isObject(), "expected JSON object");
            String eventId = string(root, "eventId", "");
            require(!eventId.isEmpty(), "eventId must not be empty");
            JsonNode version = field(root, "schemaVersion", "");
            require(version.isIntegralNumber() && version.canConvertToInt(), "invalid type schemaVersion");
            require(version.intValue() == 1, "schemaVersion must be 1");
            String type = string(root, "type", "");
            require(type.equals("truck.position") || type.equals("truck.delivery"), "unknown type " + type);
            JsonNode time = field(root, "eventTime", "");
            require(time.isIntegralNumber() && time.canConvertToLong(), "invalid type eventTime");
            String key = string(root, "key", "");
            JsonNode payload = field(root, "payload", "");
            require(payload.isObject(), "invalid type payload");
            require(key.equals(string(payload, "truckId", "payload.")), "key != payload.truckId");
            double x = number(payload, "x");
            double y = number(payload, "y");
            if (type.equals("truck.position")) {
                require(x >= 0 && x <= 2000, "payload.x must be in 0..2000");
                require(y >= 0 && y <= 2000, "payload.y must be in 0..2000");
                require(number(payload, "speedKmh") >= 0, "payload.speedKmh must be >= 0");
                double heading = number(payload, "heading");
                require(heading >= 0 && heading <= 360, "payload.heading must be in 0..360");
                string(payload, "routeId", "payload.");
                String status = string(payload, "status", "payload.");
                require(status.equals("DRIVING") || status.equals("UNLOADING")
                        || status.equals("IDLE") || status.equals("STOPPED"), "invalid payload.status");
            } else {
                string(payload, "stopId", "payload.");
                JsonNode parcels = field(payload, "parcels", "payload.");
                require(parcels.isIntegralNumber() && parcels.canConvertToInt(), "invalid type payload.parcels");
                require(parcels.intValue() >= 1, "payload.parcels must be >= 1");
            }
            return new Accepted(new Envelope(eventId, 1, type, time.longValue(), key, payload,
                    new SourcePosition(topic, partition, offset)));
        } catch (Invalid e) {
            return rejected(root, partition, offset, raw, e.getMessage());
        }
    }

    private static Rejected rejected(JsonNode root, int partition, long offset, String raw, String reason) {
        JsonNode id = root == null ? null : root.get("eventId");
        JsonNode time = root == null ? null : root.get("eventTime");
        return new Rejected(new DeadLetter(id != null && id.isTextual() ? id.textValue() : "p" + partition + "-o" + offset,
                DeadLetter.REJECTED, reason, partition, offset,
                time != null && time.isIntegralNumber() && time.canConvertToLong() ? time.longValue() : null, raw));
    }

    private static JsonNode field(JsonNode node, String name, String prefix) {
        JsonNode value = node.get(name);
        require(value != null, "missing field " + prefix + name);
        return value;
    }

    private static String string(JsonNode node, String name, String prefix) {
        JsonNode value = field(node, name, prefix);
        require(value.isTextual(), "invalid type " + prefix + name);
        return value.textValue();
    }

    private static double number(JsonNode node, String name) {
        JsonNode value = field(node, name, "payload.");
        require(value.isNumber() && Double.isFinite(value.doubleValue()), "invalid type payload." + name);
        return value.doubleValue();
    }

    private static void require(boolean valid, String reason) {
        if (!valid) throw new Invalid(reason);
    }

    private static final class Invalid extends RuntimeException {
        private Invalid(String message) { super(message, null, false, false); }
    }
}
