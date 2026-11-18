package io.aetherstream.api.extras;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ExtrasController {
    private final ExtrasClient client;

    public ExtrasController(ExtrasClient client) {
        this.client = client;
    }

    @PostMapping(value = "/simulator/scenario", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> scenario(@RequestBody String body) {
        HttpResponse<String> response = client.scenario(body);
        MediaType contentType = response.statusCode() == 400
                ? MediaType.APPLICATION_JSON : MediaType.APPLICATION_JSON;
        return ResponseEntity.status(response.statusCode()).contentType(contentType).body(response.body());
    }

    @GetMapping(value = "/metrics/coordinator", produces = MediaType.TEXT_PLAIN_VALUE)
    ResponseEntity<String> coordinatorMetrics() {
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(client.coordinatorMetrics());
    }

    @GetMapping("/scenarios")
    List<Map<String, String>> scenarios() {
        return List.of(
                Map.of("name", "rush-hour", "label", "Rush hour", "description", "Heavy traffic across the city."),
                Map.of("name", "breakdown", "label", "Breakdown", "description", "A truck breaks down during its route."),
                Map.of("name", "surge", "label", "Surge", "description", "A surge of additional delivery traffic."),
                Map.of("name", "reset", "label", "Reset", "description", "Reset the simulator to its normal scenario."));
    }

    @ExceptionHandler(ExtrasUpstreamException.class)
    ResponseEntity<Map<String, String>> upstreamDown(ExtrasUpstreamException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "UPSTREAM_DOWN", "message", "simulator is not reachable"));
    }
}
