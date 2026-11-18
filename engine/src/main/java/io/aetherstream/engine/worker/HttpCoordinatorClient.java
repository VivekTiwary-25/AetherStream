package io.aetherstream.engine.worker;

import io.aetherstream.common.Json;
import io.aetherstream.common.protocol.AckResponse;
import io.aetherstream.common.protocol.CheckpointAck;
import io.aetherstream.common.protocol.HeartbeatResponse;
import io.aetherstream.common.protocol.RegisterRequest;
import io.aetherstream.common.protocol.RegisterResponse;
import io.aetherstream.common.protocol.WorkerStatus;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** JDK HttpClient implementation of the coordinator protocol. */
public final class HttpCoordinatorClient implements CoordinatorClient {
    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public HttpCoordinatorClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public RegisterResponse register(RegisterRequest request) throws Exception {
        return post("/internal/workers/register", request, RegisterResponse.class);
    }

    @Override
    public HeartbeatResponse heartbeat(String workerId, WorkerStatus status) throws Exception {
        String id = URLEncoder.encode(workerId, StandardCharsets.UTF_8);
        return post("/internal/workers/" + id + "/heartbeat", status, HeartbeatResponse.class);
    }

    @Override
    public AckResponse ack(CheckpointAck ack) throws Exception {
        return post("/internal/checkpoints/ack", ack, AckResponse.class);
    }

    private <T> T post(String path, Object body, Class<T> type) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(Json.MAPPER.writeValueAsBytes(body)))
                .build();
        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("POST " + path + " -> HTTP " + resp.statusCode() + ": "
                    + new String(resp.body(), StandardCharsets.UTF_8));
        }
        return Json.MAPPER.readValue(resp.body(), type);
    }
}
