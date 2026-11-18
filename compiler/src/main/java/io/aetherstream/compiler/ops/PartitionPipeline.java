package io.aetherstream.compiler.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aetherstream.common.Alert;
import io.aetherstream.common.DeadLetter;
import io.aetherstream.common.Envelope;
import io.aetherstream.common.Json;
import io.aetherstream.common.WindowResult;
import io.aetherstream.common.pipeline.ExecutionPlan;
import io.aetherstream.common.pipeline.PlannedOperator;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Single-thread-confined semantics core for one source partition. No wall-clock or idle flushing. */
public final class PartitionPipeline {
    private final ExecutionPlan plan;
    private final int partition;
    private final Map<String, Node> nodes = new TreeMap<>();
    private final List<Node> windows = new ArrayList<>();
    private final Node source;
    private long maxTs = Long.MIN_VALUE;

    public PartitionPipeline(ExecutionPlan plan, int partition) {
        this.plan = plan;
        this.partition = partition;
        Node sourceNode = null;
        for (PlannedOperator operator : plan.operators()) {
            Node node = new Node(operator);
            nodes.put(operator.id(), node);
            if (operator.type().equals("source")) sourceNode = node;
            if (operator.type().equals("window")) windows.add(node);
        }
        for (PlannedOperator operator : plan.operators()) {
            for (String input : operator.inputs()) nodes.get(input).children.add(nodes.get(operator.id()));
        }
        for (Node node : nodes.values()) node.children.sort((a, b) -> a.id.compareTo(b.id));
        if (sourceNode == null) throw new IllegalArgumentException("Plan has no source");
        source = sourceNode;
    }

    public void process(long offset, String rawValue, Collector out) {
        EnvelopeValidator.Result parsed = EnvelopeValidator.parse(plan.topic(), partition, offset, rawValue);
        if (parsed instanceof EnvelopeValidator.Rejected rejected) {
            out.deadLetter(rejected.deadLetter());
            return;
        }
        Envelope event = ((EnvelopeValidator.Accepted) parsed).envelope();
        Late late = new Late(event, rawValue);
        forward(source, event, out, late);
        if (late.result != null) out.deadLetter(late.result);
        maxTs = Math.max(maxTs, event.eventTime());
        for (Node window : windows) {
            long watermark = watermark(window);
            var iterator = window.buckets.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                if (entry.getKey().end > watermark) break;
                WindowKey key = entry.getKey();
                Bucket bucket = entry.getValue();
                Map<String, Double> values = new TreeMap<>();
                for (Aggregation aggregation : window.aggregations) {
                    Aggregate aggregate = bucket.aggregates.get(aggregation.name);
                    if (aggregate.count != 0) values.put(aggregation.name, aggregate.result(aggregation.fn));
                }
                WindowResult result = new WindowResult(window.id, key.key, key.end - window.sizeMs, key.end,
                        Collections.unmodifiableMap(values), bucket.eventIds.size());
                iterator.remove();
                forward(window, result, out, late);
            }
        }
    }

    public long maxTs() { return maxTs; }

    public Map<String, Long> watermarks() {
        Map<String, Long> result = new TreeMap<>();
        for (Node window : windows) result.put(window.id, watermark(window));
        return Collections.unmodifiableMap(result);
    }

    private long watermark(Node window) {
        // Saturation also preserves the initial Long.MIN_VALUE sentinel instead of wrapping positive.
        return maxTs < Long.MIN_VALUE + window.outOfOrdernessMs ? Long.MIN_VALUE : maxTs - window.outOfOrdernessMs;
    }

    private void forward(Node node, Object value, Collector out, Late late) {
        for (Node child : node.children) route(child, value, out, late);
    }

    private void route(Node node, Object value, Collector out, Late late) {
        switch (node.type) {
            case "filter" -> {
                Envelope event = (Envelope) value;
                for (Condition condition : node.conditions) if (!condition.matches(event)) return;
                forward(node, event, out, late);
            }
            case "map" -> {
                Envelope original = (Envelope) value;
                ObjectNode payload = original.payload().deepCopy();
                Envelope mapped = new Envelope(original.eventId(), original.schemaVersion(), original.type(),
                        original.eventTime(), original.key(), payload, original.source());
                for (MapOp operation : node.mapOps) operation.apply(mapped, payload);
                forward(node, mapped, out, late);
            }
            case "keyBy" -> {
                Envelope event = (Envelope) value;
                String key = node.keyField.equals("key") ? event.key() : event.payload().path("truckId").asText();
                forward(node, new Keyed(event, key), out, late);
            }
            case "window" -> absorb(node, (Keyed) value, late);
            case "rule" -> {
                WindowResult result = (WindowResult) value;
                Streak streak = node.streaks.computeIfAbsent(result.key(), ignored -> new Streak());
                if (!node.condition.matches(result)) {
                    streak.count = 0;
                } else {
                    streak.count = streak.count != 0 && streak.previousEnd == result.windowStart()
                            ? streak.count + 1 : 1;
                    if (streak.count == node.forWindows) {
                        double minutes = node.forWindows * (double) (result.windowEnd() - result.windowStart()) / 60000;
                        String message = node.message.replace("{key}", result.key()).replace("{minutes}",
                                BigDecimal.valueOf(minutes).stripTrailingZeros().toPlainString());
                        forward(node, new Alert(node.id + "|" + result.key() + "|" + result.windowStart(),
                                result.key(), node.ruleId, message, result.windowEnd()), out, late);
                    }
                    // Retain an above-threshold sentinel: no repeat until a failure or gap resets the streak.
                    if (streak.count > node.forWindows) streak.count = (long) node.forWindows + 1;
                }
                streak.previousEnd = result.windowEnd();
            }
            case "sink" -> {
                if (value instanceof WindowResult result) out.windowResult(node.id, result);
                else out.alert(node.id, (Alert) value);
            }
            default -> throw new IllegalArgumentException("Unknown operator type " + node.type);
        }
    }

    private void absorb(Node node, Keyed keyed, Late late) {
        Envelope event = keyed.event;
        long start = Math.floorDiv(event.eventTime(), node.sizeMs) * node.sizeMs;
        long end = start + node.sizeMs;
        long watermark = watermark(node);
        if (end <= watermark) {
            late.record(node.id, start, end, watermark);
            return;
        }
        Bucket bucket = node.buckets.computeIfAbsent(new WindowKey(end, keyed.key), ignored -> new Bucket(node));
        if (!bucket.eventIds.add(event.eventId())) return;
        for (Aggregation aggregation : node.aggregations) {
            Aggregate aggregate = bucket.aggregates.get(aggregation.name);
            if (aggregation.fn.equals("count")) {
                aggregate.count++;
            } else {
                JsonNode field = FieldPath.get(event, aggregation.field);
                if (field != null && field.isNumber()) aggregate.add(field.doubleValue());
            }
        }
    }

    /** Explicit JSON schema keeps state ordering independent of Jackson bean/map settings. */
    public JsonNode snapshot() {
        ObjectNode root = Json.MAPPER.createObjectNode();
        root.put("maxTs", maxTs);
        ObjectNode windowState = root.putObject("windows");
        ObjectNode ruleState = root.putObject("rules");
        for (Node node : nodes.values()) {
            if (node.type.equals("window")) {
                ArrayNode entries = windowState.putArray(node.id);
                for (var entry : node.buckets.entrySet()) {
                    ObjectNode saved = entries.addObject();
                    saved.put("end", entry.getKey().end);
                    saved.put("key", entry.getKey().key);
                    ArrayNode ids = saved.putArray("eventIds");
                    entry.getValue().eventIds.forEach(ids::add);
                    ObjectNode aggregates = saved.putObject("aggregates");
                    for (var state : entry.getValue().aggregates.entrySet()) {
                        Aggregate aggregate = state.getValue();
                        ObjectNode a = aggregates.putObject(state.getKey());
                        a.put("count", aggregate.count);
                        a.put("sum", aggregate.sum);
                        a.put("min", aggregate.min);
                        a.put("max", aggregate.max);
                    }
                }
            } else if (node.type.equals("rule")) {
                ObjectNode keys = ruleState.putObject(node.id);
                for (var entry : node.streaks.entrySet()) {
                    ObjectNode saved = keys.putObject(entry.getKey());
                    saved.put("count", entry.getValue().count);
                    saved.put("previousEnd", entry.getValue().previousEnd);
                }
            }
        }
        return root;
    }

    public static PartitionPipeline restore(ExecutionPlan plan, int partition, JsonNode snapshot) {
        PartitionPipeline restored = new PartitionPipeline(plan, partition);
        restored.maxTs = snapshot.required("maxTs").longValue();
        for (Node node : restored.nodes.values()) {
            if (node.type.equals("window")) {
                for (JsonNode saved : snapshot.required("windows").required(node.id)) {
                    Bucket bucket = new Bucket(node);
                    saved.required("eventIds").forEach(id -> bucket.eventIds.add(id.textValue()));
                    for (var entry : bucket.aggregates.entrySet()) {
                        JsonNode state = saved.required("aggregates").required(entry.getKey());
                        Aggregate aggregate = entry.getValue();
                        aggregate.count = state.required("count").longValue();
                        aggregate.sum = state.required("sum").doubleValue();
                        aggregate.min = state.required("min").doubleValue();
                        aggregate.max = state.required("max").doubleValue();
                    }
                    node.buckets.put(new WindowKey(saved.required("end").longValue(), saved.required("key").textValue()), bucket);
                }
            } else if (node.type.equals("rule")) {
                snapshot.required("rules").required(node.id).properties().forEach(entry -> {
                    Streak streak = new Streak();
                    streak.count = entry.getValue().required("count").longValue();
                    streak.previousEnd = entry.getValue().required("previousEnd").longValue();
                    node.streaks.put(entry.getKey(), streak);
                });
            }
        }
        return restored;
    }

    private record Keyed(Envelope event, String key) {}
    private record WindowKey(long end, String key) implements Comparable<WindowKey> {
        @Override public int compareTo(WindowKey other) {
            int comparison = Long.compare(end, other.end);
            return comparison != 0 ? comparison : key.compareTo(other.key);
        }
    }
    private record Aggregation(String name, String fn, String field) {
        Aggregation(JsonNode config) {
            this(config.path("name").asText(), config.path("fn").asText(), config.path("field").asText());
        }
    }
    private static final class Aggregate {
        long count;
        double sum;
        double min;
        double max;
        void add(double value) {
            min = count == 0 ? value : Math.min(min, value);
            max = count == 0 ? value : Math.max(max, value);
            sum += value;
            count++;
        }
        double result(String fn) {
            return switch (fn) {
                case "count" -> count;
                case "sum" -> sum;
                case "avg" -> sum / count;
                case "min" -> min;
                case "max" -> max;
                default -> throw new IllegalArgumentException("Unknown aggregation " + fn);
            };
        }
    }
    private static final class Bucket {
        final TreeSet<String> eventIds = new TreeSet<>();
        final Map<String, Aggregate> aggregates = new TreeMap<>();
        Bucket(Node node) {
            for (Aggregation aggregation : node.aggregations) aggregates.put(aggregation.name, new Aggregate());
        }
    }
    private static final class Streak {
        long count;
        long previousEnd;
    }
    private static final class Node {
        final String id;
        final String type;
        final List<Node> children = new ArrayList<>();
        final List<Condition> conditions = new ArrayList<>();
        final List<MapOp> mapOps = new ArrayList<>();
        final List<Aggregation> aggregations = new ArrayList<>();
        final Map<WindowKey, Bucket> buckets = new TreeMap<>();
        final Map<String, Streak> streaks = new TreeMap<>();
        final long sizeMs;
        final long outOfOrdernessMs;
        final String keyField;
        final Condition condition;
        final String ruleId;
        final int forWindows;
        final String message;
        Node(PlannedOperator operator) {
            id = operator.id();
            type = operator.type();
            JsonNode config = operator.config();
            for (JsonNode c : config.path("conditions")) conditions.add(new Condition(c));
            for (JsonNode c : config.path("ops")) mapOps.add(new MapOp(c));
            for (JsonNode c : config.path("aggregations")) aggregations.add(new Aggregation(c));
            sizeMs = config.path("sizeMs").asLong();
            outOfOrdernessMs = config.path("outOfOrdernessMs").asLong(5000);
            keyField = config.path("field").asText();
            condition = type.equals("rule") ? new Condition(config.required("condition")) : null;
            ruleId = config.path("ruleId").asText();
            forWindows = config.path("forWindows").asInt();
            message = config.path("message").asText();
        }
    }
    private final class Late {
        final Envelope event;
        final String raw;
        String nodeId;
        DeadLetter result;
        Late(Envelope event, String raw) { this.event = event; this.raw = raw; }
        void record(String id, long start, long end, long watermark) {
            if (nodeId == null || id.compareTo(nodeId) < 0) {
                nodeId = id;
                result = new DeadLetter(event.eventId(), DeadLetter.LATE,
                        "window [" + start + "," + end + ") already closed (watermark " + watermark + ")",
                        partition, event.source().offset(), event.eventTime(), raw);
            }
        }
    }
}
