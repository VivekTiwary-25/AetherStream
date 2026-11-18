package io.aetherstream.compiler.validation;

import com.fasterxml.jackson.databind.JsonNode;
import io.aetherstream.common.pipeline.Node;
import io.aetherstream.common.pipeline.ValidationError;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Config checks shared by graph validation and upstream-window rule validation. */
public final class NodeConfigValidator {
    private static final Set<String> COMPARISONS = Set.of("==", "!=", ">", ">=", "<", "<=");
    private static final Set<String> AGGREGATIONS = Set.of("count", "sum", "avg", "min", "max");
    private static final Set<String> ENVELOPE_FIELDS = Set.of("type", "key", "eventTime", "eventId");
    private static final Set<String> START_POSITIONS = Set.of("latest", "earliest");
    private static final Set<String> KEY_FIELDS = Set.of("key", "payload.truckId");
    private static final Set<String> SINK_KINDS = Set.of("postgres.windows", "postgres.alerts");
    private static final Set<String> EQUALITY_COMPARISONS = Set.of("==", "!=");

    private NodeConfigValidator() {}

    public static void validate(Node node, List<ValidationError> errors) {
        JsonNode config = node.config();
        if (config == null || !config.isObject()) {
            invalid(node, errors, "config", "supply a JSON object with this block's settings");
            return;
        }
        switch (node.type()) {
            case "source" -> {
                text(node, errors, config, "topic");
                if (config.has("startFrom") && !isOneOf(config.path("startFrom"), START_POSITIONS)) {
                    invalid(node, errors, "startFrom", "use 'latest' or 'earliest', or omit it for 'latest'");
                }
            }
            case "filter" -> {
                JsonNode conditions = config.path("conditions");
                if (nonEmptyArray(node, errors, conditions, "conditions")) {
                    for (int i = 0; i < conditions.size(); i++) {
                        condition(node, errors, conditions.get(i), "conditions[" + i + "]", true);
                    }
                }
            }
            case "map" -> validateMap(node, errors, config.path("ops"));
            case "keyBy" -> {
                if (text(node, errors, config, "field") && !isOneOf(config.path("field"), KEY_FIELDS)) {
                    errors.add(new ValidationError(node.id(), "UNSUPPORTED_KEY", describe(node)
                            + ": only 'key' or 'payload.truckId' is supported in v1 because it matches the Kafka partition key. Set field to one of these paths."));
                }
            }
            case "window" -> validateWindow(node, errors, config);
            case "rule" -> {
                text(node, errors, config, "ruleId");
                condition(node, errors, config.path("condition"), "condition", false);
                integerRange(node, errors, config.path("forWindows"), "forWindows", 1, Integer.MAX_VALUE);
                if (text(node, errors, config, "message")) {
                    String message = config.path("message").textValue();
                    if (!message.contains("{key}") || !message.contains("{minutes}")) {
                        invalid(node, errors, "message", "include both {key} and {minutes} in the message template");
                    }
                }
            }
            case "sink" -> {
                if (!isOneOf(config.path("kind"), SINK_KINDS)) {
                    invalid(node, errors, "kind", "use 'postgres.windows' for Window results or 'postgres.alerts' for Rule alerts");
                }
            }
            default -> throw new IllegalArgumentException("Unknown node type " + node.type());
        }
    }

    public static void validateRuleInput(Node rule, Node window, List<ValidationError> errors) {
        if (rule.config() == null || window.config() == null) {
            return;
        }
        JsonNode field = rule.config().path("condition").path("field");
        JsonNode aggregations = window.config().path("aggregations");
        if (!field.isTextual() || field.textValue().isBlank() || !aggregations.isArray()) {
            return;
        }
        for (JsonNode aggregation : aggregations) {
            if (field.equals(aggregation.path("name"))) {
                return;
            }
        }
        invalid(rule, errors, "condition.field", "use an aggregation name declared by " + describe(window)
                + ", or add an aggregation named '" + field.textValue() + "' to that Window");
    }

    private static void validateMap(Node node, List<ValidationError> errors, JsonNode ops) {
        if (!nonEmptyArray(node, errors, ops, "ops")) {
            return;
        }
        for (int i = 0; i < ops.size(); i++) {
            JsonNode op = ops.get(i);
            String path = "ops[" + i + "]";
            if (!op.isObject()) {
                invalid(node, errors, path, "supply an operation object with op and its fields");
                continue;
            }
            String kind = op.path("op").asText("");
            switch (kind) {
                case "scale", "rename" -> {
                    field(node, errors, op.path("field"), path + ".field", false);
                    field(node, errors, op.path("as"), path + ".as", true);
                    if ("scale".equals(kind) && (!op.path("factor").isNumber() || !Double.isFinite(op.path("factor").doubleValue()))) {
                        invalid(node, errors, path + ".factor", "supply a finite numeric scale factor");
                    }
                }
                case "drop" -> field(node, errors, op.path("field"), path + ".field", true);
                case "set" -> {
                    field(node, errors, op.path("as"), path + ".as", true);
                    if (!op.has("value")) {
                        invalid(node, errors, path + ".value", "supply the value to assign");
                    }
                }
                default -> invalid(node, errors, path + ".op", "use 'scale', 'rename', 'drop' or 'set'");
            }
        }
    }

    private static void validateWindow(Node node, List<ValidationError> errors, JsonNode config) {
        if (!"tumbling".equals(config.path("kind").textValue())) {
            invalid(node, errors, "kind", "use 'tumbling', the supported window kind");
        }
        integerRange(node, errors, config.path("sizeMs"), "sizeMs", 1000, 3600000);
        if (config.has("outOfOrdernessMs")) {
            integerRange(node, errors, config.path("outOfOrdernessMs"), "outOfOrdernessMs", 0, 600000);
        }
        JsonNode aggregations = config.path("aggregations");
        if (!nonEmptyArray(node, errors, aggregations, "aggregations")) {
            return;
        }
        Set<String> names = new HashSet<>();
        for (int i = 0; i < aggregations.size(); i++) {
            JsonNode aggregation = aggregations.get(i);
            String path = "aggregations[" + i + "]";
            if (!aggregation.isObject()) {
                invalid(node, errors, path, "supply an aggregation object with name and fn");
                continue;
            }
            JsonNode name = aggregation.path("name");
            if (!name.isTextual() || name.textValue().isBlank()) {
                invalid(node, errors, path + ".name", "supply a non-empty aggregation name");
            } else if (!names.add(name.textValue())) {
                invalid(node, errors, path + ".name", "give every aggregation in this Window a unique name");
            }
            JsonNode fn = aggregation.path("fn");
            if (!isOneOf(fn, AGGREGATIONS)) {
                invalid(node, errors, path + ".fn", "use 'count', 'sum', 'avg', 'min' or 'max'");
            }
            if (!"count".equals(fn.asText()) || aggregation.has("field")) {
                field(node, errors, aggregation.path("field"), path + ".field", false);
            }
        }
    }

    private static void condition(Node node, List<ValidationError> errors, JsonNode condition, String path, boolean eventField) {
        if (!condition.isObject()) {
            invalid(node, errors, path, "supply a condition object with field, op and value");
            return;
        }
        if (eventField) {
            field(node, errors, condition.path("field"), path + ".field", false);
        } else if (!condition.path("field").isTextual() || condition.path("field").textValue().isBlank()) {
            invalid(node, errors, path + ".field", "supply an upstream Window aggregation name");
        }
        JsonNode op = condition.path("op");
        if (!isOneOf(op, COMPARISONS)) {
            invalid(node, errors, path + ".op", "use '==', '!=', '>', '>=', '<' or '<='");
        }
        JsonNode value = condition.path("value");
        if (!value.isTextual() && !value.isNumber() && !value.isBoolean()) {
            invalid(node, errors, path + ".value", "supply a string, number or boolean");
        } else if ((value.isTextual() || value.isBoolean()) && isOneOf(op, COMPARISONS)
                && !isOneOf(op, EQUALITY_COMPARISONS)) {
            invalid(node, errors, path + ".op", "use '==' or '!=' with strings and booleans; ordered comparisons need a numeric value");
        }
    }

    private static void field(Node node, List<ValidationError> errors, JsonNode value, String path, boolean payloadOnly) {
        String name = value.isTextual() ? value.textValue() : "";
        boolean payload = name.startsWith("payload.") && name.length() > "payload.".length()
                && name.indexOf('.', "payload.".length()) < 0;
        if (!payload && (payloadOnly || !ENVELOPE_FIELDS.contains(name))) {
            invalid(node, errors, path, payloadOnly ? "use a one-level payload.<name> target; envelope fields cannot be changed"
                    : "use type, key, eventTime, eventId or a one-level payload.<name> field path");
        }
    }

    private static boolean text(Node node, List<ValidationError> errors, JsonNode config, String field) {
        JsonNode value = config.path(field);
        if (value.isTextual() && !value.textValue().isBlank()) {
            return true;
        }
        invalid(node, errors, field, "supply a non-empty string");
        return false;
    }

    private static boolean nonEmptyArray(Node node, List<ValidationError> errors, JsonNode value, String field) {
        if (value.isArray() && !value.isEmpty()) {
            return true;
        }
        invalid(node, errors, field, "supply a non-empty list");
        return false;
    }

    private static void integerRange(Node node, List<ValidationError> errors, JsonNode value, String field, long min, long max) {
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < min || value.longValue() > max) {
            invalid(node, errors, field, "supply an integer from " + min + " through " + max);
        }
    }

    private static boolean isOneOf(JsonNode value, Set<String> choices) {
        return value.isTextual() && choices.contains(value.textValue());
    }

    private static void invalid(Node node, List<ValidationError> errors, String field, String fix) {
        errors.add(new ValidationError(node.id(), "INVALID_CONFIG", describe(node) + ": invalid " + field + ". To fix it, " + fix + "."));
    }

    public static String describe(Node node) {
        String type = switch (node.type() == null ? "" : node.type()) {
            case "source" -> "Source";
            case "filter" -> "Filter";
            case "map" -> "Map";
            case "keyBy" -> "Key-by";
            case "window" -> "Window";
            case "rule" -> "Rule";
            case "sink" -> "Sink";
            default -> "Node";
        };
        return type + " '" + node.id() + "'" + (node.label() == null || node.label().isBlank() ? "" : " (" + node.label() + ")");
    }
}
