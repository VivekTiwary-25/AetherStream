package io.aetherstream.engine.coordinator;

import java.util.Map;

/** Source offsets for checkpoint 0 (contract 03 section 4). */
@FunctionalInterface
public interface OffsetsLookup {
    /** startFrom "earliest" = beginning offsets, anything else ("latest") = end offsets. Keys 0..partitions-1. */
    Map<Integer, Long> offsets(String topic, int partitions, String startFrom);
}
