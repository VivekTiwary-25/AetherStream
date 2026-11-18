package io.aetherstream.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.aetherstream.api.library.LibraryController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({LibraryController.class, PipelineController.class})
@Import(ApiErrorHandler.class)
class LibraryControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean PipelineRepository pipelines;
    @MockitoBean ExecutionService executions;

    @Test void listReturnsExamplesAndLiteralRouteWinsOverPipelineIdRoute() throws Exception {
        mvc.perform(get("/api/pipelines/examples"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("logistics-demo"))
                .andExpect(jsonPath("$[0].name").isNotEmpty())
                .andExpect(jsonPath("$[0].description").isNotEmpty())
                .andExpect(jsonPath("$[0].file").doesNotExist())
                .andExpect(jsonPath("$[1].id").value("speeding-alerts"));
    }

    @Test void getReturnsAnExampleDefinition() throws Exception {
        mvc.perform(get("/api/pipelines/examples/logistics-demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("logistics-demo"))
                .andExpect(jsonPath("$.name").value("Truck city: speed, deliveries, stuck trucks"))
                .andExpect(jsonPath("$.nodes").isArray());
    }

    @Test void unknownExampleReturnsNotFoundError() throws Exception {
        mvc.perform(get("/api/pipelines/examples/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }
}
