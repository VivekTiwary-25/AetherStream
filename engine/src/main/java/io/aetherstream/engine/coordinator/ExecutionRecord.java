package io.aetherstream.engine.coordinator;

import io.aetherstream.common.pipeline.ExecutionPlan;

/** Row of table executions. */
public record ExecutionRecord(String id, String pipelineId, int pipelineVersion, ExecutionPlan plan, String state,
                              long generation, Long restoredFromCheckpoint, long createdAtMs) {}
