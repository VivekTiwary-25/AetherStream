package io.aetherstream.api;

import io.aetherstream.common.Json;
import io.aetherstream.common.api.ExecutionInfo;
import io.aetherstream.common.pipeline.ExecutionPlan;
import io.aetherstream.common.pipeline.PipelineDefinition;
import io.aetherstream.common.protocol.StartExecutionRequest;
import io.aetherstream.compiler.PipelineCompiler;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ExecutionService {
    private static final DateTimeFormatter EXECUTION_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);
    private final PipelineRepository pipelines;
    private final CoordinatorClient coordinator;
    private final JdbcTemplate jdbc;
    private final PipelineCompiler compiler = new PipelineCompiler();

    ExecutionService(PipelineRepository pipelines, CoordinatorClient coordinator, JdbcTemplate jdbc) {
        this.pipelines = pipelines; this.coordinator = coordinator; this.jdbc = jdbc;
    }

    @Transactional
    public ExecutionInfo start(String pipelineId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank())
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "idempotencyKey must not be empty");
        // Serialize API starts across instances, without locking rows the coordinator needs to update.
        jdbc.execute("SELECT pg_advisory_xact_lock(1095062612)");
        List<ExecutionInfo> existing = jdbc.query(
                "SELECT e.*, (SELECT max(checkpoint_id) FROM checkpoints WHERE execution_id=e.id AND state='COMPLETED') " +
                        "AS last_completed_checkpoint FROM executions e WHERE idempotency_key=?",
                (rs, row) -> {
                    Number restored = (Number) rs.getObject("restored_from_checkpoint");
                    Number completed = (Number) rs.getObject("last_completed_checkpoint");
                    return new ExecutionInfo(rs.getString("id"), rs.getString("pipeline_id"), rs.getInt("pipeline_version"),
                            rs.getString("state"), rs.getLong("generation"), restored == null ? null : restored.longValue(),
                            completed == null ? null : completed.longValue(), rs.getTimestamp("created_at").toInstant().toString());
                }, idempotencyKey);
        if (!existing.isEmpty()) return existing.get(0);

        PipelineDefinition pipeline = pipelines.latest(pipelineId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "pipeline not found"));
        List<String> running = jdbc.query("SELECT id FROM executions WHERE state IN ('RUNNING','RECOVERING') ORDER BY created_at",
                (rs, row) -> rs.getString("id"));
        for (String id : running) coordinator.stop(id);
        ExecutionPlan plan = compiler.plan(pipeline, 6);
        String executionId = "exec-" + EXECUTION_TIME.format(Instant.now()) + "-" + UUID.randomUUID().toString().substring(0, 4);
        ExecutionInfo execution = coordinator.startExecution(new StartExecutionRequest(executionId, plan));
        // The coordinator owns insertion; only attach the API's idempotency key after it has succeeded.
        int updated = jdbc.update("UPDATE executions SET idempotency_key=?,updated_at=now() WHERE id=?",
                idempotencyKey, execution.id());
        if (updated != 1) throw new IllegalStateException("coordinator did not persist the execution");
        jdbc.update("INSERT INTO audit_log(actor,action,detail) VALUES ('ui','execution.started',?::jsonb)",
                Json.MAPPER.valueToTree(execution).toString());
        return execution;
    }
}
