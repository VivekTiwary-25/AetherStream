package io.aetherstream.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ControlApiController.class)
@Import(ApiErrorHandler.class)
class ControlApiControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean CoordinatorClient coordinator;
    @MockitoBean SimulatorClient simulator;
    @MockitoBean ResultRepository results;
    @MockitoBean JdbcTemplate jdbc;

    @Test void versionContainsBuildMetadata() throws Exception {
        mvc.perform(get("/api/version"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.commit").exists()).andExpect(jsonPath("$.builtAt").exists());
    }

    @Test void healthIsProcessOnly() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test void chaosEndpointProxiesPayload() throws Exception {
        when(results.currentExecutionId()).thenReturn("exec-1");
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq("exec-1"), eq("demo"))).thenReturn(true);
        when(coordinator.state()).thenReturn(mapper.readTree("{\"execution\":{\"id\":\"exec-1\",\"state\":\"RUNNING\"}}"));
        when(simulator.post(org.mockito.ArgumentMatchers.eq("/chaos/duplicates"), any()))
                .thenReturn(mapper.readTree("{\"sent\":2}"));
        mvc.perform(post("/api/chaos/duplicates").contentType("application/json").content("{\"count\":2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sent").value(2));
    }

    @Test void upstreamFailureUsesContractError() throws Exception {
        when(simulator.state()).thenThrow(new UpstreamException("connection refused"));
        mvc.perform(get("/api/simulator/state")).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("UPSTREAM_DOWN"))
                .andExpect(jsonPath("$.message").value("coordinator is not reachable"));
    }

    @Test void summaryHasContractShape() throws Exception {
        when(results.currentExecutionId()).thenReturn("exec-1");
        when(coordinator.state()).thenReturn(mapper.readTree("{\"workers\":[]}"));
        when(results.summary("exec-1", 0)).thenReturn(Map.of("executionId", "exec-1", "windowsWritten", 3,
                "alerts", 1, "rejected", 0, "late", 0, "deliveriesLastMinute", 2.0,
                "avgSpeedLastMinute", 31.2, "eventsPerSec", 0.0));
        mvc.perform(get("/api/results/summary")).andExpect(status().isOk())
                .andExpect(jsonPath("$.executionId").value("exec-1"))
                .andExpect(jsonPath("$.windowsWritten").value(3))
                .andExpect(jsonPath("$.deliveriesLastMinute").value(2.0))
                .andExpect(jsonPath("$.avgSpeedLastMinute").value(31.2))
                .andExpect(jsonPath("$.eventsPerSec").value(0.0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a_b", "a.b", "a b", "é", "a,b", "a/b", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void invalidOrgIsRejectedBeforeDispatch(String org) throws Exception {
        mvc.perform(get("/api/health").header("X-Org", org))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_ORG"));
    }

    @Test void multipleOrgHeadersAreRejected() throws Exception {
        mvc.perform(get("/api/health").header("X-Org", "a", "b"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_ORG"));
    }

    @Test void foreignExecutionMutationsAndCheckpointsNeverReachCoordinator() throws Exception {
        mvc.perform(post("/api/executions/foreign/stop").header("X-Org", "b"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("NOT_FOUND"));
        mvc.perform(get("/api/executions/foreign/checkpoints").header("X-Org", "b"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("NOT_FOUND"));
        verifyNoInteractions(coordinator, results);
    }

    @Test void chaosWithoutOwnedCurrentExecutionDoesNotReachSharedServices() throws Exception {
        mvc.perform(post("/api/chaos/duplicates").header("X-Org", "b")
                        .contentType("application/json").content("{\"count\":2}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("NOT_FOUND"));
        verifyNoInteractions(coordinator, simulator);
    }

    @Test void chaosCannotTargetAnotherOrganisationsActiveExecution() throws Exception {
        when(results.currentExecutionId()).thenReturn("own-stopped");
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq("own-stopped"), eq("b"))).thenReturn(true);
        when(coordinator.state()).thenReturn(mapper.readTree("{\"execution\":{\"id\":\"foreign\",\"state\":\"RUNNING\"}}"));
        mvc.perform(post("/api/chaos/duplicates").header("X-Org", "b")
                        .contentType("application/json").content("{\"count\":2}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("NOT_FOUND"));
        verifyNoInteractions(simulator);
    }
}
