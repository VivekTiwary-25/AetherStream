package io.aetherstream.api.glorious;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The five-node demo vocabulary is separate from the engine's existing pipeline API. */
@RestController
@RequestMapping("/api/glorious/pipelines")
public final class GloriousPipelineController {
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private final PipelineValidator validator = new PipelineValidator();
    private final PipelineRuns runs;
    private final ObjectMapper mapper;

    public GloriousPipelineController(PipelineRuns runs, ObjectMapper mapper) {
        this.runs = runs;
        this.mapper = mapper;
    }

    @GetMapping("/fields")
    public Map<String, Object> fields() {
        return Map.of("fields", List.of(
                field("truckId", "string", "Truck identifier"),
                field("speedKmh", "number", "Speed in kilometres per hour"),
                field("heading", "number", "Heading in degrees"),
                field("x", "number", "Simulator x coordinate in metres"),
                field("y", "number", "Simulator y coordinate in metres"),
                field("routeId", "string", "Simulator route identifier"),
                field("status", "string", "DRIVING, UNLOADING, IDLE or STOPPED"),
                field("partition", "number", "Actual Kafka record partition (0–5)"),
                field("eventTime", "number", "Event timestamp in epoch milliseconds")),
                "nodeTypes", List.of("filter", "pickFields", "windowAverage", "thresholdAlert", "output"));
    }

    private static Map<String, String> field(String name, String type, String description) {
        return Map.of("name", name, "type", type, "description", description);
    }

    @PostMapping("/validate")
    public ResponseEntity<?> validate(HttpServletRequest request) {
        return ResponseEntity.ok(validator.validate(readPipeline(request)));
    }

    @PostMapping("/runs")
    public ResponseEntity<?> start(HttpServletRequest request) {
        JsonNode pipeline = readPipeline(request);
        PipelineValidator.Validation validation = validator.validate(pipeline);
        if (!validation.valid()) return ResponseEntity.badRequest().body(validation);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("runId", runs.start(pipeline)));
    }

    @GetMapping("/runs/{runId}/results")
    public PipelineRuns.Snapshot results(@PathVariable String runId,
            @RequestParam(defaultValue = "0") long after) {
        if (after < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "after must be non-negative");
        return runs.results(runId, after);
    }

    @PostMapping("/runs/{runId}/stop")
    public Map<String, String> stop(@PathVariable String runId) {
        return Map.of("state", runs.stop(runId));
    }

    private JsonNode readPipeline(HttpServletRequest request) {
        if (request.getContentLengthLong() > MAX_BODY_BYTES)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "pipeline body exceeds 64 KB");
        try {
            // Reading one extra byte also bounds chunked bodies before JSON allocation/parsing.
            byte[] bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES)
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "pipeline body exceeds 64 KB");
            JsonNode pipeline = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes);
            if (pipeline == null || pipeline.isMissingNode())
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pipeline JSON is required");
            return pipeline;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pipeline body must be valid JSON");
        }
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> requestError(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("valid", false,
                "errors", List.of(Map.of("message", error.getReason() == null ? "request failed" : error.getReason()))));
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<?> invalidCursor() {
        return ResponseEntity.badRequest().body(Map.of("valid", false,
                "errors", List.of(Map.of("message", "after must be a non-negative integer"))));
    }
}
