package io.aetherstream.common.pipeline;

import com.fasterxml.jackson.databind.JsonNode;

/** type: source|filter|map|keyBy|window|rule|sink. config is type-specific (contract 02 section 2). */
public record Node(String id, String type, String label, Position position, JsonNode config) {
    public record Position(double x, double y) {}
}
