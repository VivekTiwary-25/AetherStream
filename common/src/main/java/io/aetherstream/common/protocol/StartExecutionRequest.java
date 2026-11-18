package io.aetherstream.common.protocol;

import io.aetherstream.common.pipeline.ExecutionPlan;

/** POST /coord/executions. */
public record StartExecutionRequest(String executionId, ExecutionPlan plan) {}
