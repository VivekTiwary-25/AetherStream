package io.aetherstream.api.compare;

import io.aetherstream.common.Alert;
import io.aetherstream.common.DeadLetter;
import io.aetherstream.common.WindowResult;
import io.aetherstream.common.pipeline.ExecutionPlan;
import io.aetherstream.common.pipeline.PlannedOperator;
import io.aetherstream.compiler.ops.Collector;
import io.aetherstream.compiler.ops.EnvelopeValidator;
import io.aetherstream.compiler.ops.PartitionPipeline;
import io.aetherstream.reference.RawRecord;
import io.aetherstream.reference.ReferenceResult;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Streams a savepoint lineage through the shared operators one partition at a time, like
 * {@code ReferenceExecutor.run(List)}, but never materialises Kafka records and keeps only the
 * results whose event-time end lies within {@code retentionMs} of the partition's final maxTs.
 * Operator state is still built from the lineage root, so retained results are exact; memory is
 * bounded by the retention horizon (results per key per horizon) instead of the run length.
 */
final class BoundedReplay {
    /** Retention must cover several of the plan's largest windows so a comparison is never empty. */
    static final int MIN_WINDOWS_RETAINED = 3;

    @FunctionalInterface
    interface PartitionReader {
        /** Delivers the records of one partition in {@code [fromInclusive, toExclusive)} in offset order. */
        void read(int partition, long fromInclusive, long toExclusive, Consumer<RawRecord> sink);
    }

    record Stage(ExecutionPlan plan, Map<Integer, Long> from, Map<Integer, Long> to) {}

    /**
     * {@code cutoffByKey}: results for a key are compared only if their end is strictly greater.
     * {@code floor} is the smallest cutoff (a safe SQL pre-filter); {@code peakRetained} is the
     * largest number of reference results held at any moment during the replay.
     */
    record Outcome(ReferenceResult reference, Map<String, Long> cutoffByKey, long floor,
                   long retentionMs, long peakRetained) {
        boolean keeps(String key, long end) {
            return end > cutoffByKey.getOrDefault(key, floor);
        }
    }

    private BoundedReplay() {}

    static long effectiveRetention(ExecutionPlan plan, long configuredMs) {
        long widest = 0;
        for (PlannedOperator operator : plan.operators()) {
            if (operator.type().equals("window")) {
                long span = operator.config().path("sizeMs").asLong(0)
                        + operator.config().path("outOfOrdernessMs").asLong(5000);
                widest = Math.max(widest, span);
            }
        }
        return Math.max(Math.max(configuredMs, 0), widest * MIN_WINDOWS_RETAINED);
    }

    static Outcome run(List<Stage> stages, PartitionReader reader, long configuredRetentionMs) {
        if (stages.isEmpty()) throw new IllegalArgumentException("No replay stages");
        for (Stage stage : stages) {
            if (!stage.from().keySet().equals(stage.to().keySet())) {
                throw new IllegalArgumentException("offset maps must have identical partition sets");
            }
            for (int partition : stage.from().keySet()) {
                if (partition < 0 || partition >= stage.plan().partitions()) {
                    throw new IllegalArgumentException("Partition outside plan: " + partition);
                }
            }
        }
        ExecutionPlan plan = stages.getLast().plan();
        long retentionMs = effectiveRetention(plan, configuredRetentionMs);
        Map<String, List<WindowResult>> windows = new TreeMap<>();
        Map<String, List<Alert>> alerts = new TreeMap<>();
        for (PlannedOperator operator : plan.operators()) {
            if (operator.type().equals("sink")) {
                String kind = operator.config().path("kind").asText();
                if (kind.equals("postgres.windows")) windows.put(operator.id(), new ArrayList<>());
                else if (kind.equals("postgres.alerts")) alerts.put(operator.id(), new ArrayList<>());
            }
        }
        Map<Integer, Map<String, Long>> watermarks = new TreeMap<>();
        Map<Integer, Set<String>> keys = new TreeMap<>();
        Map<String, Long> cutoffByKey = new HashMap<>();
        Long floor = null;
        long[] replayed = {0};
        long finalized = 0;
        long peak = 0;
        for (int partition = 0; partition < plan.partitions(); partition++) {
            final int p = partition;
            PartitionPipeline pipeline = null;
            Window window = null;
            Set<String> partitionKeys = new TreeSet<>();
            for (Stage stage : stages) {
                PartitionPipeline current = pipeline == null ? new PartitionPipeline(stage.plan(), p)
                        : PartitionPipeline.restore(stage.plan(), p, pipeline.snapshot());
                pipeline = current;
                // Only the final stage's outputs belong to the compared execution.
                Window retained = new Window(retentionMs, finalized, peak);
                window = retained;
                Long from = stage.from().get(p);
                Long to = stage.to().get(p);
                if (from == null || from >= to) continue;
                long[] last = {from - 1};
                String topic = stage.plan().topic();
                reader.read(p, from, to, record -> {
                    if (record.partition() != p || record.offset() <= last[0] || record.offset() >= to) {
                        throw new IllegalStateException("Record " + record.partition() + "@" + record.offset()
                                + " is outside partition " + p + " range or out of offset order");
                    }
                    last[0] = record.offset();
                    // Include ancestor keys when checking for extra checkpoint-visible windows.
                    if (EnvelopeValidator.parse(topic, p, record.offset(), record.value())
                            instanceof EnvelopeValidator.Accepted accepted) {
                        partitionKeys.add(accepted.envelope().key());
                    }
                    current.process(record.offset(), record.value(), retained);
                    retained.evict(current.maxTs());
                    replayed[0]++;
                });
                peak = Math.max(peak, retained.peak);
            }
            window.evict(pipeline.maxTs());
            long cutoff = cutoff(pipeline.maxTs(), retentionMs);
            for (Held held : window.held) {
                if (held.window() != null) windows.get(held.sinkId()).add(held.window());
                else alerts.get(held.sinkId()).add(held.alert());
            }
            finalized += window.held.size();
            peak = Math.max(peak, finalized);
            watermarks.put(p, pipeline.watermarks());
            keys.put(p, partitionKeys);
            if (!partitionKeys.isEmpty()) {
                floor = floor == null ? cutoff : Math.min(floor, cutoff);
                for (String key : partitionKeys) cutoffByKey.merge(key, cutoff, Math::max);
            }
        }
        long sqlFloor = floor == null ? Long.MIN_VALUE : floor;
        // A key replayed in several partitions uses its strictest cutoff, the same one applied to engine rows.
        windows.values().forEach(list -> list.removeIf(row ->
                row.windowEnd() <= cutoffByKey.getOrDefault(row.key(), sqlFloor)));
        alerts.values().forEach(list -> list.removeIf(alert ->
                alert.eventTime() <= cutoffByKey.getOrDefault(alert.key(), sqlFloor)));
        ReferenceResult reference = new ReferenceResult(plan, windows, alerts, List.<DeadLetter>of(),
                watermarks, keys, replayed[0]);
        return new Outcome(reference, Map.copyOf(cutoffByKey), sqlFloor, retentionMs, peak);
    }

    static long cutoff(long maxTs, long retentionMs) {
        return maxTs < Long.MIN_VALUE + retentionMs ? Long.MIN_VALUE : maxTs - retentionMs;
    }

    private record Held(String sinkId, WindowResult window, Alert alert, long end) {}

    /** Per-partition output buffer; dead letters are not compared and are dropped immediately. */
    private static final class Window implements Collector {
        private final ArrayDeque<Held> held = new ArrayDeque<>();
        private final long retentionMs;
        private final long base;
        private long peak;

        Window(long retentionMs, long base, long peak) {
            this.retentionMs = retentionMs;
            this.base = base;
            this.peak = peak;
        }

        @Override public void windowResult(String sinkNodeId, WindowResult r) {
            add(new Held(sinkNodeId, r, null, r.windowEnd()));
        }

        @Override public void alert(String sinkNodeId, Alert a) {
            add(new Held(sinkNodeId, null, a, a.eventTime()));
        }

        @Override public void deadLetter(DeadLetter d) {}

        private void add(Held value) {
            held.addLast(value);
            peak = Math.max(peak, base + held.size());
        }

        /** Outputs are emitted in non-decreasing end order per partition, so the head is always oldest. */
        void evict(long maxTs) {
            long cutoff = cutoff(maxTs, retentionMs);
            while (!held.isEmpty() && held.peekFirst().end() <= cutoff) held.removeFirst();
        }
    }
}
