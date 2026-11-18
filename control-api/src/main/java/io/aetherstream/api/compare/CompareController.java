package io.aetherstream.api.compare;

import io.aetherstream.common.api.CompareResult;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/compare")
@CrossOrigin(origins = "*")
public class CompareController {
    private final CompareService service;

    public CompareController(CompareService service) {
        this.service = service;
    }

    @PostMapping
    public CompareResult compare() {
        return service.compare();
    }

    @ExceptionHandler(CompareService.CompareException.class)
    ResponseEntity<Map<String, String>> compareError(CompareService.CompareException e) {
        return ResponseEntity.status(e.status).body(Map.of("error", e.code, "message", e.getMessage()));
    }
}
