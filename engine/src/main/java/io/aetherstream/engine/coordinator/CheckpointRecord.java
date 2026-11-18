package io.aetherstream.engine.coordinator;

import java.util.Map;

/** Row of table checkpoints. state: PENDING|COMPLETED|ABORTED. */
public record CheckpointRecord(String executionId, long checkpointId, long generation, String state,
                               String manifestKey, Map<Integer, Long> sourceOffsets, long startedAtMs,
                               Long completedAtMs, Long durationMs) {
    public static final String PENDING = "PENDING";
    public static final String COMPLETED = "COMPLETED";
    public static final String ABORTED = "ABORTED";
}
