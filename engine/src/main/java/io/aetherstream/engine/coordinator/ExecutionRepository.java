package io.aetherstream.engine.coordinator;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Persistence for tables executions, checkpoints and audit_log (infra/db/schema.sql). */
public interface ExecutionRepository {
    /** Inserts the execution, or overwrites plan/state/generation if a row with this id already exists. */
    void insertExecution(ExecutionRecord execution);

    void updateState(String executionId, String state);

    /** UPDATE executions SET generation = generation + 1 (row lock, serialises with fenced sink batches). Returns the new value. */
    long bumpGeneration(String executionId);

    void setRestoredFrom(String executionId, long checkpointId);

    Optional<ExecutionRecord> findExecution(String executionId);

    /** Executions in state RUNNING or RECOVERING, oldest first. */
    List<ExecutionRecord> findActive();

    /** Inserts (or replaces) a checkpoint row. */
    void insertCheckpoint(CheckpointRecord checkpoint);

    /** Marks a checkpoint COMPLETED with manifest key, offsets, completion time and duration. */
    void completeCheckpoint(String executionId, long checkpointId, String manifestKey, Map<Integer, Long> sourceOffsets,
                            long completedAtMs, long durationMs);

    /** Sets one checkpoint ABORTED if it is still PENDING. */
    void abortCheckpoint(String executionId, long checkpointId);

    /** Sets every PENDING checkpoint of the execution ABORTED; returns how many. */
    int abortPendingCheckpoints(String executionId);

    Optional<CheckpointRecord> findCheckpoint(String executionId, long checkpointId);

    Optional<CheckpointRecord> latestCompleted(String executionId);

    /** Highest checkpoint id of the execution (0 if none). */
    long maxCheckpointId(String executionId);

    /** audit_log row with actor "coordinator". */
    void audit(String action, Map<String, Object> detail);
}
