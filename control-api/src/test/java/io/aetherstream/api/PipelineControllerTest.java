package io.aetherstream.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.aetherstream.api.org.OrgContext;
import io.aetherstream.common.api.ExecutionInfo;
import io.aetherstream.common.pipeline.PipelineDefinition;
import io.aetherstream.common.protocol.StartExecutionRequest;
import io.aetherstream.compiler.PipelineCompiler;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PipelineController.class)
@Import({ApiErrorHandler.class, ExecutionService.class})
class PipelineControllerTest {
    private static final String KEY = "reusable-key";
    private static final String CREATED_AT = "2026-11-19T01:10:00Z";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean PipelineRepository pipelines;
    @MockitoBean CoordinatorClient coordinator;
    @MockitoBean JdbcTemplate jdbc;
    private final PipelineDefinition demo = PipelineCompiler.demoPipeline();

    @BeforeEach void setup() {
        when(pipelines.latest(demo.id())).thenReturn(Optional.of(demo));
        when(jdbc.update(startsWith("UPDATE executions SET idempotency_key="), anyString(), anyString(), anyString())).thenReturn(1);
        when(coordinator.startExecution(any())).thenAnswer(invocation -> {
            StartExecutionRequest request = invocation.getArgument(0);
            return new ExecutionInfo(request.executionId(), request.plan().pipelineId(), request.plan().pipelineVersion(),
                    "RUNNING", 1, null, 0L, CREATED_AT);
        });
    }

    @Test void invalidPipelineReturnsValidationErrorsAndCannotBeSaved() throws Exception {
        PipelineDefinition noSink = new PipelineDefinition("invalid", 1, "No sink",
                List.of(demo.nodes().get(0)), List.of());
        String body = mapper.writeValueAsString(noSink);
        mvc.perform(post("/api/pipelines/validate").contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[?(@.code == 'NO_SINK')]").isNotEmpty())
                .andExpect(jsonPath("$.plan").isEmpty());
        mvc.perform(post("/api/pipelines").contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_PIPELINE"))
                .andExpect(jsonPath("$.errors[?(@.code == 'NO_SINK')]").isNotEmpty());
        verify(pipelines, never()).create(any());
    }

    @Test void demoValidatesWithItsSixPartitionPlan() throws Exception {
        String demoJson = mvc.perform(get("/api/pipelines/demo")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(mapper.readValue(demoJson, PipelineDefinition.class)).isEqualTo(demo);
        String validation = mvc.perform(post("/api/pipelines/validate").contentType("application/json").content(demoJson))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.errors").isEmpty()).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(validation).get("plan"))
                .isEqualTo(mapper.valueToTree(new PipelineCompiler().plan(demo, 6)));
    }

    @Test void creatingTheSameIdReturnsSuccessiveVersions() throws Exception {
        var first = new PipelineRepository.PipelineSummary(demo.id(), 1, demo.name(), CREATED_AT);
        var second = new PipelineRepository.PipelineSummary(demo.id(), 2, demo.name(), CREATED_AT);
        when(pipelines.create(any())).thenReturn(first, second);
        String body = mapper.writeValueAsString(demo);
        mvc.perform(post("/api/pipelines").contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(demo.id()))
                .andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.createdAt").value(CREATED_AT));
        mvc.perform(post("/api/pipelines").contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        when(pipelines.list()).thenReturn(List.of(second));
        mvc.perform(get("/api/pipelines")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value(2));
    }

    @Test void repositoryStoresTheAllocatedVersionInTheDefinitionAndAuditsIt() throws Exception {
        JdbcTemplate database = mock(JdbcTemplate.class);
        when(database.queryForObject(anyString(), eq(Integer.class), eq(demo.id()))).thenReturn(2);
        var summary = new PipelineRepository.PipelineSummary(demo.id(), 2, demo.name(), CREATED_AT);
        when(database.queryForObject(startsWith("INSERT INTO pipelines"),
                ArgumentMatchers.<RowMapper<PipelineRepository.PipelineSummary>>any(),
                eq(demo.id()), eq(2), eq(demo.name()), anyString(), eq("demo"))).thenReturn(summary);
        assertThat(new PipelineRepository(database, mapper).create(demo)).isEqualTo(summary);
        ArgumentCaptor<String> definition = ArgumentCaptor.forClass(String.class);
        verify(database).queryForObject(startsWith("INSERT INTO pipelines"),
                ArgumentMatchers.<RowMapper<PipelineRepository.PipelineSummary>>any(),
                eq(demo.id()), eq(2), eq(demo.name()), definition.capture(), eq("demo"));
        PipelineDefinition stored = mapper.readValue(definition.getValue(), PipelineDefinition.class);
        assertThat(stored.version()).isEqualTo(2);
        assertThat(stored.nodes()).isEqualTo(demo.nodes());
        ArgumentCaptor<String> audit = ArgumentCaptor.forClass(String.class);
        verify(database).update(contains("'pipeline.created'"), audit.capture());
        assertThat(mapper.readTree(audit.getValue())).isEqualTo(OrgContext.detail(summary));
    }

    @Test void creatingAnotherOrganisationsPipelineIdFailsWithoutWriting() throws Exception {
        JdbcTemplate database = mock(JdbcTemplate.class);
        when(database.queryForObject(anyString(), eq(Boolean.class), eq(demo.id()), eq("demo"))).thenReturn(true);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PipelineRepository(database, mapper).create(demo))
                .isInstanceOf(io.aetherstream.api.org.OrgException.class)
                .satisfies(error -> {
                    var denied = (io.aetherstream.api.org.OrgException) error;
                    assertThat(denied.status().value()).isEqualTo(409);
                    assertThat(denied.code()).isEqualTo("PIPELINE_ID_TAKEN");
                });
        verify(database, never()).update(anyString(), any(Object[].class));
    }

    @Test void readingUsesTheLatestDefinitionAndMissingIdsReturnNotFound() throws Exception {
        PipelineDefinition latest = version(2);
        when(pipelines.latest(demo.id())).thenReturn(Optional.of(latest));
        mvc.perform(get("/api/pipelines/" + demo.id())).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        when(pipelines.latest("missing")).thenReturn(Optional.empty());
        mvc.perform(get("/api/pipelines/missing")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test void startStopsRecoveringExecutionPlansLatestVersionAndReusesTheKey() throws Exception {
        PipelineDefinition latest = version(2);
        when(pipelines.latest(demo.id())).thenReturn(Optional.of(latest));
        when(jdbc.query(contains("state IN ('RUNNING','RECOVERING')"), ArgumentMatchers.<RowMapper<String>>any(), eq("demo")))
                .thenReturn(List.of("exec-recovering"));
        String response = mvc.perform(post("/api/pipelines/" + demo.id() + "/executions")
                        .contentType("application/json").content("{\"idempotencyKey\":\"" + KEY + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pipelineVersion").value(2))
                .andReturn().getResponse().getContentAsString();
        ExecutionInfo execution = mapper.readValue(response, ExecutionInfo.class);
        assertThat(execution.id()).matches("exec-[0-9]{8}-[0-9]{6}-[0-9a-f]{4}");
        var order = inOrder(coordinator, jdbc);
        order.verify(coordinator).stop("exec-recovering");
        ArgumentCaptor<StartExecutionRequest> request = ArgumentCaptor.forClass(StartExecutionRequest.class);
        order.verify(coordinator).startExecution(request.capture());
        assertThat(request.getValue().plan()).isEqualTo(new PipelineCompiler().plan(latest, 6));
        order.verify(jdbc).update(startsWith("UPDATE executions SET idempotency_key="),
                eq(OrgContext.idempotencyKey(KEY)), eq("demo"), eq(execution.id()));
        order.verify(jdbc).update(contains("'execution.started'"), anyString());
        when(jdbc.query(contains("WHERE idempotency_key=?"), ArgumentMatchers.<RowMapper<ExecutionInfo>>any(),
                eq(OrgContext.idempotencyKey(KEY)), eq("demo")))
                .thenReturn(List.of(execution));
        String retried = mvc.perform(post("/api/pipelines/" + demo.id() + "/executions")
                        .contentType("application/json").content("{\"idempotencyKey\":\"" + KEY + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readValue(retried, ExecutionInfo.class)).isEqualTo(execution);
        verify(coordinator).startExecution(any());
        verify(coordinator).stop("exec-recovering");
    }

    @Test void upstreamFailureDoesNotAttachAKeyOrAuditAStart() throws Exception {
        doThrow(new UpstreamException("offline")).when(coordinator).startExecution(any());
        mvc.perform(post("/api/pipelines/" + demo.id() + "/executions").contentType("application/json")
                        .content("{\"idempotencyKey\":\"" + KEY + "\"}"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.error").value("UPSTREAM_DOWN"));
        verify(jdbc, never()).update(startsWith("UPDATE executions"), anyString(), anyString(), anyString());
        verify(jdbc, never()).update(contains("'execution.started'"), anyString());
    }

    @Test void startCannotDisplaceAnotherOrganisationsExecution() throws Exception {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq("demo"))).thenReturn(true);
        mvc.perform(post("/api/pipelines/" + demo.id() + "/executions")
                        .contentType("application/json").content("{\"idempotencyKey\":\"new\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("EXECUTION_BUSY"));
        verifyNoInteractions(coordinator);
    }

    @Test void missingPipelineAndMissingKeyDoNotStopAnExecution() throws Exception {
        when(pipelines.latest("missing")).thenReturn(Optional.empty());
        mvc.perform(post("/api/pipelines/missing/executions").contentType("application/json")
                        .content("{\"idempotencyKey\":\"new-key\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("NOT_FOUND"));
        mvc.perform(post("/api/pipelines/" + demo.id() + "/executions").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        verifyNoInteractions(coordinator);
    }

    @Test void coordinatorClientPostsTheContractAndReadsExecutionInfo() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<JsonNode> received = new AtomicReference<>();
        AtomicReference<String> method = new AtomicReference<>();
        ExecutionInfo expected = new ExecutionInfo("exec-client", demo.id(), 1, "RUNNING", 1, null, 0L, CREATED_AT);
        byte[] body = mapper.writeValueAsString(expected).getBytes(StandardCharsets.UTF_8);
        server.createContext("/coord/executions", exchange -> {
            method.set(exchange.getRequestMethod());
            received.set(mapper.readTree(exchange.getRequestBody()));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            var client = new CoordinatorClient("http://127.0.0.1:" + server.getAddress().getPort(), mapper);
            var request = new StartExecutionRequest(expected.id(), new PipelineCompiler().plan(demo, 6));
            assertThat(client.startExecution(request)).isEqualTo(expected);
            assertThat(method.get()).isEqualTo("POST");
            assertThat(received.get()).isEqualTo(mapper.valueToTree(request));
        } finally { server.stop(0); }
    }

    private PipelineDefinition version(int version) {
        return new PipelineDefinition(demo.id(), version, demo.name(), demo.nodes(), demo.edges());
    }
}
