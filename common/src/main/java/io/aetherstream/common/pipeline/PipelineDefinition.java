package io.aetherstream.common.pipeline;

import java.util.List;

/** Contract 02 section 1. */
public record PipelineDefinition(String id, int version, String name, List<Node> nodes, List<Edge> edges) {}
