package io.aetherstream.common;

import java.util.Map;

/** Output of a window node (contract 02 section 4.3). nodeId = window node id; values = aggregation name to value. */
public record WindowResult(String nodeId, String key, long windowStart, long windowEnd,
                           Map<String, Double> values, long eventCount) {}
