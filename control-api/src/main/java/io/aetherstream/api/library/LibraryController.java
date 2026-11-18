package io.aetherstream.api.library;

import io.aetherstream.compiler.PipelineLibrary;
import io.aetherstream.common.pipeline.PipelineDefinition;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/pipelines/examples")
@CrossOrigin(origins = "*")
public class LibraryController {
    @GetMapping
    public List<ExampleSummary> list() {
        return PipelineLibrary.list().stream()
                .map(entry -> new ExampleSummary(entry.id(), entry.name(), entry.description()))
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        try {
            return ResponseEntity.ok(PipelineLibrary.load(id));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "NOT_FOUND", "message", "pipeline example not found"));
        }
    }

    public record ExampleSummary(String id, String name, String description) {}
}
