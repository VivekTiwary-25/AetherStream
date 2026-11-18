package io.aetherstream.common.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** outputType: EVENT|KEYED|WINDOW_RESULT|ALERT|NONE. */
public record PlannedOperator(String id, String type, JsonNode config, List<String> inputs, String outputType) {}
