package io.aetherstream.api.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Coordinator operations kept separate from the general-purpose upstream clients. */
@Component
public class OpsCoordinatorClient {
    private final String baseUrl;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public OpsCoordinatorClient(@Value("${aetherstream.coordinator-url:${COORDINATOR_URL:http://coordinator:8081}}") String url,
                                ObjectMapper mapper) {
        this.baseUrl = url.replaceAll("/+$", "");
        this.mapper = mapper;
    }

    public JsonNode savepoint(String id) {
        // The coordinator can spend 20 seconds waiting for an aligned checkpoint.
        return request("POST", executionPath(id) + "/savepoint", null, Duration.ofSeconds(30));
    }

    public JsonNode savepoints() {
        return request("GET", "/coord/savepoints", null, Duration.ofSeconds(5));
    }

    public JsonNode stop(String id) {
        return request("POST", executionPath(id) + "/stop", null, Duration.ofSeconds(5));
    }

    public JsonNode execution(String id) {
        return request("GET", executionPath(id), null, Duration.ofSeconds(5));
    }

    public JsonNode restore(String executionId, JsonNode plan, String savepointId) {
        ObjectNode body = mapper.createObjectNode().put("executionId", executionId);
        body.set("plan", plan);
        body.put("fromSavepoint", savepointId);
        return request("POST", "/coord/executions", body, Duration.ofSeconds(30));
    }

    public JsonNode rebalance(String id) {
        return request("POST", executionPath(id) + "/rebalance", null, Duration.ofSeconds(5));
    }

    private static String executionPath(String id) {
        return "/coord/executions/" + URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private JsonNode request(String method, String path, JsonNode body, Duration timeout) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(timeout).header("Accept", "application/json");
            if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
            else request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ResponseException(response.statusCode(), response.body());
            }
            return mapper.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamDownException(e);
        } catch (IOException e) {
            throw new UpstreamDownException(e);
        }
    }

    /** Retains the original coordinator error body, including compatibility explanations. */
    public static class ResponseException extends RuntimeException {
        private final int status;
        private final String body;

        public ResponseException(int status, String body) {
            super("coordinator returned HTTP " + status);
            this.status = status;
            this.body = body;
        }

        public int status() { return status; }
        public String body() { return body; }
    }

    public static class UpstreamDownException extends RuntimeException {
        public UpstreamDownException(Throwable cause) { super("coordinator is not reachable", cause); }
    }
}
