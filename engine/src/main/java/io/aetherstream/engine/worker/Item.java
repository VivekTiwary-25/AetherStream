package io.aetherstream.engine.worker;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/** Items flowing through the two runtime queues. */
sealed interface Item permits Item.RecordItem, Item.OutputBatch, Item.BarrierItem {
    /** Queue 1: one Kafka record. */
    record RecordItem(int partition, long offset, String value) implements Item {}

    /** Queue 2: the outputs (0..n) of exactly one input record. */
    record OutputBatch(int partition, long offset, List<SinkRow> rows) implements Item {}

    /**
     * Checkpoint barrier. offsets: per partition, the next offset not yet put into queue 1 when the barrier was taken.
     * snapshots: null in queue 1, PartitionPipeline snapshots (per partition) in queue 2.
     */
    record BarrierItem(long checkpointId, Map<Integer, Long> offsets, Map<Integer, JsonNode> snapshots)
            implements Item {}
}
