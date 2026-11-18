package io.aetherstream.engine.coordinator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import io.aetherstream.common.Json;
import io.aetherstream.common.pipeline.ExecutionPlan;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import javax.sql.DataSource;

/** JDBC implementation over the tables of infra/db/schema.sql. */
public class PostgresExecutionRepository implements ExecutionRepository {
    private static final TypeReference<TreeMap<Integer, Long>> OFFSETS = new TypeReference<>() {};
    private static final String EXEC_COLUMNS =
            "id, pipeline_id, pipeline_version, plan::text AS plan, state, generation, restored_from_checkpoint, created_at";
    private static final String CP_COLUMNS =
            "execution_id, checkpoint_id, generation, state, manifest_key, source_offsets::text AS source_offsets, "
                    + "started_at, completed_at, duration_ms";

    private final DataSource dataSource;

    public PostgresExecutionRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection c) throws SQLException;
    }

    private <T> T withConnection(SqlWork<T> work) {
        try (Connection c = dataSource.getConnection()) {
            return work.run(c);
        } catch (SQLException e) {
            throw new IllegalStateException("database error: " + e.getMessage(), e);
        }
    }

    private int update(String sql, Object... params) {
        return withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    ps.setObject(i + 1, params[i]);
                }
                return ps.executeUpdate();
            }
        });
    }

    @Override
    public void insertExecution(ExecutionRecord e) {
        update("INSERT INTO executions (id, pipeline_id, pipeline_version, plan, state, generation, "
                        + "restored_from_checkpoint, created_at, updated_at) VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?, now()) "
                        + "ON CONFLICT (id) DO UPDATE SET pipeline_id = EXCLUDED.pipeline_id, "
                        + "pipeline_version = EXCLUDED.pipeline_version, plan = EXCLUDED.plan, state = EXCLUDED.state, "
                        + "generation = EXCLUDED.generation, restored_from_checkpoint = EXCLUDED.restored_from_checkpoint, "
                        + "updated_at = now()",
                e.id(), e.pipelineId(), e.pipelineVersion(), toJson(e.plan()), e.state(), e.generation(),
                e.restoredFromCheckpoint(), new Timestamp(e.createdAtMs()));
    }

    @Override
    public void updateState(String executionId, String state) {
        update("UPDATE executions SET state = ?, updated_at = now() WHERE id = ?", state, executionId);
    }

    @Override
    public long bumpGeneration(String executionId) {
        return withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE executions SET generation = generation + 1, updated_at = now() WHERE id = ? RETURNING generation")) {
                ps.setString(1, executionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new SQLException("no execution " + executionId);
                    }
                    return rs.getLong(1);
                }
            }
        });
    }

    @Override
    public void setRestoredFrom(String executionId, long checkpointId) {
        update("UPDATE executions SET restored_from_checkpoint = ?, updated_at = now() WHERE id = ?",
                checkpointId, executionId);
    }

    @Override
    public Optional<ExecutionRecord> findExecution(String executionId) {
        List<ExecutionRecord> rows = queryExecutions("SELECT " + EXEC_COLUMNS + " FROM executions WHERE id = ?", executionId);
        return rows.stream().findFirst();
    }

    @Override
    public List<ExecutionRecord> findActive() {
        return queryExecutions("SELECT " + EXEC_COLUMNS
                + " FROM executions WHERE state IN ('RUNNING', 'RECOVERING') ORDER BY created_at");
    }

    private List<ExecutionRecord> queryExecutions(String sql, Object... params) {
        return withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    ps.setObject(i + 1, params[i]);
                }
                List<ExecutionRecord> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long restored = rs.getLong("restored_from_checkpoint");
                        Long restoredOrNull = rs.wasNull() ? null : restored;
                        out.add(new ExecutionRecord(rs.getString("id"), rs.getString("pipeline_id"),
                                rs.getInt("pipeline_version"), fromJson(rs.getString("plan"), ExecutionPlan.class),
                                rs.getString("state"), rs.getLong("generation"), restoredOrNull,
                                rs.getTimestamp("created_at").getTime()));
                    }
                }
                return out;
            }
        });
    }

    @Override
    public void insertCheckpoint(CheckpointRecord cp) {
        update("INSERT INTO checkpoints (execution_id, checkpoint_id, generation, state, manifest_key, source_offsets, "
                        + "started_at, completed_at, duration_ms) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?) "
                        + "ON CONFLICT (execution_id, checkpoint_id) DO UPDATE SET generation = EXCLUDED.generation, "
                        + "state = EXCLUDED.state, manifest_key = EXCLUDED.manifest_key, "
                        + "source_offsets = EXCLUDED.source_offsets, started_at = EXCLUDED.started_at, "
                        + "completed_at = EXCLUDED.completed_at, duration_ms = EXCLUDED.duration_ms",
                cp.executionId(), cp.checkpointId(), cp.generation(), cp.state(), cp.manifestKey(),
                cp.sourceOffsets() == null ? null : toJson(new TreeMap<>(cp.sourceOffsets())),
                new Timestamp(cp.startedAtMs()),
                cp.completedAtMs() == null ? null : new Timestamp(cp.completedAtMs()),
                cp.durationMs());
    }

    @Override
    public void completeCheckpoint(String executionId, long checkpointId, String manifestKey,
                                   Map<Integer, Long> sourceOffsets, long completedAtMs, long durationMs) {
        update("UPDATE checkpoints SET state = 'COMPLETED', manifest_key = ?, source_offsets = ?::jsonb, "
                        + "completed_at = ?, duration_ms = ? WHERE execution_id = ? AND checkpoint_id = ?",
                manifestKey, toJson(new TreeMap<>(sourceOffsets)), new Timestamp(completedAtMs), durationMs,
                executionId, checkpointId);
    }

    @Override
    public void abortCheckpoint(String executionId, long checkpointId) {
        update("UPDATE checkpoints SET state = 'ABORTED' WHERE execution_id = ? AND checkpoint_id = ? AND state = 'PENDING'",
                executionId, checkpointId);
    }

    @Override
    public int abortPendingCheckpoints(String executionId) {
        return update("UPDATE checkpoints SET state = 'ABORTED' WHERE execution_id = ? AND state = 'PENDING'", executionId);
    }

    @Override
    public Optional<CheckpointRecord> findCheckpoint(String executionId, long checkpointId) {
        return queryCheckpoint("SELECT " + CP_COLUMNS + " FROM checkpoints WHERE execution_id = ? AND checkpoint_id = ?",
                executionId, checkpointId);
    }

    @Override
    public Optional<CheckpointRecord> latestCompleted(String executionId) {
        return queryCheckpoint("SELECT " + CP_COLUMNS + " FROM checkpoints WHERE execution_id = ? AND state = 'COMPLETED' "
                + "ORDER BY checkpoint_id DESC LIMIT 1", executionId);
    }

    private Optional<CheckpointRecord> queryCheckpoint(String sql, Object... params) {
        return withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    ps.setObject(i + 1, params[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.<CheckpointRecord>empty();
                    }
                    String offsets = rs.getString("source_offsets");
                    Timestamp completed = rs.getTimestamp("completed_at");
                    long duration = rs.getLong("duration_ms");
                    Long durationOrNull = rs.wasNull() ? null : duration;
                    return Optional.of(new CheckpointRecord(rs.getString("execution_id"), rs.getLong("checkpoint_id"),
                            rs.getLong("generation"), rs.getString("state"), rs.getString("manifest_key"),
                            offsets == null ? null : fromJson(offsets, OFFSETS),
                            rs.getTimestamp("started_at").getTime(),
                            completed == null ? null : completed.getTime(), durationOrNull));
                }
            }
        });
    }

    @Override
    public long maxCheckpointId(String executionId) {
        return withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COALESCE(MAX(checkpoint_id), 0) FROM checkpoints WHERE execution_id = ?")) {
                ps.setString(1, executionId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    @Override
    public void audit(String action, Map<String, Object> detail) {
        update("INSERT INTO audit_log (actor, action, detail) VALUES ('coordinator', ?, ?::jsonb)",
                action, detail == null ? null : toJson(detail));
    }

    @Override
    public List<SavepointInfo> listSavepoints() {
        return withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT detail::text FROM audit_log WHERE action = 'savepoint.created' ORDER BY at DESC, id DESC");
                 ResultSet rs = ps.executeQuery()) {
                List<SavepointInfo> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(fromJson(rs.getString(1), SavepointInfo.class));
                }
                return List.copyOf(out);
            }
        });
    }

    private static String toJson(Object value) {
        try {
            return Json.MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("cannot serialise " + value, e);
        }
    }

    private static <T> T fromJson(String json, Class<T> type) {
        try {
            return Json.MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot parse JSON column", e);
        }
    }

    private static <T> T fromJson(String json, TypeReference<T> type) {
        try {
            return Json.MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot parse JSON column", e);
        }
    }
}
