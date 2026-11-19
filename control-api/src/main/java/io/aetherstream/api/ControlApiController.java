package io.aetherstream.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aetherstream.api.org.OrgAccess;
import io.aetherstream.api.org.OrgContext;
import io.aetherstream.api.org.OrgException;
import io.aetherstream.api.recovery.RecoveryHistory;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
class ControlApiController {
    private final CoordinatorClient coordinator;
    private final SimulatorClient simulator;
    private final ResultRepository results;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final RecoveryHistory recoveryHistory;
    private final String kafkaBootstrap, gitSha, builtAt;

    ControlApiController(CoordinatorClient coordinator, SimulatorClient simulator, ResultRepository results,
                         JdbcTemplate jdbc, ObjectMapper mapper, RecoveryHistory recoveryHistory,
                         @Value("${aetherstream.kafka-bootstrap}") String kafkaBootstrap,
                         @Value("${aetherstream.git-sha}") String gitSha,
                         @Value("${aetherstream.built-at}") String builtAt) {
        this.coordinator = coordinator; this.simulator = simulator; this.results = results; this.jdbc = jdbc;
        this.mapper = mapper; this.kafkaBootstrap = kafkaBootstrap; this.gitSha = gitSha; this.builtAt = builtAt;
        this.recoveryHistory = recoveryHistory;
    }

    @GetMapping("/health") Map<String,String> health() { return Map.of("status", "UP"); }
    @GetMapping("/version") Map<String,String> version() { return Map.of("commit", gitSha, "builtAt", builtAt); }

    @GetMapping("/ready") ResponseEntity<Map<String,Object>> ready() {
        Map<String,String> checks = new java.util.LinkedHashMap<>();
        checks.put("postgres", check(() -> jdbc.queryForObject("SELECT 1", Integer.class)));
        checks.put("kafka", check(() -> {
            try (AdminClient admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrap))) {
                admin.describeCluster().nodes().get(2, TimeUnit.SECONDS);
            }
        }));
        checks.put("coordinator", check(coordinator::health));
        checks.put("simulator", check(simulator::health));
        String status = checks.values().stream().allMatch("UP"::equals) ? "UP" : "DOWN";
        return ResponseEntity.status("UP".equals(status) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("status", status, "checks", checks));
    }

    @GetMapping("/workers") JsonNode workers() { return coordinator.state().path("workers"); }
    @GetMapping("/recovery/history") List<Map<String, Object>> recoveryHistory() {
        return recoveryHistory.history(OrgContext.current());
    }
    @GetMapping("/executions/current") ResponseEntity<Map<String,Object>> currentExecution() {
        String id = results.currentExecutionId();
        return id == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(execution(id));
    }
    @GetMapping("/executions/{id}") Map<String,Object> getExecution(@PathVariable String id) { return execution(id); }
    @PostMapping("/executions/{id}/stop") Map<String,Object> stopExecution(@PathVariable String id) {
        OrgAccess.requireExecution(jdbc, id);
        coordinator.stop(id);
        return execution(id);
    }
    @GetMapping("/executions/{id}/checkpoints") List<Map<String,Object>> checkpoints(
            @PathVariable String id, @RequestParam(defaultValue = "20") int limit) {
        OrgAccess.requireExecution(jdbc, id);
        return results.checkpoints(id, bounded(limit, 20, 500));
    }

    @GetMapping("/results/windows") List<Map<String,Object>> windows(@RequestParam(required = false) String sinkId,
            @RequestParam(defaultValue = "200") int limit) {
        return forCurrent(id -> results.windows(id, sinkId, bounded(limit, 200, 1000)));
    }
    @GetMapping("/results/alerts") List<Map<String,Object>> alerts(@RequestParam(defaultValue = "50") int limit) {
        return forCurrent(id -> results.alerts(id, bounded(limit, 50, 500)));
    }
    @GetMapping("/results/dead-letters") List<Map<String,Object>> deadLetters(
            @RequestParam(required = false) String kind, @RequestParam(defaultValue = "50") int limit) {
        return forCurrent(id -> results.deadLetters(id, kind, bounded(limit, 50, 500)));
    }
    @GetMapping("/results/summary") Map<String,Object> summary() {
        String id = results.currentExecutionId();
        double rate = 0;
        try {
            JsonNode cluster = coordinator.state();
            if (id != null && id.equals(cluster.path("execution").path("id").asText()))
                for (JsonNode worker : cluster.path("workers"))
                    if (worker.path("alive").asBoolean()) rate += worker.path("recordsPerSec").asDouble();
        } catch (RuntimeException ignored) { }
        return results.summary(id, rate);
    }

    @PostMapping("/chaos/late-events") @Transactional
    public JsonNode late(@RequestBody JsonNode body) {
        requireChaosExecution(); return simulator.post("/chaos/late", body);
    }
    @PostMapping("/chaos/duplicates") @Transactional
    public JsonNode duplicates(@RequestBody JsonNode body) {
        requireChaosExecution(); return simulator.post("/chaos/duplicates", body);
    }
    @PostMapping("/chaos/malformed") @Transactional
    public JsonNode malformed(@RequestBody JsonNode body) {
        requireChaosExecution(); return simulator.post("/chaos/malformed", body);
    }
    @PostMapping("/chaos/slow-sink") @Transactional
    public JsonNode slowSink(@RequestBody JsonNode body) {
        requireChaosExecution(); return coordinator.chaos("/coord/chaos/sink-delay", body);
    }
    @PostMapping("/chaos/kill-worker") @Transactional
    public JsonNode killWorker(@RequestBody JsonNode body, HttpServletRequest request) {
        String executionId = requireChaosExecution();
        JsonNode response = coordinator.chaos("/coord/chaos/kill", body);
        String actor = request.getUserPrincipal() == null ? "api:" + OrgContext.current()
                : request.getUserPrincipal().getName();
        recoveryHistory.killRequested(OrgContext.current(), executionId, response.path("workerId").asText(),
                actor, System.currentTimeMillis());
        return response;
    }
    @GetMapping("/simulator/state") JsonNode simulatorState() { return simulator.state(); }
    @PostMapping("/simulator/trucks") JsonNode addTruck(@RequestBody JsonNode body) { return simulator.post("/trucks", body); }

    private String requireChaosExecution() {
        // Chaos targets shared services; serialize it with starts/restores and verify their active target.
        jdbc.execute("SELECT pg_advisory_xact_lock(1095062612)");
        String id = results.currentExecutionId();
        if (id == null) throw new OrgException(HttpStatus.NOT_FOUND, "NOT_FOUND", "execution not found");
        OrgAccess.requireExecution(jdbc, id);
        JsonNode current = coordinator.state().path("execution");
        if (!id.equals(current.path("id").asText())
                || !List.of("RUNNING", "RECOVERING").contains(current.path("state").asText()))
            throw new OrgException(HttpStatus.NOT_FOUND, "NOT_FOUND", "execution not found");
        return id;
    }

    private Map<String,Object> execution(String id) {
        List<Map<String,Object>> rows = jdbc.query("SELECT id,pipeline_id,pipeline_version,state,generation,restored_from_checkpoint,created_at FROM executions WHERE id=? AND org_id=?",
                (rs, row) -> {
                    Map<String,Object> value = new java.util.LinkedHashMap<>();
                    value.put("id", rs.getString("id")); value.put("pipelineId", rs.getString("pipeline_id"));
                    value.put("pipelineVersion", rs.getInt("pipeline_version")); value.put("state", rs.getString("state"));
                    value.put("generation", rs.getLong("generation"));
                    Object restored = rs.getObject("restored_from_checkpoint"); value.put("restoredFromCheckpoint", restored == null ? null : ((Number) restored).longValue());
                    value.put("lastCompletedCheckpoint", lastCompleted(id));
                    value.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                    return value;
                }, id, OrgContext.current());
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "execution not found");
        return rows.get(0);
    }
    private Long lastCompleted(String id) {
        List<Long> rows = jdbc.query("SELECT max(checkpoint_id) FROM checkpoints WHERE execution_id=? AND state='COMPLETED'",
                (rs, row) -> { long n = rs.getLong(1); return rs.wasNull() ? null : n; }, id);
        return rows.isEmpty() ? null : rows.get(0);
    }
    private List<Map<String,Object>> forCurrent(java.util.function.Function<String,List<Map<String,Object>>> query) {
        String id = results.currentExecutionId(); return id == null ? List.of() : query.apply(id);
    }
    @FunctionalInterface interface Check { void run() throws Exception; }

    private String check(Check check) {
        try { check.run(); return "UP"; } catch (Exception e) { return "DOWN"; }
    }
    private static int bounded(int value, int fallback, int max) { return Math.max(1, Math.min(value, max)); }
}

class ApiException extends RuntimeException {
    final HttpStatus status; final String code;
    ApiException(HttpStatus status, String code, String message) { super(message); this.status = status; this.code = code; }
}

@RestControllerAdvice
class ApiErrorHandler {
    @ExceptionHandler(OrgException.class)
    ResponseEntity<Map<String,String>> org(OrgException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.code(), "message", e.getMessage()));
    }
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Map<String,String>> notFound(NoResourceFoundException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", "NOT_FOUND", "message", "endpoint not found"));
    }
    @ExceptionHandler(UpstreamException.class)
    ResponseEntity<Map<String,String>> upstream(UpstreamException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "UPSTREAM_DOWN", "message", "coordinator is not reachable"));
    }
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String,String>> api(ApiException e) {
        return ResponseEntity.status(e.status).body(Map.of("error", e.code, "message", e.getMessage()));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String,String>> unexpected(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "INTERNAL_ERROR", "message", "request failed"));
    }
}
