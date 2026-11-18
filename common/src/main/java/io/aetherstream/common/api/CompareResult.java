package io.aetherstream.common.api;

import java.util.List;

/** POST /api/compare (contract 04). */
public record CompareResult(long checkpointId, long referenceResults, long engineResults, long matched,
                            List<Mismatch> mismatches, long recordsReplayed, long durationMs, boolean match) {
    /** type: MISSING|DIFFERENT|EXTRA. */
    public record Mismatch(String type, String sinkId, String key, long windowStart, String detail) {}
}
