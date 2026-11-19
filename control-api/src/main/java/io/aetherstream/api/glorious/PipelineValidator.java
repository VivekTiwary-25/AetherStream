package io.aetherstream.api.glorious;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Validates the complete pipeline before execution; projections retain truckId.
 * Averages expose their averaged field (resolved to avg by execution) and grouping field,
 * but not eventTime, so a second averaging stage is not meaningful.
 */
public final class PipelineValidator {
    private static final Map<String, String> FIELDS = Map.of(
            "truckId", "string", "speedKmh", "number", "heading", "number", "x", "number",
            "y", "number", "routeId", "string", "status", "string", "partition", "number", "eventTime", "number");
    private static final Set<String> OPS = Set.of(">", ">=", "<", "<=", "==", "!=");
    private static final Set<String> GROUPS = Set.of("truckId", "routeId", "status", "partition");
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record Error(String nodeId, String message) {}
    public record Validation(boolean valid, List<Error> errors) {}
    public static Map<String, String> fields() { return FIELDS; }

    public Validation validate(JsonNode pipeline) {
        List<Error> errors = new ArrayList<>();
        if (!object(pipeline, Set.of("name", "nodes", "edges"), Set.of("name", "nodes", "edges"), null, errors)) return result(errors);
        text(pipeline.get("name"), "name", 60, null, errors);
        JsonNode nodes = pipeline.get("nodes"), edges = pipeline.get("edges");
        if (!nodes.isArray() || nodes.isEmpty() || nodes.size() > 12) errors.add(new Error(null, "nodes must be an array containing 1 to 12 nodes"));
        if (!edges.isArray()) errors.add(new Error(null, "edges must be an array"));
        if (!nodes.isArray() || !edges.isArray() || nodes.size() > 12) return result(errors);
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode node : nodes) {
            String id = node.path("id").isTextual() ? node.path("id").textValue() : null;
            if (!object(node, Set.of("id", "type", "params"), Set.of("id", "type", "params"), id, errors)) continue;
            text(node.get("id"), "id", Integer.MAX_VALUE, id, errors);
            if (id != null && byId.putIfAbsent(id, node) != null) errors.add(new Error(id, "duplicate node id"));
            validateParams(node, id, errors);
        }
        Map<String, List<String>> next = new HashMap<>();
        Map<String, Integer> incoming = new HashMap<>();
        Set<List<String>> edgeKeys = new HashSet<>();
        for (JsonNode edge : edges) {
            if (!object(edge, Set.of("from", "to"), Set.of("from", "to"), null, errors)) continue;
            String from = edge.path("from").asText(""), to = edge.path("to").asText("");
            if (!edge.path("from").isTextual() || !edge.path("to").isTextual() || !byId.containsKey(from) || !byId.containsKey(to)) {
                errors.add(new Error(null, "edge endpoints must reference existing node ids")); continue;
            }
            if (!edgeKeys.add(List.of(from, to))) errors.add(new Error(to, "duplicate edge"));
            next.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
            if (incoming.merge(to, 1, Integer::sum) > 1) errors.add(new Error(to, "node may have at most one incoming edge"));
        }
        if (byId.values().stream().noneMatch(n -> n.path("type").asText().equals("output"))) errors.add(new Error(null, "pipeline must contain an output"));
        for (var entry : byId.entrySet()) {
            boolean output = entry.getValue().path("type").asText().equals("output");
            boolean terminal = next.getOrDefault(entry.getKey(), List.of()).isEmpty();
            if (output && !terminal) errors.add(new Error(entry.getKey(), "output must be terminal"));
            if (!output && terminal) errors.add(new Error(entry.getKey(), "every path must end in an output"));
        }
        Map<String, Integer> degree = new HashMap<>(incoming);
        Deque<String> ready = new ArrayDeque<>();
        Map<String, Set<String>> available = new HashMap<>();
        byId.keySet().forEach(id -> { if (degree.getOrDefault(id, 0) == 0) { ready.add(id); available.put(id, FIELDS.keySet()); } });
        int visited = 0;
        while (!ready.isEmpty()) {
            String id = ready.remove(); visited++;
            JsonNode node = byId.get(id), p = node.path("params");
            Set<String> fields = available.getOrDefault(id, Set.of());
            String type = node.path("type").asText();
            if (Set.of("filter", "thresholdAlert", "windowAverage").contains(type)) requireField(p.path("field").asText(), fields, id, errors);
            if (type.equals("pickFields") && p.path("fields").isArray()) for (JsonNode f : p.path("fields")) requireField(f.asText(), fields, id, errors);
            Set<String> out = fields;
            if (type.equals("pickFields")) {
                out = new HashSet<>(); out.add("truckId");
                if (p.path("fields").isArray()) for (JsonNode f : p.path("fields")) out.add(f.asText());
            } else if (type.equals("windowAverage")) {
                requireField("eventTime", fields, id, errors);
                out = new HashSet<>(); out.add(p.path("field").asText());
                if (p.has("groupBy")) { requireField(p.path("groupBy").asText(), fields, id, errors); out.add(p.path("groupBy").asText()); }
            }
            for (String child : next.getOrDefault(id, List.of())) {
                available.put(child, out);
                if (degree.merge(child, -1, Integer::sum) == 0) ready.add(child);
            }
        }
        if (visited != byId.size()) errors.add(new Error(null, "pipeline graph must be acyclic"));
        return result(errors);
    }

    private static void validateParams(JsonNode node, String id, List<Error> errors) {
        String type = node.path("type").asText();
        Set<String> required;
        switch (type) {
            case "filter" -> required = Set.of("field", "op", "value");
            case "thresholdAlert" -> required = Set.of("field", "op", "value", "message");
            case "pickFields" -> required = Set.of("fields");
            case "windowAverage" -> required = Set.of("field", "windowSec");
            case "output" -> required = Set.of("name");
            default -> { errors.add(new Error(id, "unknown node type")); return; }
        }
        Set<String> allowed = new HashSet<>(required);
        if (type.equals("windowAverage")) allowed.add("groupBy");
        JsonNode p = node.path("params");
        if (!object(p, allowed, required, id, errors)) return;
        if (p.has("field")) {
            String field = p.path("field").asText();
            if (!p.path("field").isTextual() || !FIELDS.containsKey(field)) errors.add(new Error(id, "field must be a known truck field"));
            if (type.equals("windowAverage") && !"number".equals(FIELDS.get(field))) errors.add(new Error(id, "windowAverage field must be numeric"));
            if (p.has("op")) {
                String op = p.path("op").asText();
                if (!p.path("op").isTextual() || !OPS.contains(op)) errors.add(new Error(id, "op must be one of >, >=, <, <=, ==, !="));
                JsonNode value = p.path("value");
                if ("number".equals(FIELDS.get(field))) {
                    if (!value.isNumber() || !Double.isFinite(value.doubleValue())) errors.add(new Error(id, "value must be a finite number for " + field));
                } else if ("string".equals(FIELDS.get(field))) {
                    if (!Set.of("==", "!=").contains(op)) errors.add(new Error(id, "string fields support only == and !="));
                    if (!value.isTextual()) errors.add(new Error(id, "value must be a string for " + field));
                }
            }
        }
        if (type.equals("windowAverage")) {
            JsonNode w = p.path("windowSec");
            if (!w.isNumber() || !Double.isFinite(w.doubleValue()) || w.doubleValue() != Math.rint(w.doubleValue()) || w.doubleValue() < 5 || w.doubleValue() > 300) errors.add(new Error(id, "windowSec must be an integer between 5 and 300"));
            if (p.has("groupBy") && (!p.path("groupBy").isTextual() || !GROUPS.contains(p.path("groupBy").asText()))) errors.add(new Error(id, "groupBy must be truckId, routeId, status or partition"));
        }
        if (type.equals("pickFields")) {
            JsonNode fields = p.path("fields");
            if (!fields.isArray() || fields.isEmpty()) errors.add(new Error(id, "fields must be a nonempty array"));
            else {
                Set<String> seen = new HashSet<>();
                for (JsonNode field : fields) if (!field.isTextual() || !FIELDS.containsKey(field.asText()) || !seen.add(field.asText())) errors.add(new Error(id, "fields must contain unique known truck fields"));
            }
        }
        if (p.has("message")) text(p.get("message"), "message", 120, id, errors);
        if (type.equals("output")) text(p.get("name"), "name", 60, id, errors);
    }
    private static void requireField(String field, Set<String> available, String id, List<Error> errors) {
        if (FIELDS.containsKey(field) && !available.contains(field)) errors.add(new Error(id, "field " + field + " is unavailable after upstream projection or average"));
    }
    private static boolean object(JsonNode node, Set<String> allowed, Set<String> required, String id, List<Error> errors) {
        if (node == null || !node.isObject()) { errors.add(new Error(id, "expected an object")); return false; }
        node.fieldNames().forEachRemaining(key -> { if (!allowed.contains(key)) errors.add(new Error(id, "unknown property: " + key)); });
        for (String key : required) if (!node.has(key)) errors.add(new Error(id, "missing required property: " + key));
        return required.stream().allMatch(node::has);
    }
    private static void text(JsonNode value, String key, int max, String id, List<Error> errors) {
        if (value == null || !value.isTextual() || value.textValue().isEmpty() || value.textValue().codePointCount(0, value.textValue().length()) > max) errors.add(new Error(id, key + " must be a nonempty string of at most " + max + " characters"));
    }
    private static Validation result(List<Error> errors) { return new Validation(errors.isEmpty(), List.copyOf(errors)); }
}
