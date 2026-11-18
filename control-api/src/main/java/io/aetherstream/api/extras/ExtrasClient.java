package io.aetherstream.api.extras;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ExtrasClient {
    private final String simulatorUrl;
    private final String coordinatorUrl;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public ExtrasClient(@Value("${SIMULATOR_URL:http://simulator:8070}") String simulatorUrl,
                        @Value("${COORDINATOR_URL:http://coordinator:8081}") String coordinatorUrl) {
        this.simulatorUrl = trimTrailingSlash(simulatorUrl);
        this.coordinatorUrl = trimTrailingSlash(coordinatorUrl);
    }

    public HttpResponse<String> scenario(String body) {
        return send(simulatorUrl + "/scenario", "POST", body);
    }

    public String coordinatorMetrics() {
        HttpResponse<String> response = send(coordinatorUrl + "/coord/metrics", "GET", null);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ExtrasUpstreamException("coordinator returned HTTP " + response.statusCode());
        }
        return response.body();
    }

    private HttpResponse<String> send(String url, String method, String body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5));
            if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
            else builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new ExtrasUpstreamException(e.getMessage() == null ? "request failed" : e.getMessage());
        }
    }

    private static String trimTrailingSlash(String url) {
        return url.replaceAll("/$", "");
    }
}

class ExtrasUpstreamException extends RuntimeException {
    ExtrasUpstreamException(String message) { super(message); }
}
