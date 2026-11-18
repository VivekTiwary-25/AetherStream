package io.aetherstream.engine.coordinator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.aetherstream.common.Json;
import io.aetherstream.common.protocol.CheckpointAck;
import io.aetherstream.common.protocol.RegisterRequest;
import io.aetherstream.common.protocol.StartExecutionRequest;
import io.aetherstream.common.protocol.WorkerStatus;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** JDK HttpServer exposing the coordinator endpoints of contract 03 section 2. */
public class CoordinatorHttpServer {
    private static final Logger log = LoggerFactory.getLogger(CoordinatorHttpServer.class);

    private final Coordinator coordinator;
    private final HttpServer server;
    private final ExecutorService executor;

    /** port 0 = pick a free port (tests). */
    public CoordinatorHttpServer(Coordinator coordinator, int port) throws IOException {
        this.coordinator = coordinator;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }

    public void start() {
        server.start();
        log.info("Coordinator HTTP listening on port {}", port());
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            String[] seg = Arrays.stream(ex.getRequestURI().getPath().split("/"))
                    .filter(s -> !s.isEmpty())
                    .toArray(String[]::new);
            if ("GET".equals(method) && Arrays.equals(seg, new String[] {"coord", "metrics"})) {
                sendText(ex, coordinator.metricsText());
                return;
            }
            Object result = route(method, seg, ex);
            send(ex, 200, result);
        } catch (JsonProcessingException e) {
            send(ex, 400, error("BAD_REQUEST", "invalid JSON: " + e.getOriginalMessage()));
        } catch (IllegalArgumentException e) {
            send(ex, 400, error("BAD_REQUEST", e.getMessage()));
        } catch (NoSuchElementException e) {
            send(ex, 404, error("NOT_FOUND", e.getMessage()));
        } catch (IllegalStateException e) {
            send(ex, 409, error("CONFLICT", e.getMessage()));
        } catch (Exception e) {
            log.error("Request {} {} failed", ex.getRequestMethod(), ex.getRequestURI(), e);
            send(ex, 500, error("INTERNAL", String.valueOf(e.getMessage())));
        } finally {
            ex.close();
        }
    }

    private Object route(String method, String[] s, HttpExchange ex) throws IOException {
        boolean get = "GET".equals(method);
        boolean post = "POST".equals(method);
        if (s.length >= 2 && s[0].equals("internal")) {
            if (post && s.length == 3 && s[1].equals("workers") && s[2].equals("register")) {
                return coordinator.register(read(ex, RegisterRequest.class));
            }
            if (post && s.length == 4 && s[1].equals("workers") && s[3].equals("heartbeat")) {
                return coordinator.heartbeat(s[2], read(ex, WorkerStatus.class));
            }
            if (post && s.length == 3 && s[1].equals("checkpoints") && s[2].equals("ack")) {
                return coordinator.ack(read(ex, CheckpointAck.class));
            }
        }
        if (s.length >= 2 && s[0].equals("coord")) {
            if (get && s.length == 2 && s[1].equals("health")) {
                return Map.of("status", "UP");
            }
            if (get && s.length == 2 && s[1].equals("state")) {
                return coordinator.clusterState();
            }
            if (post && s.length == 2 && s[1].equals("executions")) {
                return coordinator.startExecution(read(ex, StartExecutionRequest.class));
            }
            if (get && s.length == 3 && s[1].equals("executions")) {
                return coordinator.executionInfo(s[2]);
            }
            if (post && s.length == 4 && s[1].equals("executions") && s[3].equals("rebalance")) {
                return Map.of("checkpointId", coordinator.requestRebalance(s[2]));
            }
            if (post && s.length == 4 && s[1].equals("executions") && s[3].equals("stop")) {
                return coordinator.stopExecution(s[2]);
            }
            if (post && s.length == 4 && s[1].equals("executions") && s[3].equals("checkpoint")) {
                return Map.of("checkpointId", coordinator.triggerCheckpointNow(s[2]));
            }
            if (post && s.length == 3 && s[1].equals("chaos") && s[2].equals("kill")) {
                JsonNode body = readTree(ex);
                JsonNode id = body.get("workerId");
                String workerId = id == null || id.isNull() ? null : id.asText();
                return Map.of("workerId", coordinator.killWorker(workerId));
            }
            if (post && s.length == 3 && s[1].equals("chaos") && s[2].equals("sink-delay")) {
                JsonNode delay = readTree(ex).get("delayMs");
                if (delay == null || !delay.canConvertToLong()) {
                    throw new IllegalArgumentException("delayMs (number) is required");
                }
                return Map.of("delayMs", coordinator.setSinkDelay(delay.asLong()));
            }
        }
        throw new NoSuchElementException("no route " + method + " " + ex.getRequestURI().getPath());
    }

    private static <T> T read(HttpExchange ex, Class<T> type) throws IOException {
        byte[] body = ex.getRequestBody().readAllBytes();
        if (body.length == 0) {
            throw new IllegalArgumentException("request body is required");
        }
        return Json.MAPPER.readValue(body, type);
    }

    private static JsonNode readTree(HttpExchange ex) throws IOException {
        byte[] body = ex.getRequestBody().readAllBytes();
        if (body.length == 0) {
            return Json.MAPPER.createObjectNode();
        }
        JsonNode node = Json.MAPPER.readTree(body);
        return node == null || !node.isObject() ? Json.MAPPER.createObjectNode() : node;
    }

    private static Map<String, Object> error(String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", code);
        m.put("message", message);
        return m;
    }

    private static void send(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Json.MAPPER.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
    private static void sendText(HttpExchange ex, String body) throws IOException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
