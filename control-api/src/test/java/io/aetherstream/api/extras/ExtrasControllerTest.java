package io.aetherstream.api.extras;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ExtrasController.class)
class ExtrasControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean ExtrasClient client;

    @Test void scenarioRequestAndResponseAreProxied() throws Exception {
        HttpResponse<String> response = response(200, "{\"scenario\":\"rush-hour\"}");
        when(client.scenario("{\"name\":\"rush-hour\"}")).thenReturn(response);
        mvc.perform(post("/api/simulator/scenario").contentType("application/json").content("{\"name\":\"rush-hour\"}"))
                .andExpect(status().isOk()).andExpect(content().json("{\"scenario\":\"rush-hour\"}"));
    }

    @Test void scenarioBadRequestIsPassedThrough() throws Exception {
        HttpResponse<String> bad = response(400, "{\"error\":\"invalid scenario\"}");
        when(client.scenario("{}")).thenReturn(bad);
        mvc.perform(post("/api/simulator/scenario").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(content().json("{\"error\":\"invalid scenario\"}"));
    }

    @Test void coordinatorMetricsArePlainText() throws Exception {
        when(client.coordinatorMetrics()).thenReturn("requests_total 7\n");
        mvc.perform(get("/api/metrics/coordinator"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(content().string("requests_total 7\n"));
    }

    @Test void listsFourScenarios() throws Exception {
        mvc.perform(get("/api/scenarios")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("rush-hour"))
                .andExpect(jsonPath("$[1].name").value("breakdown"))
                .andExpect(jsonPath("$[2].name").value("surge"))
                .andExpect(jsonPath("$[3].name").value("reset"));
    }

    @Test void upstreamFailureUsesRequiredError() throws Exception {
        when(client.scenario("{}" )).thenThrow(new ExtrasUpstreamException("connection refused"));
        mvc.perform(post("/api/simulator/scenario").contentType("application/json").content("{}"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.error").value("UPSTREAM_DOWN"))
                .andExpect(jsonPath("$.message").value("simulator is not reachable"));
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(int status, String body) {
        HttpResponse<String> response = org.mockito.Mockito.mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }
}
