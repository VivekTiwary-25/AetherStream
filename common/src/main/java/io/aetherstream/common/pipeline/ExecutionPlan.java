package io.aetherstream.common.pipeline;

import java.util.List;

/** Contract 02 section 5. operators are in deterministic topological order. */
public record ExecutionPlan(String pipelineId, int pipelineVersion, String topic, int partitions, String startFrom,
                            List<PlannedOperator> operators) {}
