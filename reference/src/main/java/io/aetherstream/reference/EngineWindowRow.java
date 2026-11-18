package io.aetherstream.reference;

import java.util.Map;

public record EngineWindowRow(String sinkId, String key, long windowStart, long windowEnd,
                              Map<String, Double> values, long eventCount) {}
