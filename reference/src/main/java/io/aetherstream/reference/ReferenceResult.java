package io.aetherstream.reference;

import io.aetherstream.common.Alert;
import io.aetherstream.common.DeadLetter;
import io.aetherstream.common.WindowResult;
import io.aetherstream.common.pipeline.ExecutionPlan;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Replay output at the supplied offset frontier, without flushing still-open windows.
 * Watermarks are indexed by source partition, then window node id; keys include all
 * accepted envelopes, even those filtered out before a window. Replayed records include rejections.
 * The plan retains the sink-to-window mapping needed for checkpoint-scoped comparison.
 */
public record ReferenceResult(ExecutionPlan plan,
                              Map<String, List<WindowResult>> windowsBySink,
                              Map<String, List<Alert>> alertsBySink,
                              List<DeadLetter> deadLetters,
                              Map<Integer, Map<String, Long>> watermarksByPartition,
                              Map<Integer, Set<String>> keysByPartition,
                              long recordsReplayed) {
    public ReferenceResult {
        windowsBySink = freezeLists(windowsBySink);
        alertsBySink = freezeLists(alertsBySink);
        deadLetters = List.copyOf(deadLetters);
        Map<Integer, Map<String, Long>> watermarks = new TreeMap<>();
        watermarksByPartition.forEach((partition, values) ->
                watermarks.put(partition, Collections.unmodifiableMap(new TreeMap<>(values))));
        watermarksByPartition = Collections.unmodifiableMap(watermarks);
        Map<Integer, Set<String>> keys = new TreeMap<>();
        keysByPartition.forEach((partition, values) ->
                keys.put(partition, Collections.unmodifiableSet(new TreeSet<>(values))));
        keysByPartition = Collections.unmodifiableMap(keys);
    }

    private static <T> Map<String, List<T>> freezeLists(Map<String, List<T>> source) {
        Map<String, List<T>> result = new TreeMap<>();
        source.forEach((sink, values) -> result.put(sink, List.copyOf(values)));
        return Collections.unmodifiableMap(result);
    }
}
