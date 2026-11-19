package io.aetherstream.api.glorious;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PipelineValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final PipelineValidator validator = new PipelineValidator();

    @Test void savedExamplesAreValid() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("schemas/examples"))) root = root.getParent();
        assertThat(root).as("repository schemas/examples directory").isNotNull();
        for (String name : new String[]{"speeding", "avg-speed-30s", "partition-2"}) {
            JsonNode example = mapper.readTree(root.resolve("schemas/examples/" + name + ".json").toFile());
            assertThat(example.path("prompt").asText()).isNotBlank();
            assertThat(validator.validate(example.path("pipeline")).errors()).as(name).isEmpty();
        }
    }

    @Test void acceptsRootsBranchesStringsAndAllNumericOperators() throws Exception {
        for (String op : new String[]{">", ">=", "<", "<=", "==", "!="}) {
            assertThat(validator.validate(pipeline("filter", "{\"field\":\"speedKmh\",\"op\":\"" + op + "\",\"value\":60}")).valid()).isTrue();
        }
        assertThat(validator.validate(pipeline("filter", "{\"field\":\"status\",\"op\":\"==\",\"value\":\"IDLE\"}")).valid()).isTrue();
        assertThat(validator.validate(json("{\"name\":\"roots\",\"nodes\":[{\"id\":\"a\",\"type\":\"output\",\"params\":{\"name\":\"A\"}},{\"id\":\"b\",\"type\":\"output\",\"params\":{\"name\":\"B\"}}],\"edges\":[]}")).valid()).isTrue();
    }

    @Test void rejectsStructuralErrorsAndUnknownKeys() throws Exception {
        invalid(null, "object"); invalid(json("[]"), "object"); invalid(json("{}"), "missing required");
        ObjectNode p = pipeline("output", "{\"name\":\"ok\"}");
        p.put("extra", true); invalid(p, "unknown property"); p.remove("extra");
        p.put("name", ""); invalid(p, "name must"); p.put("name", "x".repeat(61)); invalid(p, "name must");
        p.put("name", "ok"); p.put("nodes", false); invalid(p, "nodes must");
        p.set("nodes", mapper.createArrayNode()); invalid(p, "nodes must");
        for (int i = 0; i < 13; i++) p.withArray("nodes").add(mapper.createObjectNode()); invalid(p, "nodes must");
        p = pipeline("output", "{\"name\":\"ok\"}"); p.put("edges", false); invalid(p, "edges must");
        p = pipeline("output", "{\"name\":\"ok\"}"); node(p).put("extra", true); invalid(p, "unknown property");
        p = pipeline("output", "{\"name\":\"ok\"}"); node(p).put("id", ""); invalid(p, "id must");
        p = pipeline("unknown", "{}"); invalid(p, "unknown node type");
        p = pipeline("output", "{}"); invalid(p, "missing required");
        p = pipeline("output", "{\"name\":null}"); invalid(p, "name must");
        p = pipeline("output", "{\"name\":\"ok\",\"extra\":1}"); invalid(p, "unknown property");
        p = pipeline("output", "{}"); node(p).putNull("params"); invalid(p, "object");
    }

    @Test void rejectsInvalidParameters() throws Exception {
        invalid(pipeline("filter", "{\"field\":\"bogus\",\"op\":\"==\",\"value\":1}"), "known truck field");
        invalid(pipeline("filter", "{\"field\":\"speedKmh\",\"op\":\"contains\",\"value\":1}"), "op must");
        invalid(pipeline("filter", "{\"field\":\"speedKmh\",\"op\":\"==\",\"value\":\"1\"}"), "finite number");
        ObjectNode p = pipeline("filter", "{\"field\":\"speedKmh\",\"op\":\"==\",\"value\":1}");
        ((ObjectNode) node(p).get("params")).put("value", Double.NaN); invalid(p, "finite number");
        ((ObjectNode) node(p).get("params")).put("value", Double.POSITIVE_INFINITY); invalid(p, "finite number");
        invalid(pipeline("filter", "{\"field\":\"truckId\",\"op\":\">\",\"value\":1}"), "string fields support");
        invalid(pipeline("filter", "{\"field\":\"truckId\",\"op\":\"==\",\"value\":1}"), "value must be a string");
        invalid(pipeline("thresholdAlert", "{\"field\":\"speedKmh\",\"op\":\">\",\"value\":1,\"message\":\"" + "m".repeat(121) + "\"}"), "message must");
        invalid(pipeline("windowAverage", "{\"field\":\"truckId\",\"windowSec\":30}"), "numeric");
        for (String window : new String[]{"4", "301", "5.5", "null", "\"30\""}) invalid(pipeline("windowAverage", "{\"field\":\"speedKmh\",\"windowSec\":" + window + "}"), "windowSec");
        invalid(pipeline("windowAverage", "{\"field\":\"speedKmh\",\"windowSec\":30,\"groupBy\":\"x\"}"), "groupBy");
        for (String fields : new String[]{"[]", "null", "[\"bogus\"]", "[1]", "[\"x\",\"x\"]"}) invalid(pipeline("pickFields", "{\"fields\":" + fields + "}"), "fields must");
    }

    @Test void rejectsGraphErrors() throws Exception {
        ObjectNode p = pipeline("filter", "{\"field\":\"x\",\"op\":\">\",\"value\":0}");
        p.withArray("edges").removeAll(); invalid(p, "every path");
        p.withArray("nodes").remove(1); invalid(p, "contain an output");
        p = pipeline("output", "{\"name\":\"A\"}"); invalid(p, "output must be terminal");
        p = pipeline("pickFields", "{\"fields\":[\"x\"]}");
        p.withArray("edges").add(json("{\"from\":\"o\",\"to\":\"n\"}")); invalid(p, "acyclic");
        p = pipeline("pickFields", "{\"fields\":[\"x\"]}");
        p.withArray("edges").add(json("{\"from\":\"n\",\"to\":\"o\"}")); invalid(p, "duplicate edge"); invalid(p, "at most one incoming");
        p = pipeline("output", "{\"name\":\"A\"}"); p.withArray("edges").add(json("{\"from\":\"missing\",\"to\":\"o\"}")); invalid(p, "existing node");
        p = pipeline("output", "{\"name\":\"A\"}"); p.withArray("edges").add(json("{\"from\":\"n\",\"to\":\"o\",\"extra\":1}")); invalid(p, "unknown property");
        p = pipeline("output", "{\"name\":\"A\"}"); ((ObjectNode) p.withArray("nodes").get(1)).put("id", "n"); invalid(p, "duplicate node id");
    }

    @Test void tracksProjectionAndAverageAvailabilityWithNodeErrors() throws Exception {
        ObjectNode p = pipeline("pickFields", "{\"fields\":[\"x\"]}");
        appendStage(p, "filter", "{\"field\":\"speedKmh\",\"op\":\">\",\"value\":60}");
        invalid(p, "unavailable");
        assertThat(validator.validate(p).errors()).anySatisfy(e -> { assertThat(e.nodeId()).isEqualTo("s"); assertThat(e.message()).contains("unavailable"); });
        p = pipeline("pickFields", "{\"fields\":[\"x\"]}"); appendStage(p, "filter", "{\"field\":\"truckId\",\"op\":\"==\",\"value\":\"t\"}"); assertThat(validator.validate(p).valid()).isTrue();
        p = pipeline("pickFields", "{\"fields\":[\"speedKmh\"]}"); appendStage(p, "windowAverage", "{\"field\":\"speedKmh\",\"windowSec\":30}"); invalid(p, "eventTime");
        p = pipeline("windowAverage", "{\"field\":\"speedKmh\",\"windowSec\":30,\"groupBy\":\"truckId\"}"); appendStage(p, "thresholdAlert", "{\"field\":\"speedKmh\",\"op\":\">\",\"value\":60,\"message\":\"Fast\"}"); assertThat(validator.validate(p).valid()).isTrue();
        p = pipeline("windowAverage", "{\"field\":\"speedKmh\",\"windowSec\":30}"); appendStage(p, "windowAverage", "{\"field\":\"speedKmh\",\"windowSec\":30}"); invalid(p, "eventTime");
    }

    @Test void acceptsLimitsAndFanOutAndRetainsAlertInputFields() throws Exception {
        for (String window : new String[]{"5", "300", "5.0"}) {
            assertThat(validator.validate(pipeline("windowAverage", "{\"field\":\"speedKmh\",\"windowSec\":" + window + "}")).valid()).isTrue();
        }
        ObjectNode p = pipeline("thresholdAlert", "{\"field\":\"speedKmh\",\"op\":\">\",\"value\":60,\"message\":\"" + "m".repeat(120) + "\"}");
        p.put("name", "n".repeat(60));
        appendStage(p, "filter", "{\"field\":\"routeId\",\"op\":\"==\",\"value\":\"r\"}");
        assertThat(validator.validate(p).valid()).isTrue();
        p.withArray("edges").add(json("{\"from\":\"n\",\"to\":\"o\"}"));
        invalid(p, "at most one incoming");
        p.withArray("edges").removeAll();
        p.withArray("edges").add(json("{\"from\":\"n\",\"to\":\"o\"}"));
        p.withArray("edges").add(json("{\"from\":\"n\",\"to\":\"s\"}"));
        ((ObjectNode) p.withArray("nodes").get(2)).put("type", "output").set("params", json("{\"name\":\"Branch\"}"));
        assertThat(validator.validate(p).valid()).isTrue();
    }

    private void appendStage(ObjectNode p, String type, String params) throws Exception {
        p.withArray("nodes").add(json("{\"id\":\"s\",\"type\":\"" + type + "\",\"params\":" + params + "}"));
        p.set("edges", json("[{\"from\":\"n\",\"to\":\"s\"},{\"from\":\"s\",\"to\":\"o\"}]"));
    }
    private ObjectNode pipeline(String type, String params) throws Exception {
        return (ObjectNode) json("{\"name\":\"Test\",\"nodes\":[{\"id\":\"n\",\"type\":\"" + type + "\",\"params\":" + params + "},{\"id\":\"o\",\"type\":\"output\",\"params\":{\"name\":\"Rows\"}}],\"edges\":[{\"from\":\"n\",\"to\":\"o\"}]}");
    }
    private ObjectNode node(ObjectNode p) { return (ObjectNode) p.path("nodes").get(0); }
    private JsonNode json(String text) throws Exception { return mapper.readTree(text); }
    private void invalid(JsonNode p, String message) {
        PipelineValidator.Validation result = validator.validate(p);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anySatisfy(e -> assertThat(e.message()).contains(message));
    }
}
