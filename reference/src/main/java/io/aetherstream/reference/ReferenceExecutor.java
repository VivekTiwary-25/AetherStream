package io.aetherstream.reference;

import io.aetherstream.common.Alert;
import io.aetherstream.common.DeadLetter;
import io.aetherstream.common.WindowResult;
import io.aetherstream.common.pipeline.ExecutionPlan;
import io.aetherstream.common.pipeline.PlannedOperator;
import io.aetherstream.compiler.ops.EnvelopeValidator;
import io.aetherstream.compiler.ops.ListCollector;
import io.aetherstream.compiler.ops.PartitionPipeline;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Deterministic, single-process replay using the same operators as the engine. */
public final class ReferenceExecutor {
    public ReferenceResult run(ExecutionPlan plan, Map<Integer, List<RawRecord>> recordsByPartition) {
        return run(List.of(new ReplayStage(plan, recordsByPartition)));
    }

    /**
     * Replays a savepoint lineage oldest first, restoring each next plan from the preceding
     * operator snapshot. Only the final stage's outputs belong to the compared execution.
     */
    public ReferenceResult run(List<ReplayStage> stages) {
        ExecutionPlan plan = stages.getLast().plan();
        for (ReplayStage stage : stages) {
            for (int partition : stage.recordsByPartition().keySet()) {
                if (partition < 0 || partition >= stage.plan().partitions()) {
                    throw new IllegalArgumentException("Partition outside plan: " + partition);
                }
            }
        }
        Map<String, List<WindowResult>> windows = new TreeMap<>();
        Map<String, List<Alert>> alerts = new TreeMap<>();
        for (PlannedOperator operator : plan.operators()) {
            if (operator.type().equals("sink")) {
                if (operator.config().path("kind").asText().equals("postgres.windows")) {
                    windows.put(operator.id(), new ArrayList<>());
                } else if (operator.config().path("kind").asText().equals("postgres.alerts")) {
                    alerts.put(operator.id(), new ArrayList<>());
                }
            }
        }
        List<DeadLetter> deadLetters = new ArrayList<>();
        Map<Integer, Map<String, Long>> watermarks = new TreeMap<>();
        Map<Integer, Set<String>> keys = new TreeMap<>();
        long recordsReplayed = 0;
        for (int partition = 0; partition < plan.partitions(); partition++) {
            PartitionPipeline pipeline = null;
            ListCollector collector = null;
            Set<String> partitionKeys = new TreeSet<>();
            for (ReplayStage stage : stages) {
                pipeline = pipeline == null ? new PartitionPipeline(stage.plan(), partition)
                        : PartitionPipeline.restore(stage.plan(), partition, pipeline.snapshot());
                collector = new ListCollector();
                List<RawRecord> records = new ArrayList<>(stage.recordsByPartition().getOrDefault(partition, List.of()));
                records.sort(Comparator.comparingLong(RawRecord::offset));
                for (RawRecord record : records) {
                    if (record.partition() != partition) {
                        throw new IllegalArgumentException("Record partition does not match bucket " + partition);
                    }
                    // Include ancestor keys when checking for extra checkpoint-visible windows.
                    if (EnvelopeValidator.parse(stage.plan().topic(), partition, record.offset(), record.value())
                            instanceof EnvelopeValidator.Accepted accepted) {
                        partitionKeys.add(accepted.envelope().key());
                    }
                    pipeline.process(record.offset(), record.value(), collector);
                    recordsReplayed++;
                }
            }
            for (ListCollector.Output output : collector.outputs()) {
                if (output instanceof ListCollector.WindowOutput window) {
                    windows.get(window.sinkNodeId()).add(window.result());
                } else if (output instanceof ListCollector.AlertOutput alert) {
                    alerts.get(alert.sinkNodeId()).add(alert.alert());
                } else if (output instanceof ListCollector.DeadLetterOutput deadLetter) {
                    deadLetters.add(deadLetter.deadLetter());
                }
            }
            watermarks.put(partition, pipeline.watermarks());
            keys.put(partition, partitionKeys);
        }
        return new ReferenceResult(plan, windows, alerts, deadLetters, watermarks, keys, recordsReplayed);
    }

    public record ReplayStage(ExecutionPlan plan, Map<Integer, List<RawRecord>> recordsByPartition) {}
}
