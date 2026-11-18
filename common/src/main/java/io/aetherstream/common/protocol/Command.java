package io.aetherstream.common.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.aetherstream.common.pipeline.ExecutionPlan;
import java.util.List;
import java.util.Map;

/** Commands piggybacked on heartbeat responses (contract 03 section 2). JSON discriminator field: type. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Command.Assign.class, name = "ASSIGN"),
        @JsonSubTypes.Type(value = Command.TriggerCheckpoint.class, name = "TRIGGER_CHECKPOINT"),
        @JsonSubTypes.Type(value = Command.Cancel.class, name = "CANCEL"),
        @JsonSubTypes.Type(value = Command.Kill.class, name = "KILL"),
        @JsonSubTypes.Type(value = Command.SetSinkDelay.class, name = "SET_SINK_DELAY")
})
public sealed interface Command {
    /** restoreCheckpointId 0 = start fresh from startOffsets with empty state. */
    record Assign(String executionId, long generation, ExecutionPlan plan, List<Integer> partitions,
                  long restoreCheckpointId, String manifestKey, Map<Integer, Long> startOffsets) implements Command {}

    record TriggerCheckpoint(String executionId, long generation, long checkpointId) implements Command {}

    record Cancel(String executionId, long generation) implements Command {}

    record Kill() implements Command {}

    record SetSinkDelay(long delayMs) implements Command {}
}
