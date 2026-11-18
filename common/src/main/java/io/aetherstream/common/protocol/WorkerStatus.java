package io.aetherstream.common.protocol;

import java.util.List;
import java.util.Map;

/** Heartbeat body (contract 03 section 2). state: IDLE|RESTORING|RUNNING. */
public record WorkerStatus(int protocolVersion, String workerId, String executionId, long generation, String state,
                           List<Integer> partitions, QueueStatus sourceQueue, QueueStatus sinkQueue,
                           boolean backpressured, long processedRecords, double recordsPerSec,
                           long lastCheckpointId, Map<Integer, Long> lag) {}
