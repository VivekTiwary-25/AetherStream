package io.aetherstream.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Package-local core beans stay unchanged; this adapter lives in their package for access. */
@Component
public class LiveSources {
    private final CoordinatorClient coordinator;
    private final SimulatorClient simulator;
    private final ResultRepository results;
    private final ControlApiController controller;
    private final ObjectMapper mapper;

    LiveSources(CoordinatorClient coordinator, SimulatorClient simulator, ResultRepository results,
                ControlApiController controller, ObjectMapper mapper) {
        this.coordinator = coordinator;
        this.simulator = simulator;
        this.results = results;
        this.controller = controller;
        this.mapper = mapper;
    }

    public JsonNode cluster() { return coordinator.state(); }
    public JsonNode simulator() { return simulator.state(); }
    public String currentExecutionId() { return results.currentExecutionId(); }
    public JsonNode execution() {
        Map<String, Object> body = controller.currentExecution().getBody();
        return body == null ? null : mapper.valueToTree(body);
    }
    public Map<String, Object> summary(String executionId, double eventsPerSec) {
        return results.summary(executionId, eventsPerSec);
    }
}
