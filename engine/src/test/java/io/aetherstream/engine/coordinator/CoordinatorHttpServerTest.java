package io.aetherstream.engine.coordinator;

import static org.assertj.core.api.Assertions.assertThat;

import io.aetherstream.common.Json;
import io.aetherstream.common.api.ExecutionInfo;
import io.aetherstream.common.pipeline.ExecutionPlan;
import io.aetherstream.common.protocol.ClusterState;
import io.aetherstream.common.protocol.Command;
import io.aetherstream.common.protocol.HeartbeatResponse;
import io.aetherstream.common.protocol.QueueStatus;
import io.aetherstream.common.protocol.RegisterRequest;
import io.aetherstream.common.protocol.RegisterResponse;
import io.aetherstream.common.protocol.StartExecutionRequest;
import io.aetherstream.common.protocol.WorkerStatus;
import io.aetherstream.engine.storage.InMemoryCheckpointStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CoordinatorHttpServerTest {
    private CoordinatorHttpServer server;
    private final HttpClient client = HttpClient.newHttpClient();
    private String base;

    @BeforeEach
    void setUp() throws Exception {
        Coordinator coordinator = new Coordinator(new InMemoryExecutionRepository(), new InMemoryCheckpointStore(),
                (topic, partitions, startFrom) -> Map.of(0, 5L, 1, 6L), Clock.SYSTEM, 10_000);
        server = new CoordinatorHttpServer(coordinator, 0);
        server.start();
        base = "http://localhost:" + server.port();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private HttpResponse<String> post(String path, Object body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.MAPPER.writeValueAsString(body)))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void registerHeartbeatStartAndState() throws Exception {
        HttpResponse<String> health = get("/coord/health");
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("UP");

        HttpResponse<String> reg = post("/internal/workers/register", new RegisterRequest(1, null, "worker-host"));
        assertThat(reg.statusCode()).isEqualTo(200);
        RegisterResponse registered = Json.MAPPER.readValue(reg.body(), RegisterResponse.class);
        assertThat(registered.workerId()).isNotBlank();
        assertThat(registered.heartbeatIntervalMs()).isEqualTo(1000);
        assertThat(registered.heartbeatTimeoutMs()).isEqualTo(4000);
        String id = registered.workerId();

        ExecutionPlan plan = new ExecutionPlan("p", 1, "truck-events", 2, "earliest", List.of());
        HttpResponse<String> started = post("/coord/executions", new StartExecutionRequest("exec-http", plan));
        assertThat(started.statusCode()).isEqualTo(200);
        ExecutionInfo info = Json.MAPPER.readValue(started.body(), ExecutionInfo.class);
        assertThat(info.state()).isEqualTo("RUNNING");
        assertThat(info.generation()).isEqualTo(1);

        WorkerStatus status = new WorkerStatus(1, id, null, 0, "IDLE", List.of(), new QueueStatus(0, 1000),
                new QueueStatus(0, 1000), false, 0, 0.0, 0, Map.of());
        HttpResponse<String> hb = post("/internal/workers/" + id + "/heartbeat", status);
        assertThat(hb.statusCode()).isEqualTo(200);
        assertThat(hb.body()).contains("\"type\":\"ASSIGN\"");
        HeartbeatResponse response = Json.MAPPER.readValue(hb.body(), HeartbeatResponse.class);
        assertThat(response.commands()).hasSize(1);
        Command.Assign assign = (Command.Assign) response.commands().get(0);
        assertThat(assign.partitions()).containsExactly(0, 1);
        assertThat(assign.startOffsets()).isEqualTo(Map.of(0, 5L, 1, 6L));

        HttpResponse<String> stateResp = get("/coord/state");
        assertThat(stateResp.statusCode()).isEqualTo(200);
        ClusterState state = Json.MAPPER.readValue(stateResp.body(), ClusterState.class);
        assertThat(state.execution().id()).isEqualTo("exec-http");
        assertThat(state.workers()).hasSize(1);
        assertThat(state.workers().get(0).alive()).isTrue();
        assertThat(state.workers().get(0).partitions()).containsExactly(0, 1);

        HttpResponse<String> delay = post("/coord/chaos/sink-delay", Map.of("delayMs", 25));
        assertThat(delay.statusCode()).isEqualTo(200);
        assertThat(delay.body()).contains("25");

        assertThat(post("/coord/executions/nope/stop", Map.of()).statusCode()).isEqualTo(404);
        assertThat(get("/no/such/path").statusCode()).isEqualTo(404);
    }
    @Test
    void metricsEndpointReturnsPrometheusText() throws Exception {
        HttpResponse<String> response = get("/coord/metrics");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .startsWith("text/plain; version=0.0.4");
        assertThat(response.body()).contains("aether_workers_alive", "aether_execution_generation",
                "aether_checkpoints_completed_total", "aether_checkpoints_aborted_total",
                "aether_last_checkpoint_duration_ms", "aether_recoveries_total",
                "aether_last_recovery_duration_ms", "aether_worker_records_per_sec",
                "aether_worker_queue_size", "aether_worker_backpressured");
    }
}
