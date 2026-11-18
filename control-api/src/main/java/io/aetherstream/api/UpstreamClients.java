package io.aetherstream.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

abstract class JsonUpstreamClient {
    private final String baseUrl;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper mapper;

    JsonUpstreamClient(String baseUrl, ObjectMapper mapper) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.mapper = mapper;
    }

    JsonNode get(String path) { return request("GET", path, null); }
    JsonNode post(String path, JsonNode body) { return request("POST", path, body); }

    private JsonNode request(String method, String path, JsonNode body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(5)).header("Accept", "application/json");
            if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
            else builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new UpstreamException("upstream returned HTTP " + response.statusCode());
            return response.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(response.body());
        } catch (UpstreamException e) {
            throw e;
        } catch (Exception e) {
            throw new UpstreamException(e.getMessage() == null ? "request failed" : e.getMessage());
        }
    }
}

@Component
class CoordinatorClient extends JsonUpstreamClient {
    CoordinatorClient(@Value("${aetherstream.coordinator-url}") String url, ObjectMapper mapper) { super(url, mapper); }
    JsonNode health() { return get("/coord/health"); }
    JsonNode state() { return get("/coord/state"); }
    JsonNode stop(String id) { return post("/coord/executions/" + id + "/stop", null); }
    JsonNode chaos(String path, JsonNode body) { return post(path, body); }
}

@Component
class SimulatorClient extends JsonUpstreamClient {
    SimulatorClient(@Value("${aetherstream.simulator-url}") String url, ObjectMapper mapper) { super(url, mapper); }
    JsonNode health() { return get("/health"); }
    JsonNode state() { return get("/state"); }
    JsonNode post(String path, JsonNode body) { return super.post(path, body); }
}

class UpstreamException extends RuntimeException {
    UpstreamException(String message) { super(message); }
}
