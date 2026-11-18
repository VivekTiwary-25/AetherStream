package io.aetherstream.common.protocol;

import java.util.Map;

/** S3 object executions/{executionId}/checkpoints/{checkpointId}/manifest.json (contract 03 section 4). */
public record CheckpointManifest(String executionId, long checkpointId, long generation, String pipelineId,
                                 int pipelineVersion, int protocolVersion, String createdAt,
                                 Map<Integer, PartitionEntry> partitions) {
    public record PartitionEntry(long nextOffset, String objectKey) {}
}
