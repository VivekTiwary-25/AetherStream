package io.aetherstream.common.pipeline;

import java.util.List;

public record ValidationResult(boolean valid, List<ValidationError> errors, ExecutionPlan plan) {}
