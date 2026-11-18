package io.aetherstream.simulator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.aetherstream.common.Json;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Simulator controls; the emitter must submit records without blocking on Kafka. */
public final class HttpApi implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(HttpApi.class);

    public interface Control {
        boolean running();
        void setRunning(boolean running);
        double timeScale();
        void setTimeScale(double timeScale);
    }

    private final Simulation simulation;
    private final Control control;
    private final Consumer<List<Simulation.Emission>> emit;
    private final HttpServer server;
    private final ExecutorService executor;

    public HttpApi(InetSocketAddress address, Simulation simulation, Control control,
                   Consumer<List<Simulation.Emission>> emit) throws IOException {
        this.simulation = Objects.requireNonNull(simulation, "simulation");
        this.control = Objects.requireNonNull(control, "control");
        this.emit = Objects.requireNonNull(emit, "emit");
        server = HttpServer.create(Objects.requireNonNull(address, "address"), 0);
        // A waiting mutation must never prevent a process-health request from being served.
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            Response response;
            try {
                response = route(exchange);
            } catch (ApiException e) {
                response = error(e.status, e.code, e.getMessage());
            } catch (JsonProcessingException e) {
                response = error(400, "BAD_REQUEST", "Body must contain one valid JSON object");
            } catch (IllegalArgumentException e) {
                response = error(400, "BAD_REQUEST", e.getMessage());
            } catch (IOException e) {
                response = error(400, "BAD_REQUEST", "Unable to read request body");
            } catch (RuntimeException e) {
                LOG.error("Simulator HTTP request failed", e);
                response = error(500, "INTERNAL_ERROR", "Request could not be completed");
            }
            if (response.status == 204) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            byte[] bytes = Json.MAPPER.writeValueAsBytes(response.body);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            // HttpServer handles HEAD specially, including error responses for unsupported HEAD.
            boolean head = exchange.getRequestMethod().equals("HEAD");
            exchange.sendResponseHeaders(response.status, head ? -1 : bytes.length);
            if (!head) {
                exchange.getResponseBody().write(bytes);
            }
        }
    }

    private Response route(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        return switch (path) {
            case "/health" -> {
                requireMethod(exchange, "GET");
                yield ok(Json.MAPPER.createObjectNode().put("status", "UP"));
            }
            case "/state" -> {
                requireMethod(exchange, "GET");
                synchronized (simulation) {
                    yield ok(state());
                }
            }
            case "/trucks" -> addTruck(exchange);
            case "/chaos/late", "/chaos/duplicates", "/chaos/malformed" -> chaos(exchange, path);
            case "/control" -> updateControl(exchange);
            default -> {
                String prefix = "/trucks/";
                if (!path.startsWith(prefix) || path.length() == prefix.length()
                        || path.indexOf('/', prefix.length()) >= 0) {
                    throw new ApiException(404, "NOT_FOUND", "Unknown endpoint");
                }
                requireMethod(exchange, "DELETE");
                String truckId = exchange.getRequestURI().getPath().substring(prefix.length());
                synchronized (simulation) {
                    if (!simulation.removeTruck(truckId)) {
                        throw new ApiException(404, "NOT_FOUND", "Unknown truck: " + truckId);
                    }
                }
                yield new Response(204, null);
            }
        };
    }

    private Response addTruck(HttpExchange exchange) throws IOException {
        requireMethod(exchange, "POST");
        ObjectNode body = body(exchange, true);
        boolean hasX = body.has("x");
        boolean hasY = body.has("y");
        if (hasX != hasY) {
            throw badRequest("Coordinates require both x and y");
        }
        Double x = hasX ? coordinate(body.get("x"), "x") : null;
        Double y = hasY ? coordinate(body.get("y"), "y") : null;
        synchronized (simulation) {
            String truckId = simulation.addTruck(x, y);
            return ok(Json.MAPPER.createObjectNode().put("truckId", truckId));
        }
    }

    private Response chaos(HttpExchange exchange, String path) throws IOException {
        requireMethod(exchange, "POST");
        ObjectNode body = body(exchange, false);
        int count = count(body.get("count"));
        long lateByMs = path.equals("/chaos/late") ? lateByMs(body.get("lateByMs")) : 0;
        synchronized (simulation) {
            List<Simulation.Emission> emissions = switch (path) {
                case "/chaos/late" -> simulation.lateEvents(count, lateByMs);
                case "/chaos/duplicates" -> simulation.duplicates(count);
                case "/chaos/malformed" -> simulation.malformed(count);
                default -> throw new IllegalStateException("Unknown chaos route");
            };
            // Submission belongs to the same critical section as generation, preserving record order.
            emit.accept(emissions);
            return ok(Json.MAPPER.createObjectNode().put("sent", emissions.size()));
        }
    }

    private Response updateControl(HttpExchange exchange) throws IOException {
        requireMethod(exchange, "POST");
        ObjectNode body = body(exchange, false);
        JsonNode running = body.get("running");
        JsonNode scale = body.get("timeScale");
        if (running == null && scale == null) {
            throw badRequest("Provide running and/or timeScale");
        }
        if (running != null && !running.isBoolean()) {
            throw badRequest("running must be a boolean");
        }
        double timeScale = 0;
        if (scale != null) {
            if (!scale.isNumber() || !Double.isFinite(scale.doubleValue()) || scale.doubleValue() <= 0) {
                throw badRequest("timeScale must be a finite positive number");
            }
            timeScale = scale.doubleValue();
        }
        // Validate the entire request before applying either change.
        synchronized (simulation) {
            if (scale != null) {
                control.setTimeScale(timeScale);
            }
            if (running != null) {
                control.setRunning(running.booleanValue());
            }
            return ok(state());
        }
    }

    private ObjectNode state() {
        return simulation.state(control.running(), control.timeScale());
    }

    private static ObjectNode body(HttpExchange exchange, boolean optional) throws IOException {
        JsonNode node = Json.MAPPER.reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readTree(exchange.getRequestBody());
        if ((node == null || node.isMissingNode()) && optional) {
            return Json.MAPPER.createObjectNode();
        }
        if (node == null || !node.isObject()) {
            throw badRequest("Body must be a JSON object");
        }
        return (ObjectNode) node;
    }

    private static double coordinate(JsonNode node, String name) {
        if (!node.isNumber() || !Double.isFinite(node.doubleValue())
                || node.doubleValue() < 0 || node.doubleValue() > 2000) {
            throw badRequest(name + " must be a finite number between 0 and 2000");
        }
        return node.doubleValue();
    }

    private static int count(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < 0) {
            throw badRequest("count must be a non-negative integer");
        }
        return node.intValue();
    }

    private static long lateByMs(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < 0) {
            throw badRequest("lateByMs must be a non-negative long integer");
        }
        return node.longValue();
    }

    private static void requireMethod(HttpExchange exchange, String method) {
        if (!exchange.getRequestMethod().equals(method)) {
            exchange.getResponseHeaders().set("Allow", method);
            throw new ApiException(405, "METHOD_NOT_ALLOWED", "Use " + method + " for this endpoint");
        }
    }

    private static ApiException badRequest(String message) {
        return new ApiException(400, "BAD_REQUEST", message);
    }

    private static Response ok(JsonNode body) {
        return new Response(200, body);
    }

    private static Response error(int status, String code, String message) {
        return new Response(status, Json.MAPPER.createObjectNode()
                .put("error", code).put("message", message == null ? "Invalid request" : message));
    }

    private record Response(int status, JsonNode body) {}

    private static final class ApiException extends RuntimeException {
        private final int status;
        private final String code;

        private ApiException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }
}
