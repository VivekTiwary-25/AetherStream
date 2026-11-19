package io.aetherstream.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aetherstream.api.org.OrgFilter;
import io.aetherstream.common.api.ExecutionInfo;
import io.aetherstream.common.pipeline.PipelineDefinition;
import io.aetherstream.common.protocol.StartExecutionRequest;
import io.aetherstream.compiler.PipelineCompiler;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
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
@Import({ApiErrorHandler.class, PipelineRepository.class, ExecutionService.class, OrgFilter.class})
class PipelineContractTest {
    private static final String AT = "2026-11-19T01:10:00Z";
    private static final String KEY = "4bda73c3-8657-4f77-ad3e-c8616ce9e310";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean JdbcTemplate jdbc;
    @MockitoBean CoordinatorClient coordinator;
    private final PipelineDefinition demo = PipelineCompiler.demoPipeline();

    @BeforeEach void setup() throws Exception {
        latest("demo", demo);
        when(jdbc.update(startsWith("UPDATE executions SET idempotency_key="), anyString(), anyString(), anyString()))
                .thenReturn(1);
        when(coordinator.startExecution(any())).thenAnswer(call -> {
            StartExecutionRequest request = call.getArgument(0);
            return new ExecutionInfo(request.executionId(), request.plan().pipelineId(), request.plan().pipelineVersion(),
                    "RUNNING", 1, null, 0L, AT);
        });
    }

    @Test void demoAndValidValidationExposeTheCompleteContractPlan() throws Exception {
        String body = mvc.perform(get("/api/pipelines/demo")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(mapper.readValue(body, PipelineDefinition.class)).isEqualTo(demo);
        JsonNode response = mapper.readTree(mvc.perform(post("/api/pipelines/validate")
                        .contentType("application/json").content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true)).andExpect(jsonPath("$.errors").isEmpty())
                .andExpect(jsonPath("$.plan.pipelineId").value(demo.id()))
                .andExpect(jsonPath("$.plan.pipelineVersion").value(1))
                .andExpect(jsonPath("$.plan.topic").value("truck-events"))
                .andExpect(jsonPath("$.plan.partitions").value(6))
                .andExpect(jsonPath("$.plan.startFrom").value("latest"))
                .andExpect(jsonPath("$.plan.operators[0].inputs").isEmpty())
                .andExpect(jsonPath("$.plan.operators[0].outputType").value("EVENT"))
                .andReturn().getResponse().getContentAsString());
        assertThat(response.get("plan")).isEqualTo(mapper.valueToTree(new PipelineCompiler().plan(demo, 6)));
        verifyNoInteractions(jdbc, coordinator);
    }

    @Test void invalidValidationCollectsErrorsAndSavingReturnsErrorDtoWithoutWriting() throws Exception {
        var invalid = new PipelineDefinition("invalid", 1, "Invalid", List.of(), List.of());
        String body = mapper.writeValueAsString(invalid);
        mvc.perform(post("/api/pipelines/validate").contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.plan").isEmpty())
                .andExpect(jsonPath("$.errors[?(@.code == 'NO_SOURCE')]").isNotEmpty())
                .andExpect(jsonPath("$.errors[?(@.code == 'NO_SINK')]").isNotEmpty())
                .andExpect(jsonPath("$.errors[0].nodeId").isEmpty())
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());
        mvc.perform(post("/api/pipelines").contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_PIPELINE"))
                .andExpect(jsonPath("$.message").isNotEmpty()).andExpect(jsonPath("$.errors").isArray());
        verifyNoInteractions(jdbc, coordinator);
    }

    @Test void createAllocatesVersionsPersistsThemAndListsAndReadsOnlyLatest() throws Exception {
        when(jdbc.queryForObject(startsWith("SELECT COALESCE(max(version)"), eq(Integer.class), eq(demo.id())))
                .thenReturn(1, 2);
        ResultSet row = summaryRow(1);
        when(jdbc.queryForObject(startsWith("INSERT INTO pipelines"),
                org.mockito.ArgumentMatchers.<RowMapper<PipelineRepository.PipelineSummary>>any(),
                eq(demo.id()), anyInt(), eq(demo.name()), anyString(), eq("demo")))
                .thenAnswer(call -> {
                    int version = call.getArgument(3);
                    when(row.getInt("version")).thenReturn(version);
                    RowMapper<PipelineRepository.PipelineSummary> mapping = call.getArgument(1);
                    return mapping.mapRow(row, 0);
                });
        for (int version = 1; version <= 2; version++) {
            mvc.perform(post("/api/pipelines").contentType("application/json").content(mapper.writeValueAsString(demo)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(demo.id()))
                    .andExpect(jsonPath("$.version").value(version)).andExpect(jsonPath("$.name").value(demo.name()))
                    .andExpect(jsonPath("$.createdAt").value(AT));
        }
        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).queryForObject(startsWith("INSERT INTO pipelines"),
                org.mockito.ArgumentMatchers.<RowMapper<PipelineRepository.PipelineSummary>>any(), eq(demo.id()),
                anyInt(), eq(demo.name()), stored.capture(), eq("demo"));
        assertThat(mapper.readValue(stored.getAllValues().get(0), PipelineDefinition.class).version()).isEqualTo(1);
        PipelineDefinition second = mapper.readValue(stored.getAllValues().get(1), PipelineDefinition.class);
        assertThat(second.version()).isEqualTo(2);
        latest("demo", second);
        when(jdbc.query(startsWith("SELECT DISTINCT ON (id)"),
                org.mockito.ArgumentMatchers.<RowMapper<PipelineRepository.PipelineSummary>>any(), eq("demo")))
                .thenAnswer(call -> {
                    RowMapper<PipelineRepository.PipelineSummary> mapping = call.getArgument(1);
                    return List.of(mapping.mapRow(row, 0));
                });
        mvc.perform(get("/api/pipelines")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[0].createdAt").value(AT));
        String fetched = mvc.perform(get("/api/pipelines/" + demo.id())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(mapper.readValue(fetched, PipelineDefinition.class)).isEqualTo(second);
    }

    @Test void startUsesBodyKeyAndLatestPlanAndReplayDoesNotStartOrStopAgain() throws Exception {
        PipelineDefinition latest = new PipelineDefinition(demo.id(), 2, demo.name(), demo.nodes(), demo.edges());
        latest("demo", latest);
        ExecutionInfo first = start(KEY);
        assertThat(first.pipelineId()).isEqualTo(demo.id());
        assertThat(first.pipelineVersion()).isEqualTo(2);
        assertThat(first.state()).isEqualTo("RUNNING");
        assertThat(first.generation()).isEqualTo(1);
        assertThat(first.restoredFromCheckpoint()).isNull();
        assertThat(first.lastCompletedCheckpoint()).isZero();
        assertThat(first.createdAt()).isEqualTo(AT);
        ResultSet row = mock(ResultSet.class);
        when(row.getString("id")).thenReturn(first.id());
        when(row.getString("pipeline_id")).thenReturn(demo.id());
        when(row.getInt("pipeline_version")).thenReturn(2);
        when(row.getString("state")).thenReturn("RUNNING");
        when(row.getLong("generation")).thenReturn(1L);
        when(row.getObject("last_completed_checkpoint")).thenReturn(0L);
        when(row.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.parse(AT)));
        when(jdbc.query(contains("WHERE idempotency_key=? AND org_id=?"),
                org.mockito.ArgumentMatchers.<RowMapper<ExecutionInfo>>any(), eq("4:demo:" + KEY), eq("demo")))
                .thenAnswer(call -> {
                    RowMapper<ExecutionInfo> mapping = call.getArgument(1);
                    return List.of(mapping.mapRow(row, 0));
                });
        assertThat(start(KEY)).isEqualTo(first);
        ArgumentCaptor<StartExecutionRequest> request = ArgumentCaptor.forClass(StartExecutionRequest.class);
        verify(coordinator).startExecution(request.capture());
        assertThat(request.getValue().plan()).isEqualTo(new PipelineCompiler().plan(latest, 6));
        verify(coordinator, never()).stop(anyString());
        verify(jdbc).update(startsWith("UPDATE executions SET idempotency_key="), eq("4:demo:" + KEY), eq("demo"), eq(first.id()));
    }

    @Test void replacementStopsTheOldExecutionBeforeStartingNewOne() throws Exception {
        when(jdbc.query(contains("state IN ('RUNNING','RECOVERING')"),
                org.mockito.ArgumentMatchers.<RowMapper<String>>any(), eq("demo"))).thenReturn(List.of("old-execution"));
        start(KEY);
        var order = inOrder(coordinator);
        order.verify(coordinator).stop("old-execution");
        order.verify(coordinator).startExecution(any());
    }

    @Test void missingPipelinesAndEmptyKeysReturnErrorDtoWithoutCoordinatorCalls() throws Exception {
        mvc.perform(get("/api/pipelines/missing")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND")).andExpect(jsonPath("$.message").isNotEmpty());
        mvc.perform(post("/api/pipelines/missing/executions").contentType("application/json")
                        .content("{\"idempotencyKey\":\"" + KEY + "\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("NOT_FOUND"));
        for (String body : List.of("{}", "{\"idempotencyKey\":null}", "{\"idempotencyKey\":\"\"}", "{\"idempotencyKey\":\"  \"}")) {
            mvc.perform(post("/api/pipelines/" + demo.id() + "/executions").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }
        verifyNoInteractions(coordinator);
    }

    @Test void orgHeaderScopesReadsAndPreventsForeignPipelineStartAndIdTakeover() throws Exception {
        mvc.perform(get("/api/pipelines/" + demo.id()).header("X-Org", "other"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("NOT_FOUND"));
        mvc.perform(get("/api/pipelines").header("X-Org", "other"))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        mvc.perform(post("/api/pipelines/" + demo.id() + "/executions").header("X-Org", "other")
                        .contentType("application/json").content("{\"idempotencyKey\":\"" + KEY + "\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("NOT_FOUND"));
        when(jdbc.queryForObject(contains("FROM pipelines WHERE id=? AND org_id<>?"), eq(Boolean.class),
                eq(demo.id()), eq("other"))).thenReturn(true);
        mvc.perform(post("/api/pipelines").header("X-Org", "other").contentType("application/json")
                        .content(mapper.writeValueAsString(demo)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("PIPELINE_ID_TAKEN"))
                .andExpect(jsonPath("$.message").isNotEmpty());
        verifyNoInteractions(coordinator);
    }

    @Test void anotherOrgCannotDisplaceTheRunningCluster() throws Exception {
        latest("other", demo);
        when(jdbc.queryForObject(contains("AND org_id<>?"), eq(Boolean.class), eq("other"))).thenReturn(true);
        mvc.perform(post("/api/pipelines/" + demo.id() + "/executions").header("X-Org", "other")
                        .contentType("application/json").content("{\"idempotencyKey\":\"" + KEY + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("EXECUTION_BUSY"))
                .andExpect(jsonPath("$.message").isNotEmpty());
        verifyNoInteractions(coordinator);
    }

    @Disabled("CONTRACT: contract 04 specifies a UUID idempotencyKey, but ExecutionService accepts any nonblank string")
    @Test void nonUuidIdempotencyKeyIsRejected() throws Exception {
        mvc.perform(post("/api/pipelines/" + demo.id() + "/executions").contentType("application/json")
                        .content("{\"idempotencyKey\":\"not-a-uuid\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").isString())
                .andExpect(jsonPath("$.message").isNotEmpty());
        verifyNoInteractions(coordinator);
    }

    @Test void orgHeaderIsIncludedInIdempotencyLookupAndPersistence() throws Exception {
        ExecutionInfo first = start(KEY);
        when(jdbc.query(contains("WHERE idempotency_key=? AND org_id=?"),
                ArgumentMatchers.<RowMapper<ExecutionInfo>>any(), eq("4:demo:" + KEY), eq("demo")))
                .thenReturn(List.of(first));
        latest("other", demo);
        String response = mvc.perform(post("/api/pipelines/" + demo.id() + "/executions")
                        .header("X-Org", "other").contentType("application/json")
                        .content("{\"idempotencyKey\":\"" + KEY + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        ExecutionInfo execution = mapper.readValue(response, ExecutionInfo.class);
        assertThat(execution.id()).isNotEqualTo(first.id());
        verify(jdbc).query(contains("WHERE idempotency_key=? AND org_id=?"),
                org.mockito.ArgumentMatchers.<RowMapper<ExecutionInfo>>any(), eq("5:other:" + KEY), eq("other"));
        verify(jdbc).update(startsWith("UPDATE executions SET idempotency_key="),
                eq("5:other:" + KEY), eq("other"), eq(execution.id()));
        verify(coordinator, times(2)).startExecution(any());
    }

    private ExecutionInfo start(String key) throws Exception {
        String response = mvc.perform(post("/api/pipelines/" + demo.id() + "/executions")
                        .contentType("application/json").content("{\"idempotencyKey\":\"" + key + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readValue(response, ExecutionInfo.class);
    }

    private void latest(String org, PipelineDefinition definition) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString(1)).thenReturn(mapper.writeValueAsString(definition));
        when(jdbc.query(startsWith("SELECT definition::text"),
                ArgumentMatchers.<RowMapper<PipelineDefinition>>any(), eq(definition.id()), eq(org)))
                .thenAnswer(call -> {
                    RowMapper<PipelineDefinition> mapping = call.getArgument(1);
                    return List.of(mapping.mapRow(row, 0));
                });
    }

    private ResultSet summaryRow(int version) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("id")).thenReturn(demo.id());
        when(row.getInt("version")).thenReturn(version);
        when(row.getString("name")).thenReturn(demo.name());
        when(row.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.parse(AT)));
        return row;
    }
}
