package io.aetherstream.compiler.ops;

import com.fasterxml.jackson.databind.JsonNode;
import io.aetherstream.common.Envelope;
import io.aetherstream.common.WindowResult;

final class Condition {
    private final String field;
    private final String op;
    private final JsonNode expected;

    Condition(JsonNode config) {
        field = config.path("field").asText();
        op = config.path("op").asText();
        expected = config.get("value");
    }

    boolean matches(Envelope event) {
        // Envelope scalar comparisons avoid allocating temporary JSON nodes on the hot path.
        return switch (field) {
            case "type" -> text(event.type());
            case "key" -> text(event.key());
            case "eventId" -> text(event.eventId());
            case "eventTime" -> expected.isNumber() && numericCompare(
                    java.math.BigDecimal.valueOf(event.eventTime()).compareTo(expected.decimalValue()));
            default -> matches(FieldPath.get(event, field));
        };
    }

    boolean matches(WindowResult result) {
        Double value = result.values().get(field);
        return value != null && expected.isNumber() && number(value);
    }

    private boolean matches(JsonNode actual) {
        if (actual == null || actual.isNull() || actual.isMissingNode()) return false;
        if (actual.isNumber() && expected.isNumber()) {
            return numericCompare(actual.decimalValue().compareTo(expected.decimalValue()));
        }
        if (actual.isTextual() && expected.isTextual()) return equality(actual.textValue().equals(expected.textValue()));
        if (actual.isBoolean() && expected.isBoolean()) return equality(actual.booleanValue() == expected.booleanValue());
        return false;
    }

    private boolean text(String actual) {
        return expected.isTextual() && equality(actual.equals(expected.textValue()));
    }

    private boolean number(double actual) {
        return numericCompare(java.math.BigDecimal.valueOf(actual).compareTo(expected.decimalValue()));
    }

    private boolean equality(boolean equal) {
        return switch (op) { case "==" -> equal; case "!=" -> !equal; default -> false; };
    }

    private boolean numericCompare(int comparison) {
        return switch (op) {
            case "==" -> comparison == 0;
            case "!=" -> comparison != 0;
            case ">" -> comparison > 0;
            case ">=" -> comparison >= 0;
            case "<" -> comparison < 0;
            case "<=" -> comparison <= 0;
            default -> false;
        };
    }
}
