package io.aetherstream.common.protocol;

import java.util.List;

/** GET /coord/state (contract 03 section 7). execution may be null. */
public record ClusterState(ExecutionSummary execution, List<WorkerView> workers, long sinkDelayMs) {
    public record ExecutionSummary(String id, String pipelineId, String state, long generation,
                                   Long restoredFromCheckpoint, Long lastCompletedCheckpoint,
                                   Long lastCheckpointDurationMs) {}

    public record WorkerView(String workerId, boolean alive, String state, List<Integer> partitions,
                             QueueStatus sourceQueue, QueueStatus sinkQueue, boolean backpressured,
                             double recordsPerSec, long lastHeartbeatMs) {}
}
