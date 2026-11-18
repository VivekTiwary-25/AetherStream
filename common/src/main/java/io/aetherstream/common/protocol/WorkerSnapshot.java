package io.aetherstream.common.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/** S3 object executions/{executionId}/checkpoints/{checkpointId}/worker-{workerId}.json (contract 03 section 4). */
public record WorkerSnapshot(long checkpointId, long generation, String workerId,
                             Map<Integer, PartitionState> partitions) {
    public record PartitionState(long nextOffset, JsonNode state) {}
}
