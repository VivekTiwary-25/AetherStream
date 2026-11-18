package io.aetherstream.common.pipeline;

/** Contract 02 section 3. nodeId may be null for pipeline-level errors. */
public record ValidationError(String nodeId, String code, String message) {}
