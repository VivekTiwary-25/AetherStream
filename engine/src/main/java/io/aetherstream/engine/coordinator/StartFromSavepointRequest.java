package io.aetherstream.engine.coordinator;

import io.aetherstream.common.pipeline.ExecutionPlan;

/** Coordinator-only extension of the contract 03 start request; null fromSavepoint starts fresh. */
public record StartFromSavepointRequest(String executionId, ExecutionPlan plan, String fromSavepoint) {}
