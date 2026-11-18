package io.aetherstream.common.api;

/** Contract 04. state: CREATED|RUNNING|RECOVERING|STOPPED|FAILED. Also returned by coordinator /coord/executions. */
public record ExecutionInfo(String id, String pipelineId, int pipelineVersion, String state, long generation,
                            Long restoredFromCheckpoint, Long lastCompletedCheckpoint, String createdAt) {}
