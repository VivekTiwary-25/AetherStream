package io.aetherstream.api.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.aetherstream.api.org.OrgContext;
import io.aetherstream.common.pipeline.PipelineDefinition;
import io.aetherstream.compiler.PipelineCompiler;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OpsController.class)
class OpsControllerTest {
    private static final String ID = "exec-old";
    private static final String SAVEPOINT = "sp-exec-old-7";
    private static final String KEY = "restore-key";
    private static final String RESTORE_BODY = "{\"savepointId\":\"" + SAVEPOINT + "\",\"idempotencyKey\":\"" + KEY + "\"}";
    private static final String CREATED_AT = "2026-11-19T02:00:00Z";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean OpsCoordinatorClient coordinator;
    @MockitoBean JdbcTemplate jdbc;
    private JsonNode plan;
    private ObjectNode saved;

    @BeforeEach void setup() throws Exception {
        PipelineDefinition demo = PipelineCompiler.demoPipeline();
        PipelineDefinition latest = new PipelineDefinition(demo.id(), 3, demo.name(), demo.nodes(), demo.edges());
        plan = mapper.valueToTree(new PipelineCompiler().plan(latest, 6));
        saved = mapper.createObjectNode().put("savepointId", SAVEPOINT).put("checkpointId", 7)
                .put("createdAt", CREATED_AT);
        ResultSet row = mock(ResultSet.class);
        when(row.getString(1)).thenReturn(plan.toString());
        when(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM executions WHERE id=? AND org_id=?)",
                Boolean.class, ID, "demo")).thenReturn(true);
        when(jdbc.query(eq("SELECT id FROM executions WHERE org_id=?"), ArgumentMatchers.<RowMapper<String>>any(), eq("demo")))
                .thenReturn(List.of(ID));
        when(coordinator.savepoints()).thenReturn(mapper.createArrayNode().add(
                saved.deepCopy().put("executionId", ID)));
        when(jdbc.query(contains("SELECT latest.plan"), ArgumentMatchers.<RowMapper<JsonNode>>any(), eq(ID), eq("demo")))
                .thenAnswer(invocation -> List.of(invocation.<RowMapper<JsonNode>>getArgument(1).mapRow(row, 0)));
        when(jdbc.update(startsWith("UPDATE executions SET idempotency_key="), anyString(), anyString(), anyString()))
                .thenReturn(1);
        when(coordinator.restore(anyString(), any(), anyString()))
                .thenAnswer(invocation -> execution(invocation.getArgument(0)));
    }

    @Test void savepointReturnsDurableIdentityAndAuditsTheUiRequest() throws Exception {
        when(coordinator.savepoint(ID)).thenReturn(saved);
        mvc.perform(post("/api/executions/" + ID + "/savepoints"))
                .andExpect(status().isOk()).andExpect(content().json(saved.toString()));
        verify(coordinator).savepoint(ID);
        JsonNode detail = auditDetail("savepoint.requested");
        assertThat(detail.path("executionId").asText()).isEqualTo(ID);
        assertThat(detail.path("savepoint")).isEqualTo(saved);
        assertThat(detail.path("orgId").asText()).isEqualTo("demo");
    }

    @Test void listsOnlySavepointsWhoseExecutionBelongsToCaller() throws Exception {
        JsonNode owned = saved.deepCopy().put("executionId", ID);
        JsonNode foreign = saved.deepCopy().put("savepointId", "sp-foreign").put("executionId", "exec-foreign");
        JsonNode malformed = mapper.createObjectNode().put("savepointId", "no-execution");
        when(coordinator.savepoints()).thenReturn(mapper.createArrayNode().add(owned).add(foreign).add(malformed));
        mvc.perform(get("/api/savepoints")).andExpect(status().isOk())
                .andExpect(content().json(mapper.createArrayNode().add(owned).toString()));
        verify(jdbc).query(eq("SELECT id FROM executions WHERE org_id=?"), ArgumentMatchers.<RowMapper<String>>any(), eq("demo"));
    }

    @Test void unexpectedSavepointResponseFailsClosed() throws Exception {
        when(coordinator.savepoints()).thenReturn(mapper.createObjectNode().put("unexpected", "private"));
        mvc.perform(get("/api/savepoints")).andExpect(status().isOk()).andExpect(content().json("[]"));
        verifyNoInteractions(jdbc);
    }

    @Test void savepointListDoesNotExposeDemoResourcesToAnotherOrg() throws Exception {
        mvc.perform(get("/api/savepoints").header("X-Org", "acme"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        verify(jdbc).query(eq("SELECT id FROM executions WHERE org_id=?"),
                ArgumentMatchers.<RowMapper<String>>any(), eq("acme"));
    }

    @Test void restoreStopsBeforeStartingFromThePersistedPipelinePlanAndAttachesTheKey() throws Exception {
        String response = mvc.perform(post("/api/executions/" + ID + "/restore")
                        .contentType("application/json").content(RESTORE_BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pipelineVersion").value(3))
                .andReturn().getResponse().getContentAsString();
        String newId = mapper.readTree(response).path("id").asText();
        assertThat(newId).isNotEqualTo(ID).startsWith("exec-");
        var order = inOrder(jdbc, coordinator);
        order.verify(jdbc).execute("SELECT pg_advisory_xact_lock(1095062612)");
        order.verify(coordinator).stop(ID);
        order.verify(coordinator).restore(newId, plan, SAVEPOINT);
        order.verify(jdbc).update(startsWith("UPDATE executions SET idempotency_key="),
                eq(OrgContext.idempotencyKey(KEY)), eq("demo"), eq(newId));
        verify(jdbc).query(contains("latest.org_id=source.org_id"), ArgumentMatchers.<RowMapper<JsonNode>>any(), eq(ID), eq("demo"));
        JsonNode detail = auditDetail("execution.restored");
        assertThat(detail.path("executionId").asText()).isEqualTo(ID);
        assertThat(detail.path("savepointId").asText()).isEqualTo(SAVEPOINT);
        assertThat(detail.path("execution")).isEqualTo(mapper.readTree(response));
        assertThat(detail.path("orgId").asText()).isEqualTo("demo");
    }

    @Test void replayingTheRestoreKeyDoesNotStopOrStartAgain() throws Exception {
        JsonNode existing = execution("exec-restored");
        when(jdbc.query(contains("WHERE idempotency_key=?"), ArgumentMatchers.<RowMapper<String>>any(),
                eq(OrgContext.idempotencyKey(KEY)), eq("demo"))).thenReturn(List.of("exec-restored"));
        when(coordinator.execution("exec-restored")).thenReturn(existing);
        mvc.perform(post("/api/executions/" + ID + "/restore").contentType("application/json").content(RESTORE_BODY))
                .andExpect(status().isOk()).andExpect(content().json(existing.toString()));
        verify(coordinator, never()).stop(anyString());
        verify(coordinator, never()).restore(anyString(), any(), anyString());
        verify(jdbc, never()).update(startsWith("UPDATE executions SET idempotency_key="), anyString(), anyString(), anyString());
    }

    @Test void rescaleReturnsTheCheckpointResponseAndAuditsIt() throws Exception {
        JsonNode response = mapper.createObjectNode().put("checkpointId", 8);
        when(coordinator.rebalance(ID)).thenReturn(response);
        mvc.perform(post("/api/executions/" + ID + "/rescale"))
                .andExpect(status().isOk()).andExpect(content().json(response.toString()));
        JsonNode detail = auditDetail("execution.rescaled");
        assertThat(detail.path("executionId").asText()).isEqualTo(ID);
        assertThat(detail.path("result")).isEqualTo(response);
        assertThat(detail.path("orgId").asText()).isEqualTo("demo");
    }

    @Test void incompatibleSavepointKeepsTheCoordinatorsExactErrorBody() throws Exception {
        String error = "{ \"error\": \"INCOMPATIBLE_SAVEPOINT\", \"message\": \"Window size changed; restore the original plan.\" }\n";
        when(coordinator.restore(anyString(), any(), eq(SAVEPOINT)))
                .thenThrow(new OpsCoordinatorClient.ResponseException(409, error));
        mvc.perform(post("/api/executions/" + ID + "/restore").contentType("application/json").content(RESTORE_BODY))
                .andExpect(status().isConflict()).andExpect(content().string(error));
        verify(jdbc, never()).update(startsWith("UPDATE executions SET idempotency_key="), anyString(), anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"savepoints", "list", "restore", "rescale"})
    void upstreamDownReturnsBadGatewayWithoutAuditing(String operation) throws Exception {
        var failure = new OpsCoordinatorClient.UpstreamDownException(new IOException("connection refused"));
        switch (operation) {
            case "savepoints" -> when(coordinator.savepoint(ID)).thenThrow(failure);
            case "list" -> when(coordinator.savepoints()).thenThrow(failure);
            case "restore" -> when(coordinator.restore(anyString(), any(), anyString())).thenThrow(failure);
            case "rescale" -> when(coordinator.rebalance(ID)).thenThrow(failure);
            default -> throw new AssertionError(operation);
        }
        var request = operation.equals("list") ? get("/api/savepoints")
                : post("/api/executions/" + ID + "/" + operation).contentType("application/json").content(RESTORE_BODY);
        mvc.perform(request).andExpect(status().isBadGateway()).andExpect(jsonPath("$.error").value("UPSTREAM_DOWN"));
        verify(jdbc, never()).update(startsWith("INSERT INTO audit_log"), anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"savepoints", "rescale"})
    void unknownUpstreamExecutionReturnsNotFound(String operation) throws Exception {
        var failure = new OpsCoordinatorClient.ResponseException(404, "{\"error\":\"NOT_FOUND\",\"message\":\"unknown execution\"}");
        if (operation.equals("savepoints")) when(coordinator.savepoint(ID)).thenThrow(failure);
        else when(coordinator.rebalance(ID)).thenThrow(failure);
        mvc.perform(post("/api/executions/" + ID + "/" + operation))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("NOT_FOUND"));
        verify(jdbc, never()).update(startsWith("INSERT INTO audit_log"), anyString(), anyString());
    }

    @Test void unknownRestoreExecutionDoesNotStopAnything() throws Exception {
        mvc.perform(post("/api/executions/missing/restore").contentType("application/json").content(RESTORE_BODY))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("NOT_FOUND"));
        verifyNoInteractions(coordinator);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"savepointId\":\"sp\"}", "{\"idempotencyKey\":\"key\"}",
            "{\"savepointId\":\" \",\"idempotencyKey\":\"key\"}", "{\"savepointId\":\"sp\",\"idempotencyKey\":\"\"}"})
    void invalidRestoreDoesNotStopAnything(String body) throws Exception {
        mvc.perform(post("/api/executions/" + ID + "/restore").contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        verifyNoInteractions(coordinator, jdbc);
    }

    @Test void failedStopDoesNotStartAReplacement() throws Exception {
        when(coordinator.stop(ID)).thenThrow(new OpsCoordinatorClient.UpstreamDownException(new IOException("offline")));
        mvc.perform(post("/api/executions/" + ID + "/restore").contentType("application/json").content(RESTORE_BODY))
                .andExpect(status().isBadGateway());
        verify(coordinator, never()).restore(anyString(), any(), anyString());
        verify(jdbc, never()).update(startsWith("UPDATE executions SET idempotency_key="), anyString(), anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"savepoints", "restore", "rescale"})
    void foreignExecutionIsHiddenBeforeCallingCoordinator(String operation) throws Exception {
        mvc.perform(post("/api/executions/" + ID + "/" + operation).header("X-Org", "other")
                        .contentType("application/json").content(RESTORE_BODY))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("NOT_FOUND"));
        verify(jdbc).queryForObject("SELECT EXISTS (SELECT 1 FROM executions WHERE id=? AND org_id=?)",
                Boolean.class, ID, "other");
        verifyNoInteractions(coordinator);
        verify(jdbc, never()).query(contains("idempotency_key"), ArgumentMatchers.<RowMapper<String>>any(),
                anyString(), anyString());
    }

    @Test void savepointFromAnotherExecutionIsRejectedBeforeIdempotencyLookup() throws Exception {
        when(coordinator.savepoints()).thenReturn(mapper.createArrayNode().add(
                saved.deepCopy().put("executionId", "exec-other")));
        mvc.perform(post("/api/executions/" + ID + "/restore").contentType("application/json").content(RESTORE_BODY))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("NOT_FOUND"));
        verify(coordinator, never()).stop(anyString());
        verify(coordinator, never()).restore(anyString(), any(), anyString());
        verify(jdbc, never()).query(contains("idempotency_key"), ArgumentMatchers.<RowMapper<String>>any(),
                anyString(), anyString());
    }

    @Test void foreignActiveExecutionCannotBeDisplacedByRestore() throws Exception {
        when(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM executions " +
                        "WHERE state IN ('CREATED','RUNNING','RECOVERING') AND org_id<>?)",
                Boolean.class, "demo")).thenReturn(true);
        mvc.perform(post("/api/executions/" + ID + "/restore").contentType("application/json").content(RESTORE_BODY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("EXECUTION_BUSY"));
        var order = inOrder(jdbc);
        order.verify(jdbc).execute("SELECT pg_advisory_xact_lock(1095062612)");
        order.verify(jdbc).queryForObject(contains("org_id<>?"), eq(Boolean.class), eq("demo"));
        verify(coordinator, never()).stop(anyString());
        verify(coordinator, never()).restore(anyString(), any(), anyString());
    }

    @Test void restoreAttachesCallerOrgAndNamespacesTheKey() throws Exception {
        when(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM executions WHERE id=? AND org_id=?)",
                Boolean.class, ID, "acme")).thenReturn(true);
        ResultSet row = mock(ResultSet.class);
        when(row.getString(1)).thenReturn(plan.toString());
        when(jdbc.query(contains("SELECT latest.plan"), ArgumentMatchers.<RowMapper<JsonNode>>any(), eq(ID), eq("acme")))
                .thenAnswer(invocation -> List.of(invocation.<RowMapper<JsonNode>>getArgument(1).mapRow(row, 0)));
        String response = mvc.perform(post("/api/executions/" + ID + "/restore").header("X-Org", "acme")
                        .contentType("application/json").content(RESTORE_BODY))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String restoredId = mapper.readTree(response).path("id").asText();
        verify(jdbc).query(contains("WHERE idempotency_key=? AND org_id=?"), ArgumentMatchers.<RowMapper<String>>any(),
                eq("4:acme:" + KEY), eq("acme"));
        verify(jdbc).update(startsWith("UPDATE executions SET idempotency_key="),
                eq("4:acme:" + KEY), eq("acme"), eq(restoredId));
        assertThat(auditDetail("execution.restored").path("orgId").asText()).isEqualTo("acme");
    }

    @Test void clientUsesCoordinatorRoutesAndTheFromSavepointWireShape() throws Exception {
        List<String> requests = new ArrayList<>();
        AtomicReference<JsonNode> startBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/coord", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.add(exchange.getRequestMethod() + " " + path);
            if (path.equals("/coord/executions")) startBody.set(mapper.readTree(exchange.getRequestBody()));
            byte[] response = (path.equals("/coord/savepoints") ? "[" + saved + "]" : saved.toString())
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var out = exchange.getResponseBody()) { out.write(response); }
        });
        server.start();
        try {
            OpsCoordinatorClient client = client(server);
            assertThat(client.savepoint(ID)).isEqualTo(saved);
            assertThat(client.savepoints().get(0)).isEqualTo(saved);
            client.stop(ID);
            client.execution(ID);
            client.restore("exec-new", plan, SAVEPOINT);
            client.rebalance(ID);
            assertThat(requests).containsExactly("POST /coord/executions/exec-old/savepoint", "GET /coord/savepoints",
                    "POST /coord/executions/exec-old/stop", "GET /coord/executions/exec-old",
                    "POST /coord/executions", "POST /coord/executions/exec-old/rebalance");
            assertThat(startBody.get().path("executionId").asText()).isEqualTo("exec-new");
            assertThat(startBody.get().path("plan")).isEqualTo(plan);
            assertThat(startBody.get().path("fromSavepoint").asText()).isEqualTo(SAVEPOINT);
        } finally { server.stop(0); }
    }

    @Test void clientRetainsCompatibilityErrorsAndDistinguishesTransportFailure() throws Exception {
        String error = "{\"error\":\"INCOMPATIBLE_SAVEPOINT\",\"message\":\"Source topic changed.\"}";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/coord/executions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = error.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(409, response.length);
            try (var out = exchange.getResponseBody()) { out.write(response); }
        });
        server.start();
        OpsCoordinatorClient client = client(server);
        try {
            assertThatThrownBy(() -> client.restore("exec-new", plan, SAVEPOINT))
                    .isInstanceOfSatisfying(OpsCoordinatorClient.ResponseException.class, e -> {
                        assertThat(e.status()).isEqualTo(409);
                        assertThat(e.body()).isEqualTo(error);
                    });
        } finally { server.stop(0); }
        assertThatThrownBy(client::savepoints).isInstanceOf(OpsCoordinatorClient.UpstreamDownException.class);
    }

    private OpsCoordinatorClient client(HttpServer server) {
        return new OpsCoordinatorClient("http://127.0.0.1:" + server.getAddress().getPort() + "/", mapper);
    }

    private JsonNode execution(String id) {
        return mapper.createObjectNode().put("id", id).put("pipelineId", plan.path("pipelineId").asText())
                .put("pipelineVersion", 3).put("state", "RUNNING").put("generation", 1)
                .put("restoredFromCheckpoint", 7).put("lastCompletedCheckpoint", 0).put("createdAt", CREATED_AT);
    }

    private JsonNode auditDetail(String action) throws Exception {
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(eq("INSERT INTO audit_log(actor,action,detail) VALUES ('ui',?,?::jsonb)"), eq(action), detail.capture());
        return mapper.readTree(detail.getValue());
    }
}
