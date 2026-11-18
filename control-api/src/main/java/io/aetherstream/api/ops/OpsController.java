package io.aetherstream.api.ops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.aetherstream.api.org.OrgAccess;
import io.aetherstream.api.org.OrgContext;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class OpsController {
    private final OpsCoordinatorClient coordinator;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public OpsController(OpsCoordinatorClient coordinator, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.coordinator = coordinator;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @PostMapping("/executions/{id}/savepoints")
    public JsonNode savepoint(@PathVariable String id) {
        OrgAccess.requireExecution(jdbc, id);
        JsonNode saved = coordinator.savepoint(id);
        audit("savepoint.requested", Map.of("executionId", id, "savepoint", saved));
        return saved;
    }

    @GetMapping("/savepoints")
    public JsonNode savepoints() {
        JsonNode all = coordinator.savepoints();
        ArrayNode visible = mapper.createArrayNode();
        if (all == null || !all.isArray()) return visible;
        var owned = new HashSet<>(jdbc.query("SELECT id FROM executions WHERE org_id=?",
                (rs, row) -> rs.getString("id"), OrgContext.current()));
        for (JsonNode saved : all) {
            if (saved.path("executionId").isTextual() && owned.contains(saved.path("executionId").textValue())) {
                visible.add(saved);
            }
        }
        return visible;
    }

    /** Restores with the newest persisted execution plan belonging to the source pipeline. */
    @PostMapping("/executions/{id}/restore")
    @Transactional
    public JsonNode restore(@PathVariable String id, @RequestBody RestoreRequest request) {
        if (request.savepointId() == null || request.savepointId().isBlank()
                || request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw new RequestException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                    "savepointId and idempotencyKey must not be empty");
        }
        OrgAccess.requireExecution(jdbc, id);
        // Share the start service's lock without locking rows the coordinator needs to update.
        jdbc.execute("SELECT pg_advisory_xact_lock(1095062612)");
        String org = OrgContext.current();
        requireSavepoint(id, request.savepointId());
        List<JsonNode> plans = jdbc.query("""
                SELECT latest.plan::text FROM executions source
                JOIN executions latest ON latest.pipeline_id=source.pipeline_id AND latest.org_id=source.org_id
                WHERE source.id=? AND source.org_id=?
                ORDER BY latest.pipeline_version DESC,latest.created_at DESC,latest.id DESC LIMIT 1
                """, (rs, row) -> storedPlan(rs.getString(1)), id, org);
        if (plans.isEmpty()) {
            throw new RequestException(HttpStatus.NOT_FOUND, "NOT_FOUND", "execution not found");
        }
        String key = OrgContext.idempotencyKey(request.idempotencyKey());
        List<String> existing = jdbc.query("SELECT id FROM executions WHERE idempotency_key=? AND org_id=?",
                (rs, row) -> rs.getString("id"), key, org);
        if (!existing.isEmpty()) return coordinator.execution(existing.get(0));
        OrgAccess.requireAvailableCluster(jdbc);

        coordinator.stop(id);
        JsonNode restored = coordinator.restore("exec-" + UUID.randomUUID(), plans.get(0), request.savepointId());
        int updated = jdbc.update("UPDATE executions SET idempotency_key=?,org_id=?,updated_at=now() WHERE id=?",
                key, org, restored.path("id").asText());
        if (updated != 1) throw new IllegalStateException("coordinator did not persist the execution");
        audit("execution.restored", Map.of("executionId", id, "savepointId", request.savepointId(),
                "execution", restored));
        return restored;
    }

    /**
     * Requests a controlled pause at a checkpoint followed by partition reassignment.
     * This is not online migration.
     */
    @PostMapping("/executions/{id}/rescale")
    public JsonNode rescale(@PathVariable String id) {
        OrgAccess.requireExecution(jdbc, id);
        JsonNode response = coordinator.rebalance(id);
        audit("execution.rescaled", Map.of("executionId", id, "result", response));
        return response;
    }

    @ExceptionHandler(OpsCoordinatorClient.ResponseException.class)
    public ResponseEntity<String> coordinatorError(OpsCoordinatorClient.ResponseException e) {
        return ResponseEntity.status(e.status()).contentType(MediaType.APPLICATION_JSON).body(e.body());
    }

    @ExceptionHandler(OpsCoordinatorClient.UpstreamDownException.class)
    public ResponseEntity<Map<String, String>> upstreamDown(OpsCoordinatorClient.UpstreamDownException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "UPSTREAM_DOWN", "message", e.getMessage()));
    }

    @ExceptionHandler(RequestException.class)
    public ResponseEntity<Map<String, String>> invalidRequest(RequestException e) {
        return ResponseEntity.status(e.status).body(Map.of("error", e.code, "message", e.getMessage()));
    }

    private void requireSavepoint(String executionId, String savepointId) {
        JsonNode all = coordinator.savepoints();
        if (all != null && all.isArray()) {
            for (JsonNode saved : all) {
                if (saved.path("savepointId").isTextual() && saved.path("executionId").isTextual()
                        && savepointId.equals(saved.path("savepointId").textValue())
                        && executionId.equals(saved.path("executionId").textValue())) return;
            }
        }
        throw new RequestException(HttpStatus.NOT_FOUND, "NOT_FOUND", "savepoint not found");
    }

    private JsonNode storedPlan(String json) {
        try { return mapper.readTree(json); }
        catch (JsonProcessingException e) { throw new IllegalStateException("stored execution plan is not valid JSON", e); }
    }

    private void audit(String action, Object detail) {
        jdbc.update("INSERT INTO audit_log(actor,action,detail) VALUES ('ui',?,?::jsonb)",
                action, OrgContext.detail(detail).toString());
    }

    public record RestoreRequest(String savepointId, String idempotencyKey) {}

    static class RequestException extends RuntimeException {
        final HttpStatus status;
        final String code;

        RequestException(HttpStatus status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }
}
