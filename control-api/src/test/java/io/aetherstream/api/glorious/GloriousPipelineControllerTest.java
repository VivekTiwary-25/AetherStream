package io.aetherstream.api.glorious;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class GloriousPipelineControllerTest {
    private static final String BASE = "/api/glorious/pipelines";
    private static final String VALID = """
            {"name":"Live trucks","nodes":[{"id":"o","type":"output","params":{"name":"Trucks"}}],"edges":[]}
            """;
    private final PipelineRuns runs = mock(PipelineRuns.class);
    private MockMvc mvc;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new GloriousPipelineController(runs, new ObjectMapper())).build();
    }

    @Test void exposesRealFieldsAndExactlyFiveNodeTypes() throws Exception {
        String body = mvc.perform(get(BASE + "/fields")).andExpect(status().isOk())
                .andExpect(jsonPath("$.fields[7].name").value("partition"))
                .andExpect(jsonPath("$.fields[8].name").value("eventTime"))
                .andReturn().getResponse().getContentAsString();
        assertThat(new ObjectMapper().readTree(body).path("nodeTypes"))
                .isEqualTo(new ObjectMapper().valueToTree(List.of("filter", "pickFields", "windowAverage", "thresholdAlert", "output")));
    }

    @Test void validationAndStartShareErrorsAndInvalidInputCannotDisplaceRun() throws Exception {
        String invalid = VALID.replace("output", "source");
        String validation = mvc.perform(post(BASE + "/validate").contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[0].nodeId").value("o")).andReturn().getResponse().getContentAsString();
        String start = mvc.perform(post(BASE + "/runs").contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(start).isEqualTo(validation);
        verifyNoInteractions(runs);
    }

    @Test void startsPollsCursorAndStopsRun() throws Exception {
        when(runs.start(any())).thenReturn("run-1");
        when(runs.results("run-1", 7)).thenReturn(new PipelineRuns.Snapshot("run-1", "RUNNING", null, 123, 9, 2, List.of()));
        mvc.perform(post(BASE + "/runs").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.runId").value("run-1"));
        mvc.perform(get(BASE + "/runs/run-1/results?after=7"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("RUNNING"))
                .andExpect(jsonPath("$.eventsIn").value(9)).andExpect(jsonPath("$.droppedRows").value(2));
        mvc.perform(post(BASE + "/runs/run-1/stop"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("STOPPED"));
        verify(runs).stop("run-1");
    }

    @Test void rejectsOversizedAndMalformedBodiesWithoutStartingAnything() throws Exception {
        for (String path : List.of("/validate", "/runs")) {
            mvc.perform(post(BASE + path).contentType(MediaType.APPLICATION_JSON).content(" ".repeat(65537)))
                    .andExpect(status().isPayloadTooLarge());
            for (String malformed : List.of("{", "", VALID + " {}"))
                mvc.perform(post(BASE + path).contentType(MediaType.APPLICATION_JSON).content(malformed))
                        .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(runs);
    }

    @Test void enforcesLimitForUnknownLengthBodiesToo() throws Exception {
        jakarta.servlet.http.HttpServletRequest request = mock(jakarta.servlet.http.HttpServletRequest.class);
        when(request.getContentLengthLong()).thenReturn(-1L);
        when(request.getInputStream()).thenReturn(new org.springframework.mock.web.DelegatingServletInputStream(
                new java.io.ByteArrayInputStream(new byte[65537])));
        GloriousPipelineController controller = new GloriousPipelineController(runs, new ObjectMapper());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.validate(request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
    }

    @Test void missingRunsAndNegativeCursorsHaveHonestHttpErrors() throws Exception {
        when(runs.results("missing", 0)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "pipeline run not found"));
        mvc.perform(get(BASE + "/runs/missing/results")).andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/runs/missing/results?after=-1")).andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/runs/missing/results?after=abc")).andExpect(status().isBadRequest());
    }
}
