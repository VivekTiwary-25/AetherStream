package io.aetherstream.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.aetherstream.common.Json;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpApiTest {
    private Simulation simulation;
    private TestControl control;
    private HttpApi api;
    private HttpClient client;
    private URI base;
    private final List<Simulation.Emission> submitted = new ArrayList<>();

    @BeforeEach
    void start() throws Exception {
        simulation = new Simulation(42, 1, 0, false);
        control = new TestControl();
        api = new HttpApi(new InetSocketAddress("127.0.0.1", 0), simulation, control, emissions -> {
            assertThat(Thread.holdsLock(simulation)).isTrue();
            submitted.addAll(emissions);
        });
        api.start();
        base = URI.create("http://127.0.0.1:" + api.port());
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void stop() {
        if (api != null) {
            api.close();
        }
        if (client != null) {
            client.close();
        }
    }

    @Test
    void healthDoesNotWaitForSimulationAndStateUsesControl() throws Exception {
        synchronized (simulation) {
            HttpResponse<String> health = request("GET", "/health", null);
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(json(health)).isEqualTo(Json.MAPPER.readTree("{\"status\":\"UP\"}"));
            control.running = false;
            control.scale = 3.5;
        }
        HttpResponse<String> response = request("GET", "/state", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json; charset=utf-8");
        JsonNode state = json(response);
        assertThat(state.path("running").booleanValue()).isFalse();
        assertThat(state.path("timeScale").doubleValue()).isEqualTo(3.5);
        assertThat(state.path("simTime").longValue()).isEqualTo(Simulation.START_EPOCH_MS);
        assertThat(state.path("trucks").size()).isEqualTo(1);
        assertThat(state.path("stops").size()).isEqualTo(24);
        assertThat(state.path("depot").path("x").doubleValue()).isEqualTo(1000);
        assertThat(state.path("emitted").longValue()).isZero();
    }

    @Test
    void addsTrucksWithOptionalCoordinatesAndDeletesThem() throws Exception {
        String located = json(success("POST", "/trucks", "{\"x\":503,\"y\":697,\"ignored\":true}"))
                .path("truckId").textValue();
        String defaultLocation = json(success("POST", "/trucks", null)).path("truckId").textValue();
        String emptyObject = json(success("POST", "/trucks", "{}")).path("truckId").textValue();
        String whitespace = json(success("POST", "/trucks", "   ")).path("truckId").textValue();
        assertThat(List.of(located, defaultLocation, emptyObject, whitespace)).doesNotHaveDuplicates();
        assertThat(json(success("GET", "/state", null)).path("trucks").size()).isEqualTo(5);
        HttpResponse<String> deleted = request("DELETE", "/trucks/" + located, null);
        assertThat(deleted.statusCode()).isEqualTo(204);
        assertThat(deleted.body()).isEmpty();
        assertError(request("DELETE", "/trucks/" + located, null), 404);
        assertThat(json(success("GET", "/state", null)).path("trucks").size()).isEqualTo(4);
    }

    @Test
    void changesControlAndReturnsCurrentState() throws Exception {
        JsonNode paused = json(success("POST", "/control",
                "{\"running\":false,\"timeScale\":2.5,\"ignored\":{\"anything\":true}}"));
        assertThat(paused.path("running").booleanValue()).isFalse();
        assertThat(paused.path("timeScale").doubleValue()).isEqualTo(2.5);
        assertThat(paused.path("trucks").isArray()).isTrue();
        JsonNode resumed = json(success("POST", "/control", "{\"running\":true}"));
        assertThat(resumed.path("running").booleanValue()).isTrue();
        assertThat(resumed.path("timeScale").doubleValue()).isEqualTo(2.5);
        JsonNode faster = json(success("POST", "/control", "{\"timeScale\":20}"));
        assertThat(faster.path("running").booleanValue()).isTrue();
        assertThat(faster.path("timeScale").doubleValue()).isEqualTo(20);
    }

    @Test
    void invalidControlNeverPartiallyMutatesState() throws Exception {
        List<String> invalid = List.of(
                "{\"running\":false,\"timeScale\":0}",
                "{\"running\":false,\"timeScale\":-1}",
                "{\"running\":false,\"timeScale\":\"20\"}",
                "{\"running\":false,\"timeScale\":null}",
                "{\"running\":false,\"timeScale\":1e400}",
                "{\"running\":\"false\",\"timeScale\":20}",
                "{\"running\":null,\"timeScale\":20}",
                "{\"running\":0}", "{}", "{\"ignored\":true}",
                "[]", "null", "", "{", "{\"running\":false} {}");
        for (String body : invalid) {
            assertError(request("POST", "/control", body), 400);
            JsonNode state = json(success("GET", "/state", null));
            assertThat(state.path("running").booleanValue()).as(body).isTrue();
            assertThat(state.path("timeScale").doubleValue()).as(body).isEqualTo(10);
        }
    }

    @Test
    void chaosSubmitsActualRecordsUnderTheSimulationMonitor() throws Exception {
        assertThat(json(success("POST", "/chaos/duplicates", "{\"count\":20}"))
                .path("sent").intValue()).isZero();
        List<Simulation.Emission> originals;
        long now;
        synchronized (simulation) {
            originals = List.copyOf(simulation.step(Simulation.START_EPOCH_MS + 2000));
            submitted.addAll(originals);
            now = simulation.now();
        }
        assertThat(originals).isNotEmpty();
        JsonNode duplicateResponse = json(success("POST", "/chaos/duplicates", "{\"count\":10000}"));
        assertThat(duplicateResponse.path("sent").intValue()).isEqualTo(originals.size());
        synchronized (simulation) {
            assertThat(submitted.subList(originals.size(), submitted.size())).containsExactlyElementsOf(originals);
        }
        assertThat(json(success("POST", "/chaos/late", "{\"count\":2,\"lateByMs\":180000,\"ignored\":1}"))
                .path("sent").intValue()).isEqualTo(2);
        synchronized (simulation) {
            for (Simulation.Emission late : submitted.subList(originals.size() * 2, submitted.size())) {
                JsonNode record = Json.MAPPER.readTree(late.json());
                assertThat(record.path("eventTime").longValue()).isEqualTo(now - 180000);
                assertThat(record.path("eventId").textValue()).contains("-late-");
                assertThat(record.path("key").textValue()).isEqualTo(late.key());
            }
        }
        assertThat(json(success("POST", "/chaos/malformed", "{\"count\":3}"))
                .path("sent").intValue()).isEqualTo(3);
        JsonNode state = json(success("GET", "/state", null));
        synchronized (simulation) {
            assertThat(submitted).hasSize(originals.size() * 2 + 5);
            assertThat(state.path("emitted").longValue()).isEqualTo(submitted.size());
        }
    }

    @Test
    void rejectsInvalidChaosFieldsWithoutEmitting() throws Exception {
        List<String> badCounts = List.of("{}", "{\"count\":null}", "{\"count\":\"1\"}",
                "{\"count\":true}", "{\"count\":1.0}", "{\"count\":-1}",
                "{\"count\":2147483648}", "[]", "null", "{", "");
        for (String route : List.of("/chaos/duplicates", "/chaos/malformed")) {
            for (String body : badCounts) {
                assertError(request("POST", route, body), 400);
            }
        }
        for (String value : List.of("null", "\"1\"", "true", "0.5", "-1", "9223372036854775808")) {
            assertError(request("POST", "/chaos/late", "{\"count\":2,\"lateByMs\":" + value + "}"), 400);
        }
        assertError(request("POST", "/chaos/late", "{\"count\":2}"), 400);
        assertError(request("POST", "/chaos/late", "{\"count\":1.5,\"lateByMs\":0}"), 400);
        assertThat(json(success("GET", "/state", null)).path("emitted").longValue()).isZero();
        synchronized (simulation) {
            assertThat(submitted).isEmpty();
        }
    }

    @Test
    void zeroChaosCountsWorkWithoutTrucksButPositiveGenerationRequiresATruck() throws Exception {
        String truckId = json(success("GET", "/state", null)).path("trucks").get(0).path("truckId").textValue();
        assertThat(request("DELETE", "/trucks/" + truckId, null).statusCode()).isEqualTo(204);
        for (String route : List.of("/chaos/late", "/chaos/malformed", "/chaos/duplicates")) {
            assertThat(json(success("POST", route, "{\"count\":0,\"lateByMs\":0}"))
                    .path("sent").intValue()).isZero();
        }
        assertError(request("POST", "/chaos/late", "{\"count\":1,\"lateByMs\":0}"), 400);
        assertError(request("POST", "/chaos/malformed", "{\"count\":1}"), 400);
        assertThat(json(success("GET", "/state", null)).path("emitted").longValue()).isZero();
    }

    @Test
    void invalidTruckBodiesNeverAddATruck() throws Exception {
        for (String body : List.of("[]", "null", "{", "{} {}", "{\"x\":500}",
                "{\"x\":500,\"y\":\"700\"}", "{\"x\":null,\"y\":700}",
                "{\"x\":-1,\"y\":700}", "{\"x\":500,\"y\":2001}", "{\"x\":1e400,\"y\":700}")) {
            assertError(request("POST", "/trucks", body), 400);
        }
        assertThat(json(success("GET", "/state", null)).path("trucks").size()).isEqualTo(1);
    }

    @Test
    void enforcesExactPathsAndMethodsWithJsonErrors() throws Exception {
        for (String route : List.of("/health/", "/healthcheck", "/state/extra", "/trucks/",
                "/trucks/truck-001/extra", "/chaos/late/", "/control/", "/missing", "/%68ealth")) {
            assertError(request("GET", route, null), 404);
        }
        for (String route : List.of("/health", "/state")) {
            HttpResponse<String> response = request("POST", route, "{}");
            assertError(response, 405);
            assertThat(response.headers().firstValue("Allow")).hasValue("GET");
        }
        for (String route : List.of("/trucks", "/chaos/late", "/chaos/duplicates", "/chaos/malformed", "/control")) {
            HttpResponse<String> response = request("GET", route, null);
            assertError(response, 405);
            assertThat(response.headers().firstValue("Allow")).hasValue("POST");
        }
        HttpResponse<String> wrongDelete = request("POST", "/trucks/truck-001", "{}");
        assertError(wrongDelete, 405);
        assertThat(wrongDelete.headers().firstValue("Allow")).hasValue("DELETE");
    }

    private HttpResponse<String> success(String method, String path, String body) throws Exception {
        HttpResponse<String> response = request(method, path, body);
        assertThat(response.statusCode()).as("%s %s: %s", method, path, response.body()).isEqualTo(200);
        return response;
    }

    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest request = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .method(method, publisher).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> response) throws Exception {
        return Json.MAPPER.readTree(response.body());
    }

    private static void assertError(HttpResponse<String> response, int status) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json; charset=utf-8");
        JsonNode error = json(response);
        assertThat(error.path("error").isTextual()).isTrue();
        assertThat(error.path("error").textValue()).isNotBlank();
        assertThat(error.path("message").isTextual()).isTrue();
        assertThat(error.path("message").textValue()).isNotBlank();
    }

    private final class TestControl implements HttpApi.Control {
        private boolean running = true;
        private double scale = 10;

        @Override
        public boolean running() {
            assertThat(Thread.holdsLock(simulation)).isTrue();
            return running;
        }

        @Override
        public void setRunning(boolean running) {
            assertThat(Thread.holdsLock(simulation)).isTrue();
            this.running = running;
        }

        @Override
        public double timeScale() {
            assertThat(Thread.holdsLock(simulation)).isTrue();
            return scale;
        }

        @Override
        public void setTimeScale(double timeScale) {
            assertThat(Thread.holdsLock(simulation)).isTrue();
            scale = timeScale;
        }
    }
}
