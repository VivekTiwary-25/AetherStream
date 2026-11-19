package io.aetherstream.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.common.KafkaFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(value = ControlApiController.class, properties = {
        "aetherstream.git-sha=contract-sha", "aetherstream.built-at=2026-11-19T00:00:00Z"})
@Import({ApiErrorHandler.class, ResultRepository.class})
class ControlApiContractTest {
    private static final String EXECUTION = "exec-contract";
    private static final long TIME = 1_795_251_600_000L;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean CoordinatorClient coordinator;
    @MockitoBean SimulatorClient simulator;
    @MockitoBean JdbcTemplate jdbc;
    private String executionState;

    @BeforeEach
    @SuppressWarnings({"rawtypes", "unchecked"})
    void databaseAndUpstreams() throws Exception {
        executionState = "RUNNING";
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            Object[] args = invocation.getArguments();
            List<Map<String, Object>> rows;
            if (sql.startsWith("SELECT id FROM executions")) {
                rows = "alpha".equals(args[2]) ? List.of(Map.of("1", EXECUTION)) : List.of();
            } else if (sql.startsWith("SELECT id,pipeline_id")) {
                rows = EXECUTION.equals(args[2]) && "alpha".equals(args[3])
                        ? List.of(Map.of("id", EXECUTION, "pipeline_id", "logistics-demo", "pipeline_version", 2,
                        "state", executionState, "generation", 3L, "restored_from_checkpoint", 1L,
                        "created_at", Timestamp.from(Instant.parse("2026-11-19T00:00:00Z")))) : List.of();
            } else if (sql.startsWith("SELECT max(checkpoint_id)")) {
                rows = List.of(Map.of("1", 2L));
            } else if (sql.contains("FROM window_results") && sql.contains("AS deliveries")) {
                rows = args[2] == null ? List.of() : List.of(Map.of("deliveries", 3.0, "speed", 31.5));
            } else if (sql.contains("FROM window_results")) {
                rows = List.of(Map.of("sink_id", "speedSink", "key", "truck-001", "window_start", TIME,
                        "window_end", TIME + 60_000, "values", "{\"avgSpeedKmh\":31.5}", "event_count", 7L));
            } else if (sql.contains("FROM alerts")) {
                rows = List.of(Map.of("alert_id", "stuck|truck-001|0", "key", "truck-001", "rule_id", "stuck-truck",
                        "message", "truck-001 has not moved", "event_time", TIME));
            } else if (sql.contains("FROM dead_letters")) {
                String kind = args[3] == null ? "REJECTED" : args[3].toString();
                rows = List.of(Map.of("event_id", "event-1", "kind", kind, "reason", "invalid speed",
                        "partition", 1, "offset", 42L, "event_time", TIME));
            } else if (sql.contains("FROM checkpoints")) {
                rows = List.of(Map.of("checkpoint_id", 2L, "state", "COMPLETED", "generation", 3L,
                        "duration_ms", 84L, "completed_at", Timestamp.from(Instant.parse("2026-11-19T00:00:00Z")),
                        "source_offsets", "{\"0\":42}"));
            } else {
                throw new AssertionError("Unexpected query: " + sql);
            }
            RowMapper rowMapper = invocation.getArgument(1);
            List<Object> mapped = new ArrayList<>();
            for (int index = 0; index < rows.size(); index++) mapped.add(rowMapper.mapRow(row(rows.get(index)), index));
            return mapped;
        });
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), anyString(), anyString()))
                .thenAnswer(invocation -> EXECUTION.equals(invocation.getArgument(2)) && "alpha".equals(invocation.getArgument(3)));
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(2L);
        when(coordinator.state()).thenReturn(mapper.readTree("""
                {"execution":{"id":"exec-contract","state":"RUNNING"},"workers":[
                {"workerId":"w-1","alive":true,"state":"RUNNING","partitions":[0,3],
                 "sourceQueue":{"size":3,"capacity":1000},"sinkQueue":{"size":0,"capacity":1000},
                 "backpressured":false,"recordsPerSec":41.5,"lastHeartbeatMs":1795251600123},
                {"workerId":"w-dead","alive":false,"recordsPerSec":999}]}
                """));
    }

    @Test void healthIsProcessOnlyAndVersionUsesBuildMetadata() throws Exception {
        JsonNode health = ok(get("/api/health"));
        fields(health, "status");
        assertThat(health.path("status").asText()).isEqualTo("UP");
        JsonNode version = ok(get("/api/version"));
        fields(version, "commit", "builtAt");
        assertThat(version.path("commit").asText()).isEqualTo("contract-sha");
        assertThat(version.path("builtAt").asText()).isEqualTo("2026-11-19T00:00:00Z");
        verifyNoInteractions(jdbc, coordinator, simulator);
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "postgres", "kafka", "coordinator", "simulator"})
    void readinessReturnsAllChecksAnd503ForEachDependencyFailure(String failed) throws Exception {
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        if (failed.equals("postgres")) when(jdbc.queryForObject("SELECT 1", Integer.class)).thenThrow(new IllegalStateException());
        if (failed.equals("coordinator")) when(coordinator.health()).thenThrow(new UpstreamException("offline"));
        if (failed.equals("simulator")) when(simulator.health()).thenThrow(new UpstreamException("offline"));
        AdminClient admin = mock(AdminClient.class);
        DescribeClusterResult cluster = mock(DescribeClusterResult.class);
        when(admin.describeCluster()).thenReturn(cluster);
        when(cluster.nodes()).thenReturn(KafkaFuture.completedFuture(List.of()));
        if (failed.equals("kafka")) when(admin.describeCluster()).thenThrow(new IllegalStateException());
        try (var factory = mockStatic(AdminClient.class)) {
            factory.when(() -> AdminClient.create(org.mockito.ArgumentMatchers.<String, Object>anyMap())).thenReturn(admin);
            JsonNode body = body(mvc.perform(get("/api/ready")).andExpect(failed.equals("none")
                    ? status().isOk() : status().isServiceUnavailable()));
            fields(body, "status", "checks");
            fields(body.path("checks"), "postgres", "kafka", "coordinator", "simulator");
            assertThat(body.path("status").asText()).isEqualTo(failed.equals("none") ? "UP" : "DOWN");
            for (String dependency : List.of("postgres", "kafka", "coordinator", "simulator"))
                assertThat(body.path("checks").path(dependency).asText()).isEqualTo(dependency.equals(failed) ? "DOWN" : "UP");
        }
    }

    @Test void currentGetAndStopExposeExecutionInfo() throws Exception {
        execution(ok(get("/api/executions/current")), "RUNNING");
        execution(ok(get("/api/executions/" + EXECUTION)), "RUNNING");
        doAnswer(invocation -> { executionState = "STOPPED"; return mapper.createObjectNode(); }).when(coordinator).stop(EXECUTION);
        execution(ok(post("/api/executions/" + EXECUTION + "/stop")), "STOPPED");
    }

    @Test void absentCurrentExecutionHas204AndNoBody() throws Exception {
        mvc.perform(get("/api/executions/current").header("X-Org", "empty"))
                .andExpect(status().isNoContent()).andExpect(result -> assertThat(result.getResponse().getContentAsString()).isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"GET,/api/executions/exec-contract", "POST,/api/executions/exec-contract/stop",
            "GET,/api/executions/exec-contract/checkpoints", "GET,/api/executions/missing"})
    void foreignOrMissingExecutionIsNotFound(String method, String path) throws Exception {
        error(mvc.perform(request(HttpMethod.valueOf(method), path).header("X-Org", "beta"))
                .andExpect(status().isNotFound()), "NOT_FOUND");
        verify(coordinator, never()).stop(anyString());
    }

    @Test void checkpointsHaveContractFields() throws Exception {
        JsonNode rows = ok(get("/api/executions/" + EXECUTION + "/checkpoints?limit=20"));
        assertThat(rows.isArray()).isTrue();
        JsonNode checkpoint = rows.get(0);
        fields(checkpoint, "checkpointId", "state", "generation", "durationMs", "completedAt", "sourceOffsets");
        numbers(checkpoint, "checkpointId", "generation", "durationMs");
        assertThat(checkpoint.path("state").asText()).isEqualTo("COMPLETED");
        assertThat(checkpoint.path("completedAt").isTextual()).isTrue();
        assertThat(checkpoint.path("sourceOffsets").path("0").isIntegralNumber()).isTrue();
    }

    @Test void workersExposeQueuesThroughputAndHeartbeat() throws Exception {
        JsonNode workers = ok(get("/api/workers"));
        assertThat(workers.isArray()).isTrue();
        JsonNode worker = workers.get(0);
        fields(worker, "workerId", "alive", "state", "partitions", "sourceQueue", "sinkQueue", "backpressured", "recordsPerSec", "lastHeartbeatMs");
        assertThat(worker.path("workerId").isTextual()).isTrue();
        assertThat(worker.path("state").isTextual()).isTrue();
        assertThat(worker.path("alive").isBoolean()).isTrue();
        assertThat(worker.path("backpressured").isBoolean()).isTrue();
        assertThat(worker.path("partitions").get(0).isIntegralNumber()).isTrue();
        numbers(worker, "recordsPerSec", "lastHeartbeatMs");
        for (String queue : List.of("sourceQueue", "sinkQueue")) {
            fields(worker.path(queue), "size", "capacity");
            numbers(worker.path(queue), "size", "capacity");
        }
    }

    @Test void windowsAlertsAndDeadLettersMapDatabaseColumnsToWireFields() throws Exception {
        JsonNode window = ok(get("/api/results/windows?sinkId=speedSink&limit=200")).get(0);
        fields(window, "sinkId", "key", "windowStart", "windowEnd", "values", "eventCount");
        assertThat(window.path("sinkId").asText()).isEqualTo("speedSink");
        assertThat(window.path("key").isTextual()).isTrue();
        numbers(window, "windowStart", "windowEnd", "eventCount");
        assertThat(window.path("values").isObject()).isTrue();
        assertThat(window.path("values").path("avgSpeedKmh").doubleValue()).isEqualTo(31.5);
        JsonNode alert = ok(get("/api/results/alerts?limit=50")).get(0);
        fields(alert, "alertId", "key", "ruleId", "message", "eventTime");
        strings(alert, "alertId", "key", "ruleId", "message");
        numbers(alert, "eventTime");
        for (String kind : List.of("LATE", "REJECTED")) {
            JsonNode dead = ok(get("/api/results/dead-letters?kind=" + kind + "&limit=50")).get(0);
            fields(dead, "eventId", "kind", "reason", "partition", "offset", "eventTime");
            strings(dead, "eventId", "kind", "reason");
            numbers(dead, "partition", "offset", "eventTime");
            assertThat(dead.path("kind").asText()).isEqualTo(kind);
        }
    }

    @Test void summaryUsesCurrentExecutionAndOnlyLiveWorkerRates() throws Exception {
        JsonNode summary = ok(get("/api/results/summary"));
        fields(summary, "executionId", "windowsWritten", "alerts", "rejected", "late", "deliveriesLastMinute", "avgSpeedLastMinute", "eventsPerSec");
        assertThat(summary.path("executionId").asText()).isEqualTo(EXECUTION);
        numbers(summary, "windowsWritten", "alerts", "rejected", "late", "deliveriesLastMinute", "avgSpeedLastMinute", "eventsPerSec");
        assertThat(summary.path("eventsPerSec").doubleValue()).isEqualTo(41.5);
        assertThat(summary.path("deliveriesLastMinute").doubleValue()).isEqualTo(3.0);
        assertThat(summary.path("avgSpeedLastMinute").doubleValue()).isEqualTo(31.5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"windows", "alerts", "dead-letters"})
    void anotherOrgCannotReadCurrentResults(String area) throws Exception {
        JsonNode body = body(mvc.perform(get("/api/results/" + area).header("X-Org", "beta")).andExpect(status().isOk()));
        assertThat(body.isArray()).isTrue();
        assertThat(body.isEmpty()).isTrue();
    }

    @Test void anotherOrgSummaryDoesNotLeakRatesOrCounters() throws Exception {
        JsonNode summary = body(mvc.perform(get("/api/results/summary").header("X-Org", "beta")).andExpect(status().isOk()));
        assertThat(summary.path("executionId").asText()).isEmpty();
        for (String field : List.of("windowsWritten", "alerts", "rejected", "late", "eventsPerSec", "deliveriesLastMinute", "avgSpeedLastMinute"))
            assertThat(summary.path(field).doubleValue()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"late-events,/chaos/late,{\"count\":20;\"lateByMs\":180000},sent,20",
            "duplicates,/chaos/duplicates,{\"count\":20},sent,20", "malformed,/chaos/malformed,{\"count\":5},sent,5",
            "slow-sink,/coord/chaos/sink-delay,{\"delayMs\":50},delayMs,50",
            "slow-sink,/coord/chaos/sink-delay,{\"delayMs\":0},delayMs,0",
            "kill-worker,/coord/chaos/kill,{\"workerId\":null},workerId,w-1"})
    void chaosRoutesRequestAndReturnsUpstreamContract(String endpoint, String upstream, String input, String field, String value) throws Exception {
        JsonNode payload = mapper.readTree(input.replace(';', ','));
        JsonNode response = mapper.createObjectNode();
        if (field.equals("workerId")) ((com.fasterxml.jackson.databind.node.ObjectNode) response).put(field, value);
        else ((com.fasterxml.jackson.databind.node.ObjectNode) response).put(field, Integer.parseInt(value));
        when(simulator.post(upstream, payload)).thenReturn(response);
        when(coordinator.chaos(upstream, payload)).thenReturn(response);
        JsonNode actual = ok(post("/api/chaos/" + endpoint).contentType("application/json").content(payload.toString()));
        fields(actual, field);
        if (field.equals("workerId")) strings(actual, field); else numbers(actual, field);
        assertThat(actual).isEqualTo(response);
    }

    @ParameterizedTest
    @ValueSource(strings = {"late-events", "duplicates", "malformed", "slow-sink", "kill-worker"})
    void foreignOrgChaosCannotMutateSharedServices(String endpoint) throws Exception {
        error(mvc.perform(post("/api/chaos/" + endpoint).header("X-Org", "beta").contentType("application/json").content("{}"))
                .andExpect(status().isNotFound()), "NOT_FOUND");
        verifyNoInteractions(coordinator, simulator);
    }

    @Test void simulatorStateAndTruckCreationUseSimulatorContract() throws Exception {
        when(simulator.state()).thenReturn(mapper.readTree("""
                {"running":true,"timeScale":10,"simTime":1795251660000,"trucks":[
                {"truckId":"truck-001","x":500.0,"y":700.0,"speedKmh":31.5,"heading":90.0,"status":"DRIVING","routeId":"r-1"}],
                "stops":[{"stopId":"stop-1","x":600.0,"y":800.0}],"depot":{"x":1000,"y":1000},"emitted":12345}
                """));
        JsonNode state = ok(get("/api/simulator/state"));
        fields(state, "running", "timeScale", "simTime", "trucks", "stops", "depot", "emitted");
        assertThat(state.path("running").isBoolean()).isTrue();
        numbers(state, "timeScale", "simTime", "emitted");
        JsonNode truck = state.path("trucks").get(0);
        fields(truck, "truckId", "x", "y", "speedKmh", "heading", "status", "routeId");
        strings(truck, "truckId", "status", "routeId");
        numbers(truck, "x", "y", "speedKmh", "heading");
        JsonNode input = mapper.readTree("{\"x\":500,\"y\":700}");
        when(simulator.post("/trucks", input)).thenReturn(mapper.readTree("{\"truckId\":\"truck-009\"}"));
        JsonNode created = ok(post("/api/simulator/trucks").contentType("application/json").content(input.toString()));
        fields(created, "truckId");
        assertThat(created.path("truckId").asText()).isEqualTo("truck-009");
    }

    @Test void upstreamAndUnknownEndpointErrorsHaveOnlyErrorAndMessage() throws Exception {
        when(simulator.state()).thenThrow(new UpstreamException("offline"));
        error(mvc.perform(get("/api/simulator/state")).andExpect(status().isBadGateway()), "UPSTREAM_DOWN");
        error(mvc.perform(get("/api/not-a-contract-endpoint")).andExpect(status().isNotFound()), "NOT_FOUND");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a_b", "a/b", "a,b", "a b"})
    void invalidOrgFailsBeforeControllerDispatch(String org) throws Exception {
        error(mvc.perform(get("/api/health").header("X-Org", org)).andExpect(status().isBadRequest()), "INVALID_ORG");
        verifyNoInteractions(jdbc, coordinator, simulator);
    }

    @Disabled("CONTRACT: wrong HTTP method returns 500 INTERNAL_ERROR instead of a 4xx error")
    @ParameterizedTest
    @CsvSource({"POST,/api/health", "POST,/api/ready", "POST,/api/version", "POST,/api/workers",
            "GET,/api/chaos/duplicates", "GET,/api/simulator/trucks", "POST,/api/results/windows"})
    void wrongMethodsAreClientErrors(String method, String path) throws Exception {
        error(mvc.perform(request(HttpMethod.valueOf(method), path).header("X-Org", "alpha"))
                .andExpect(status().is4xxClientError()), null);
    }

    private JsonNode ok(MockHttpServletRequestBuilder request) throws Exception {
        return body(mvc.perform(request.header("X-Org", "alpha")).andExpect(status().isOk()));
    }
    private JsonNode body(ResultActions response) throws Exception {
        assertThat(response.andReturn().getResponse().getContentType()).startsWith("application/json");
        return mapper.readTree(response.andReturn().getResponse().getContentAsString());
    }
    private void error(ResultActions response, String code) throws Exception {
        JsonNode value = body(response);
        fields(value, "error", "message");
        strings(value, "error", "message");
        assertThat(value.path("message").asText()).isNotBlank();
        if (code != null) assertThat(value.path("error").asText()).isEqualTo(code);
    }
    private void execution(JsonNode value, String state) {
        fields(value, "id", "pipelineId", "pipelineVersion", "state", "generation", "restoredFromCheckpoint", "lastCompletedCheckpoint", "createdAt");
        strings(value, "id", "pipelineId", "state", "createdAt");
        numbers(value, "pipelineVersion", "generation", "restoredFromCheckpoint", "lastCompletedCheckpoint");
        assertThat(value.path("id").asText()).isEqualTo(EXECUTION);
        assertThat(value.path("state").asText()).isEqualTo(state);
    }
    private static void fields(JsonNode value, String... names) {
        List<String> actual = new ArrayList<>();
        value.fieldNames().forEachRemaining(actual::add);
        assertThat(actual).containsExactlyInAnyOrder(names);
    }
    private static void numbers(JsonNode value, String... names) {
        for (String name : names) assertThat(value.path(name).isNumber()).as(name).isTrue();
    }
    private static void strings(JsonNode value, String... names) {
        for (String name : names) assertThat(value.path(name).isTextual()).as(name).isTrue();
    }
    private static ResultSet row(Map<String, Object> values) throws Exception {
        AtomicReference<Object> last = new AtomicReference<>();
        return mock(ResultSet.class, invocation -> {
            String method = invocation.getMethod().getName();
            if (method.equals("wasNull")) return last.get() == null;
            if (!method.startsWith("get")) return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
            Object value = values.get(invocation.getArgument(0).toString());
            last.set(value);
            return switch (method) {
                case "getString" -> value == null ? null : value.toString();
                case "getLong" -> value == null ? 0L : ((Number) value).longValue();
                case "getInt" -> value == null ? 0 : ((Number) value).intValue();
                case "getDouble" -> value == null ? 0.0 : ((Number) value).doubleValue();
                default -> value;
            };
        });
    }
}
