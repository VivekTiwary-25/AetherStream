package io.aetherstream.compiler.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.aetherstream.common.Envelope;

/** Contract field paths are deliberately limited to one payload level. */
final class FieldPath {
    private FieldPath() {}

    static JsonNode get(Envelope event, String field) {
        return switch (field) {
            case "type" -> JsonNodeFactory.instance.textNode(event.type());
            case "key" -> JsonNodeFactory.instance.textNode(event.key());
            case "eventTime" -> JsonNodeFactory.instance.numberNode(event.eventTime());
            case "eventId" -> JsonNodeFactory.instance.textNode(event.eventId());
            default -> field.startsWith("payload.") ? event.payload().get(field.substring(8)) : null;
        };
    }
}
