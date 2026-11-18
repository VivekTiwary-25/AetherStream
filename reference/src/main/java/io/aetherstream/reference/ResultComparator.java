package io.aetherstream.reference;

import io.aetherstream.common.Alert;
import io.aetherstream.common.WindowResult;
import io.aetherstream.common.api.CompareResult;
import io.aetherstream.common.api.CompareResult.Mismatch;
import io.aetherstream.common.pipeline.PlannedOperator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Compares checkpoint-visible results; engine windows beyond the replay watermarks are ignored. */
public final class ResultComparator {
    private static final double TOLERANCE = 1e-6;
    private static final int MAX_MISMATCHES = 50;

    public CompareResult compare(ReferenceResult ref, List<EngineWindowRow> engineRows,
                                 List<EngineAlertRow> engineAlerts, long checkpointId, long durationMs) {
        Map<WindowIdentity, WindowResult> expectedWindows = new TreeMap<>();
        Map<WindowIdentity, EngineWindowRow> actualWindows = new TreeMap<>();
        Map<AlertIdentity, Alert> expectedAlerts = new TreeMap<>();
        Map<AlertIdentity, EngineAlertRow> actualAlerts = new TreeMap<>();
        long referenceResults = 0;
        for (var sink : ref.windowsBySink().entrySet()) {
            referenceResults += sink.getValue().size();
            for (WindowResult row : sink.getValue()) {
                expectedWindows.put(new WindowIdentity(sink.getKey(), row.key(), row.windowStart()), row);
            }
        }
        for (EngineWindowRow row : engineRows) {
            actualWindows.put(new WindowIdentity(row.sinkId(), row.key(), row.windowStart()), row);
        }
        for (var sink : ref.alertsBySink().entrySet()) {
            referenceResults += sink.getValue().size();
            for (Alert alert : sink.getValue()) {
                expectedAlerts.put(new AlertIdentity(sink.getKey(), alert.alertId()), alert);
            }
        }
        for (EngineAlertRow alert : engineAlerts) {
            actualAlerts.put(new AlertIdentity(alert.sinkId(), alert.alertId()), alert);
        }

        List<Mismatch> mismatches = new ArrayList<>();
        long matched = 0;
        boolean match = true;
        for (var entry : expectedWindows.entrySet()) {
            WindowIdentity id = entry.getKey();
            EngineWindowRow actual = actualWindows.remove(id);
            String detail = actual == null ? "Reference window is missing" : difference(entry.getValue(), actual);
            if (detail == null) {
                matched++;
            } else {
                match = false;
                sample(mismatches, actual == null ? "MISSING" : "DIFFERENT",
                        id.sinkId(), id.key(), id.windowStart(), detail);
            }
        }

        Map<String, String> windowsBySink = new HashMap<>();
        for (PlannedOperator operator : ref.plan().operators()) {
            if (operator.type().equals("sink") && operator.config().path("kind").asText().equals("postgres.windows")) {
                windowsBySink.put(operator.id(), operator.inputs().getFirst());
            }
        }
        Map<String, Map<String, Long>> closedThroughByKey =
                actualWindows.isEmpty() ? Map.of() : closedThroughByKey(ref);
        for (var entry : actualWindows.entrySet()) {
            WindowIdentity id = entry.getKey();
            String windowId = windowsBySink.get(id.sinkId());
            Map<String, Long> watermarks = closedThroughByKey.get(id.key());
            Long watermark = watermarks == null || windowId == null ? null : watermarks.get(windowId);
            if (watermark != null && entry.getValue().windowEnd() <= watermark) {
                match = false;
                sample(mismatches, "EXTRA", id.sinkId(), id.key(), id.windowStart(),
                        "Engine window was not produced by the reference");
            }
        }
        for (var entry : expectedAlerts.entrySet()) {
            AlertIdentity id = entry.getKey();
            if (actualAlerts.remove(id) != null) {
                matched++;
            } else {
                match = false;
                sample(mismatches, "MISSING", id.sinkId(), entry.getValue().key(), 0,
                        "Reference alert is missing: " + id.alertId());
            }
        }
        for (AlertIdentity id : actualAlerts.keySet()) {
            match = false;
            sample(mismatches, "EXTRA", id.sinkId(), null, 0,
                    "Engine alert was not produced by the reference: " + id.alertId());
        }
        return new CompareResult(checkpointId, referenceResults, (long) engineRows.size() + engineAlerts.size(),
                matched, List.copyOf(mismatches), ref.recordsReplayed(), durationMs, match);
    }

    private static String difference(WindowResult expected, EngineWindowRow actual) {
        if (expected.eventCount() != actual.eventCount()) {
            return "eventCount differs: expected " + expected.eventCount() + ", actual " + actual.eventCount();
        }
        if (!expected.values().keySet().equals(actual.values().keySet())) {
            return "Aggregation names differ";
        }
        for (var value : expected.values().entrySet()) {
            if (!(Math.abs(value.getValue() - actual.values().get(value.getKey())) <= TOLERANCE)) {
                return "Aggregation values differ (absolute tolerance " + TOLERANCE + ")";
            }
        }
        return null;
    }

    private static Map<String, Map<String, Long>> closedThroughByKey(ReferenceResult ref) {
        Map<String, Map<String, Long>> result = new HashMap<>();
        for (var partition : ref.keysByPartition().entrySet()) {
            Map<String, Long> watermarks = ref.watermarksByPartition().get(partition.getKey());
            if (watermarks == null) {
                continue;
            }
            for (String key : partition.getValue()) {
                Map<String, Long> previous = result.putIfAbsent(key, watermarks);
                if (previous != null) {
                    Map<String, Long> merged = new HashMap<>(previous);
                    watermarks.forEach((window, watermark) -> merged.merge(window, watermark, Math::max));
                    result.put(key, merged);
                }
            }
        }
        return result;
    }

    private static void sample(List<Mismatch> mismatches, String type, String sinkId, String key,
                               long windowStart, String detail) {
        if (mismatches.size() < MAX_MISMATCHES) {
            mismatches.add(new Mismatch(type, sinkId, key, windowStart, detail));
        }
    }

    private record WindowIdentity(String sinkId, String key, long windowStart) implements Comparable<WindowIdentity> {
        @Override
        public int compareTo(WindowIdentity other) {
            int order = sinkId.compareTo(other.sinkId);
            if (order == 0) {
                order = key.compareTo(other.key);
            }
            return order == 0 ? Long.compare(windowStart, other.windowStart) : order;
        }
    }

    private record AlertIdentity(String sinkId, String alertId) implements Comparable<AlertIdentity> {
        @Override
        public int compareTo(AlertIdentity other) {
            int order = sinkId.compareTo(other.sinkId);
            return order == 0 ? alertId.compareTo(other.alertId) : order;
        }
    }
}
