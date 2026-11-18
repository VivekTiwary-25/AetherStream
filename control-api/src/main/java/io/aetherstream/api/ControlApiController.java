package io.aetherstream.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
class ControlApiController {
    private final CoordinatorClient coordinator;
    private final SimulatorClient simulator;
    private final ResultRepository results;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final String kafkaBootstrap, gitSha, builtAt;

    ControlApiController(CoordinatorClient coordinator, SimulatorClient simulator, ResultRepository results,
                         JdbcTemplate jdbc, ObjectMapper mapper,
                         @Value("${aetherstream.kafka-bootstrap}") String kafkaBootstrap,
                         @Value("${aetherstream.git-sha}") String gitSha,
                         @Value("${aetherstream.built-at}") String builtAt) {
        this.coordinator = coordinator; this.simulator = simulator; this.results = results; this.jdbc = jdbc;
        this.mapper = mapper; this.kafkaBootstrap = kafkaBootstrap; this.gitSha = gitSha; this.builtAt = builtAt;
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
    @GetMapping("/executions/current") ResponseEntity<Map<String,Object>> currentExecution() {
        String id = results.currentExecutionId();
        return id == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(execution(id));
    }
    @GetMapping("/executions/{id}") Map<String,Object> getExecution(@PathVariable String id) { return execution(id); }
    @PostMapping("/executions/{id}/stop") Map<String,Object> stopExecution(@PathVariable String id) {
        coordinator.stop(id);
        return execution(id);
    }
    @GetMapping("/executions/{id}/checkpoints") List<Map<String,Object>> checkpoints(
            @PathVariable String id, @RequestParam(defaultValue = "20") int limit) {
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
            JsonNode workers = coordinator.state().path("workers");
            for (JsonNode worker : workers) if (worker.path("alive").asBoolean()) rate += worker.path("recordsPerSec").asDouble();
        } catch (RuntimeException ignored) { }
        return results.summary(id, rate);
    }

    @PostMapping("/chaos/late-events") JsonNode late(@RequestBody JsonNode body) { return simulator.post("/chaos/late", body); }
    @PostMapping("/chaos/duplicates") JsonNode duplicates(@RequestBody JsonNode body) { return simulator.post("/chaos/duplicates", body); }
    @PostMapping("/chaos/malformed") JsonNode malformed(@RequestBody JsonNode body) { return simulator.post("/chaos/malformed", body); }
    @PostMapping("/chaos/slow-sink") JsonNode slowSink(@RequestBody JsonNode body) { return coordinator.chaos("/coord/chaos/sink-delay", body); }
    @PostMapping("/chaos/kill-worker") JsonNode killWorker(@RequestBody JsonNode body) { return coordinator.chaos("/coord/chaos/kill", body); }
    @GetMapping("/simulator/state") JsonNode simulatorState() { return simulator.state(); }
    @PostMapping("/simulator/trucks") JsonNode addTruck(@RequestBody JsonNode body) { return simulator.post("/trucks", body); }

    private Map<String,Object> execution(String id) {
        List<Map<String,Object>> rows = jdbc.query("SELECT id,pipeline_id,pipeline_version,state,generation,restored_from_checkpoint,created_at FROM executions WHERE id=?",
                (rs, row) -> {
                    Map<String,Object> value = new java.util.LinkedHashMap<>();
                    value.put("id", rs.getString("id")); value.put("pipelineId", rs.getString("pipeline_id"));
                    value.put("pipelineVersion", rs.getInt("pipeline_version")); value.put("state", rs.getString("state"));
                    value.put("generation", rs.getLong("generation"));
                    Object restored = rs.getObject("restored_from_checkpoint"); value.put("restoredFromCheckpoint", restored == null ? null : ((Number) restored).longValue());
                    value.put("lastCompletedCheckpoint", lastCompleted(id));
                    value.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                    return value;
                }, id);
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
    private String check(Runnable check) {
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
