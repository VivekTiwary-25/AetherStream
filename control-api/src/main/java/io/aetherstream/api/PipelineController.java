package io.aetherstream.api;

import io.aetherstream.common.api.ExecutionInfo;
import io.aetherstream.common.pipeline.PipelineDefinition;
import io.aetherstream.common.pipeline.ValidationResult;
import io.aetherstream.compiler.PipelineCompiler;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/pipelines")
@CrossOrigin(origins = "*")
class PipelineController {
    private final PipelineRepository pipelines;
    private final ExecutionService executions;
    private final PipelineCompiler compiler = new PipelineCompiler();

    PipelineController(PipelineRepository pipelines, ExecutionService executions) {
        this.pipelines = pipelines; this.executions = executions;
    }

    @PostMapping("/validate") ValidationResult validate(@RequestBody PipelineDefinition definition) {
        return compiler.validate(definition);
    }

    @PostMapping ResponseEntity<?> create(@RequestBody PipelineDefinition definition) {
        ValidationResult validation = compiler.validate(definition);
        if (!validation.valid()) return ResponseEntity.badRequest().body(Map.of(
                "error", "INVALID_PIPELINE", "message", "fix the pipeline validation errors before saving",
                "errors", validation.errors()));
        return ResponseEntity.ok(pipelines.create(definition));
    }

    @GetMapping List<PipelineRepository.PipelineSummary> list() { return pipelines.list(); }

    @GetMapping("/demo") PipelineDefinition demo() { return PipelineCompiler.demoPipeline(); }

    @GetMapping("/{id}") PipelineDefinition get(@PathVariable String id) {
        return pipelines.latest(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "pipeline not found"));
    }

    @PostMapping("/{id}/executions") ExecutionInfo start(@PathVariable String id, @RequestBody StartRequest request) {
        return executions.start(id, request.idempotencyKey());
    }

    record StartRequest(String idempotencyKey) {}
}
