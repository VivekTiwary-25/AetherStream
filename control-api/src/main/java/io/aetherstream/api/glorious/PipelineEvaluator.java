package io.aetherstream.api.glorious;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Single-thread-confined evaluator; the run owner serializes accept and tick calls. */
public final class PipelineEvaluator {
    public static record Emission(String output, String kind, Map<String, Object> data) {}

    private static final int MAX_STATE = 10_000;
    private final Consumer<Emission> sink;
    private final List<Node> roots = new ArrayList<>();
    private final List<Node> nodes = new ArrayList<>();
    private long droppedRows;
    // Event time is simulator time, which can be hours or days away from the wall clock.
    // Track every source arrival separately: repeated/late timestamps are not source idleness.
    private long sourceWatermark = Long.MIN_VALUE;
    private long lastSourceRowWallMs;

    public PipelineEvaluator(JsonNode validatedPipeline, Consumer<Emission> sink) {
        this.sink = Objects.requireNonNull(sink, "sink");
        Map<String, Node> byId = new LinkedHashMap<>();
        for (JsonNode definition : validatedPipeline.path("nodes")) {
            Node node = new Node(definition);
            byId.put(definition.path("id").asText(), node);
            nodes.add(node);
        }
        var incoming = new java.util.HashSet<Node>();
        for (JsonNode edge : validatedPipeline.path("edges")) {
            Node target = byId.get(edge.path("to").asText());
            byId.get(edge.path("from").asText()).children.add(target);
            incoming.add(target);
        }
        for (Node node : nodes) if (!incoming.contains(node)) roots.add(node);
    }

    public void accept(Map<String, Object> row, long nowMs) {
        Object timestamp = row.get("eventTime");
        if (!(timestamp instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new IllegalArgumentException("Row eventTime must be a finite epoch timestamp");
        }
        sourceWatermark = Math.max(sourceWatermark, number.longValue());
        lastSourceRowWallMs = nowMs;
        tick(nowMs);
        Map<String, Object> copy = immutable(row);
        Frame frame = new Frame("row", copy, number.longValue(), copy,
                Objects.requireNonNullElse(copy.get("truckId"), "global"));
        for (Node root : roots) process(root, frame, nowMs);
    }

    public void tick(long nowMs) {
        // A quiet source may be stalled with a backlog, not stopped. Wait a full window plus
        // two seconds before idle finalization; event-time watermarks still close active windows.
        // Nodes are visited topologically so chained windows close in the same tick.
        if (sourceWatermark == Long.MIN_VALUE) return;
        long idleMs = Math.max(0, nowMs - lastSourceRowWallMs);
        long eventNow = sourceWatermark + idleMs;
        for (Node root : roots) tickNode(root, eventNow, idleMs, nowMs);
    }

    public long droppedRows() { return droppedRows; }

    private void tickNode(Node node, long eventNow, long idleMs, long nowMs) {
        // Apply idle finalization only after upstream nodes have delivered their closed windows.
        if (node.type.equals("windowAverage")
                && idleMs >= node.params.path("windowSec").asLong() * 1_000 + 2_000
                && eventNow >= Long.MIN_VALUE + 2_000) {
            node.finalizedThrough = Math.max(node.finalizedThrough, eventNow - 2_000);
        }
        closeWindows(node, nowMs);
        node.alerts.entrySet().removeIf(entry -> nowMs >= entry.getValue() && nowMs - entry.getValue() >= 10_000);
        for (Node child : node.children) tickNode(child, eventNow, idleMs, nowMs);
    }

    private void process(Node node, Frame frame, long nowMs) {
        JsonNode p = node.params;
        switch (node.type) {
            case "filter" -> {
                if (matches(value(frame, p.path("field").asText()), p)) forward(node, frame, nowMs);
            }
            case "pickFields" -> {
                Map<String, Object> projection = new LinkedHashMap<>();
                for (JsonNode field : p.path("fields")) {
                    String name = field.asText();
                    Object selected = value(frame, name);
                    if (selected != null) projection.put(name, selected);
                }
                Object truckId = frame.context.get("truckId");
                if (truckId != null) projection.put("truckId", truckId);
                forward(node, new Frame(frame.kind, immutable(projection), frame.eventTime, frame.context, frame.alertIdentity), nowMs);
            }
            case "windowAverage" -> accumulate(node, frame, nowMs);
            case "thresholdAlert" -> {
                forward(node, frame, nowMs);
                if (frame.kind.equals("alert")) return;
                String field = p.path("field").asText();
                Object actual = value(frame, field);
                if (!matches(actual, p)) return;
                Object identity = frame.alertIdentity;
                Long previous = node.alerts.get(identity);
                if (previous != null && (nowMs < previous || nowMs - previous < 10_000)) return;
                if (!node.alerts.containsKey(identity) && node.alerts.size() >= MAX_STATE) {
                    throw new IllegalStateException("Threshold alert identity limit exceeded");
                }
                node.alerts.put(identity, nowMs);
                Map<String, Object> alert = new LinkedHashMap<>();
                if (frame.context.containsKey("truckId")) alert.put("truckId", frame.context.get("truckId"));
                alert.put("field", field);
                alert.put("value", actual);
                alert.put("message", p.path("message").asText());
                forward(node, new Frame("alert", immutable(alert), frame.eventTime, frame.context, frame.alertIdentity), nowMs);
            }
            case "output" -> {
                if (frame.kind.equals("row")) {
                    // Rolling (not fixed-bucket) seconds prevent boundary bursts above 20/s.
                    while (!node.rowTimes.isEmpty() && nowMs >= node.rowTimes.peekFirst()
                            && nowMs - node.rowTimes.peekFirst() >= 1_000) node.rowTimes.removeFirst();
                    if (node.rowTimes.size() >= 20) { droppedRows++; return; }
                    node.rowTimes.addLast(nowMs);
                }
                sink.accept(new Emission(p.path("name").asText(), frame.kind, frame.data));
            }
            default -> throw new IllegalArgumentException("Unknown pipeline node type: " + node.type);
        }
    }

    private void accumulate(Node node, Frame frame, long nowMs) {
        if (frame.kind.equals("alert")) return;
        long size = node.params.path("windowSec").asLong() * 1_000;
        long start = Math.floorDiv(frame.eventTime, size) * size;
        long end = Math.addExact(start, size);
        node.watermark = Math.max(node.watermark, frame.eventTime);
        closeWindows(node, nowMs);
        // Never reopen finalized windows, even if no accumulator existed at closure.
        if (end <= node.finalizedThrough) return;
        Object actual = value(frame, node.params.path("field").asText());
        if (!(actual instanceof Number number) || !Double.isFinite(number.doubleValue())) return;
        String groupBy = node.params.path("groupBy").asText("");
        Object group = groupBy.isEmpty() ? "global" : frame.context.get(groupBy);
        if (group == null) throw new IllegalArgumentException("Missing window group field: " + groupBy);
        WindowKey key = new WindowKey(start, group);
        Window window = node.windows.get(key);
        if (window == null) {
            if (node.windows.size() >= MAX_STATE) throw new IllegalStateException("Window state limit exceeded");
            Map<String, Object> context = groupBy.isEmpty() ? Map.of() : Map.of(groupBy, group);
            window = new Window(end, context);
            node.windows.put(key, window);
        }
        window.sum += number.doubleValue();
        window.count++;
        if (!Double.isFinite(window.sum)) throw new IllegalStateException("Window sum is not finite");
    }

    private void closeWindows(Node node, long nowMs) {
        node.finalizedThrough = Math.max(node.finalizedThrough, node.watermark);
        if (node.windows.isEmpty()) return;
        var iterator = node.windows.entrySet().iterator();
        List<Frame> ready = new ArrayList<>();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            Window window = entry.getValue();
            if (window.end > node.finalizedThrough) continue;
            Map<String, Object> average = new LinkedHashMap<>();
            average.put("group", entry.getKey().group);
            average.put("field", node.params.path("field").asText());
            average.put("avg", window.sum / window.count);
            average.put("count", window.count);
            average.put("windowStart", entry.getKey().start);
            average.put("windowEnd", window.end);
            ready.add(new Frame("average", immutable(average), window.end - 1, window.context, entry.getKey().group));
            iterator.remove();
        }
        for (Frame frame : ready) forward(node, frame, nowMs);
    }

    private void forward(Node node, Frame frame, long nowMs) {
        for (Node child : node.children) process(child, frame, nowMs);
    }

    private static Object value(Frame frame, String field) {
        Object value = frame.data.get(field);
        if (value == null && frame.kind.equals("average") && field.equals(frame.data.get("field"))) {
            return frame.data.get("avg");
        }
        if (value == null && frame.kind.equals("average")) return frame.context.get(field);
        return value;
    }

    private static boolean matches(Object actual, JsonNode params) {
        JsonNode expected = params.path("value");
        String op = params.path("op").asText();
        if (actual instanceof Number number && expected.isNumber()) {
            double left = number.doubleValue(), right = expected.asDouble();
            if (!Double.isFinite(left) || !Double.isFinite(right)) return false;
            return switch (op) {
                case ">" -> left > right;
                case ">=" -> left >= right;
                case "<" -> left < right;
                case "<=" -> left <= right;
                case "==" -> left == right;
                case "!=" -> left != right;
                default -> false;
            };
        }
        if (!(actual instanceof String text) || !expected.isTextual()) return false;
        return op.equals("==") ? text.equals(expected.asText()) : op.equals("!=") && !text.equals(expected.asText());
    }

    private static Map<String, Object> immutable(Map<String, Object> map) {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    private record Frame(String kind, Map<String, Object> data, long eventTime,
                         Map<String, Object> context, Object alertIdentity) {}
    private record WindowKey(long start, Object group) {}
    private static final class Window {
        final long end;
        final Map<String, Object> context;
        double sum;
        long count;
        Window(long end, Map<String, Object> context) { this.end = end; this.context = context; }
    }
    private static final class Node {
        final String type;
        final JsonNode params;
        final List<Node> children = new ArrayList<>();
        final Map<WindowKey, Window> windows = new LinkedHashMap<>();
        final Map<Object, Long> alerts = new HashMap<>();
        final ArrayDeque<Long> rowTimes = new ArrayDeque<>();
        long watermark = Long.MIN_VALUE;
        long finalizedThrough = Long.MIN_VALUE;
        Node(JsonNode definition) {
            type = definition.path("type").asText();
            params = definition.path("params").deepCopy();
        }
    }
}
