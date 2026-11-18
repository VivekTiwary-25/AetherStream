package io.aetherstream.engine.coordinator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** In-memory ExecutionRepository for tests (and local experiments). Thread-safe via synchronized. */
public class InMemoryExecutionRepository implements ExecutionRepository {
    public record AuditRow(String action, Map<String, Object> detail) {}

    private final Map<String, ExecutionRecord> executions = new LinkedHashMap<>();
    private final Map<String, TreeMap<Long, CheckpointRecord>> checkpoints = new TreeMap<>();
    private final List<AuditRow> audit = new ArrayList<>();

    @Override
    public synchronized void insertExecution(ExecutionRecord execution) {
        executions.put(execution.id(), execution);
    }

    @Override
    public synchronized void updateState(String executionId, String state) {
        ExecutionRecord e = require(executionId);
        executions.put(executionId, new ExecutionRecord(e.id(), e.pipelineId(), e.pipelineVersion(), e.plan(), state,
                e.generation(), e.restoredFromCheckpoint(), e.createdAtMs()));
    }

    @Override
    public synchronized long bumpGeneration(String executionId) {
        ExecutionRecord e = require(executionId);
        long next = e.generation() + 1;
        executions.put(executionId, new ExecutionRecord(e.id(), e.pipelineId(), e.pipelineVersion(), e.plan(), e.state(),
                next, e.restoredFromCheckpoint(), e.createdAtMs()));
        return next;
    }

    @Override
    public synchronized void setRestoredFrom(String executionId, long checkpointId) {
        ExecutionRecord e = require(executionId);
        executions.put(executionId, new ExecutionRecord(e.id(), e.pipelineId(), e.pipelineVersion(), e.plan(), e.state(),
                e.generation(), checkpointId, e.createdAtMs()));
    }

    @Override
    public synchronized Optional<ExecutionRecord> findExecution(String executionId) {
        return Optional.ofNullable(executions.get(executionId));
    }

    @Override
    public synchronized List<ExecutionRecord> findActive() {
        return executions.values().stream()
                .filter(e -> "RUNNING".equals(e.state()) || "RECOVERING".equals(e.state()))
                .sorted(Comparator.comparingLong(ExecutionRecord::createdAtMs))
                .toList();
    }

    @Override
    public synchronized void insertCheckpoint(CheckpointRecord checkpoint) {
        checkpoints.computeIfAbsent(checkpoint.executionId(), k -> new TreeMap<>())
                .put(checkpoint.checkpointId(), checkpoint);
    }

    @Override
    public synchronized void completeCheckpoint(String executionId, long checkpointId, String manifestKey,
                                                Map<Integer, Long> sourceOffsets, long completedAtMs, long durationMs) {
        CheckpointRecord c = findCheckpoint(executionId, checkpointId)
                .orElseThrow(() -> new IllegalStateException("no checkpoint " + checkpointId));
        insertCheckpoint(new CheckpointRecord(executionId, checkpointId, c.generation(), CheckpointRecord.COMPLETED,
                manifestKey, new TreeMap<>(sourceOffsets), c.startedAtMs(), completedAtMs, durationMs));
    }

    @Override
    public synchronized void abortCheckpoint(String executionId, long checkpointId) {
        findCheckpoint(executionId, checkpointId)
                .filter(c -> CheckpointRecord.PENDING.equals(c.state()))
                .ifPresent(c -> insertCheckpoint(aborted(c)));
    }

    @Override
    public synchronized int abortPendingCheckpoints(String executionId) {
        int n = 0;
        for (CheckpointRecord c : List.copyOf(checkpointsOf(executionId).values())) {
            if (CheckpointRecord.PENDING.equals(c.state())) {
                insertCheckpoint(aborted(c));
                n++;
            }
        }
        return n;
    }

    @Override
    public synchronized Optional<CheckpointRecord> findCheckpoint(String executionId, long checkpointId) {
        return Optional.ofNullable(checkpointsOf(executionId).get(checkpointId));
    }

    @Override
    public synchronized Optional<CheckpointRecord> latestCompleted(String executionId) {
        return checkpointsOf(executionId).descendingMap().values().stream()
                .filter(c -> CheckpointRecord.COMPLETED.equals(c.state()))
                .findFirst();
    }

    @Override
    public synchronized long maxCheckpointId(String executionId) {
        TreeMap<Long, CheckpointRecord> m = checkpointsOf(executionId);
        return m.isEmpty() ? 0 : m.lastKey();
    }

    @Override
    public synchronized void audit(String action, Map<String, Object> detail) {
        audit.add(new AuditRow(action, detail == null ? Map.of() : new LinkedHashMap<>(detail)));
    }

    public synchronized List<AuditRow> auditRows() {
        return List.copyOf(audit);
    }

    @Override
    public synchronized List<SavepointInfo> listSavepoints() {
        List<SavepointInfo> out = new ArrayList<>();
        for (int i = audit.size() - 1; i >= 0; i--) {
            AuditRow row = audit.get(i);
            if ("savepoint.created".equals(row.action())) {
                out.add(io.aetherstream.common.Json.MAPPER.convertValue(row.detail(), SavepointInfo.class));
            }
        }
        return List.copyOf(out);
    }

    private TreeMap<Long, CheckpointRecord> checkpointsOf(String executionId) {
        return checkpoints.getOrDefault(executionId, new TreeMap<>());
    }

    private ExecutionRecord require(String executionId) {
        ExecutionRecord e = executions.get(executionId);
        if (e == null) {
            throw new IllegalStateException("no execution " + executionId);
        }
        return e;
    }

    private static CheckpointRecord aborted(CheckpointRecord c) {
        return new CheckpointRecord(c.executionId(), c.checkpointId(), c.generation(), CheckpointRecord.ABORTED,
                null, c.sourceOffsets(), c.startedAtMs(), c.completedAtMs(), c.durationMs());
    }
}
