package io.aetherstream.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aetherstream.common.Json;
import io.aetherstream.common.pipeline.Edge;
import io.aetherstream.common.pipeline.ExecutionPlan;
import io.aetherstream.common.pipeline.Node;
import io.aetherstream.common.pipeline.PipelineDefinition;
import io.aetherstream.common.pipeline.PlannedOperator;
import io.aetherstream.common.pipeline.ValidationError;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PipelineCompilerTest {
    private final PipelineCompiler compiler = new PipelineCompiler();

    @Test
    void demoHasExactDeterministicPlan() {
        PipelineDefinition demo = PipelineCompiler.demoPipeline();
        var result = compiler.validate(demo);
        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
        ExecutionPlan plan = result.plan();
        assertThat(plan.pipelineId()).isEqualTo("logistics-demo");
        assertThat(plan.pipelineVersion()).isEqualTo(1);
        assertThat(plan.topic()).isEqualTo("truck-events");
        assertThat(plan.partitions()).isEqualTo(6);
        assertThat(plan.startFrom()).isEqualTo("latest");
        assertThat(plan.operators()).extracting(PlannedOperator::id).containsExactly(
                "src", "del", "kb2", "del1m", "delSink", "pos", "kb1", "speed1m", "speedSink", "stuck", "alertSink");
        assertThat(plan.operators()).extracting(PlannedOperator::outputType).containsExactly(
                "EVENT", "EVENT", "KEYED", "WINDOW_RESULT", "NONE", "EVENT", "KEYED", "WINDOW_RESULT", "NONE", "ALERT", "NONE");
        for (PlannedOperator operator : plan.operators()) {
            assertThat(operator.inputs()).containsExactlyElementsOf(demo.edges().stream()
                    .filter(edge -> edge.to().equals(operator.id())).map(Edge::from).sorted().toList());
            assertThat(operator.config()).isEqualTo(demo.nodes().stream()
                    .filter(node -> node.id().equals(operator.id())).findFirst().orElseThrow().config());
        }
        List<Node> reversedNodes = new ArrayList<>(demo.nodes());
        List<Edge> reversedEdges = new ArrayList<>(demo.edges());
        Collections.reverse(reversedNodes);
        Collections.reverse(reversedEdges);
        assertThat(compiler.plan(new PipelineDefinition(demo.id(), demo.version(), demo.name(), reversedNodes, reversedEdges), 6))
                .isEqualTo(plan);
    }

    @Test
    void executionPlanRoundTripsThroughJson() throws Exception {
        ExecutionPlan plan = compiler.plan(PipelineCompiler.demoPipeline(), 3);
        assertThat(Json.MAPPER.readValue(Json.MAPPER.writeValueAsString(plan), ExecutionPlan.class)).isEqualTo(plan);
        assertThat(plan.partitions()).isEqualTo(3);
    }

    @ParameterizedTest(name = "{1} on {2}")
    @MethodSource("errorFixtures")
    void reportsEveryContractErrorCode(String fixture, String code, String nodeId, String message) throws Exception {
        var result = compiler.validate(fixture(fixture));
        assertThat(result.valid()).isFalse();
        assertThat(result.plan()).isNull();
        assertThat(result.errors()).extracting(ValidationError::code, ValidationError::nodeId, ValidationError::message)
                .contains(tuple(code, nodeId, message));
    }

    static Stream<Arguments> errorFixtures() {
        return Stream.of(
                Arguments.of("no-source", "NO_SOURCE", null,
                        "Pipeline needs a Source. Add one Source block and connect it to the pipeline."),
                Arguments.of("multiple-sources", "MULTIPLE_SOURCES", null,
                        "Pipeline has multiple Sources: Source 'other', Source 'src'. Keep exactly one Source block."),
                Arguments.of("no-sink", "NO_SINK", null,
                        "Pipeline needs a Sink. Connect a postgres.windows or postgres.alerts Sink to the appropriate output."),
                Arguments.of("duplicate-node-id", "DUPLICATE_NODE_ID", "kb",
                        "Key-by 'kb' reuses an id. Give each block a unique id and update its edges."),
                Arguments.of("unknown-node-type", "UNKNOWN_NODE_TYPE", "kb",
                        "Node 'kb' has an unknown type. Use source, filter, map, keyBy, window, rule or sink."),
                Arguments.of("edge-unknown-node", "EDGE_UNKNOWN_NODE", "missing",
                        "Edge 'src' -> 'missing' names a missing node. Add the missing block or change the edge to existing node ids."),
                Arguments.of("cycle", "CYCLE", null,
                        "Pipeline contains a cycle blocking Filter 'a', Filter 'b'. Remove backward connections so every path flows from the Source toward a Sink."),
                Arguments.of("multiple-inputs", "MULTIPLE_INPUTS", "kb",
                        "Key-by 'kb' needs exactly one input, but has 2. Connect one upstream block; remove extra incoming edges."),
                Arguments.of("unreachable-node", "UNREACHABLE_NODE", "orphan",
                        "Sink 'orphan' cannot be reached from a Source. Connect it to the Source's downstream graph."),
                Arguments.of("dangling-node", "DANGLING_NODE", "unused",
                        "Filter 'unused' has no output connection. Connect it to a downstream block ending in a Sink."),
                Arguments.of("type-mismatch", "TYPE_MISMATCH", "win",
                        "Window 'win' (One minute speed) needs a Key-by before it. Add a Key-by block between Source 'src' and this Window."),
                Arguments.of("invalid-config", "INVALID_CONFIG", "win",
                        "Window 'win' (One minute speed): invalid sizeMs. To fix it, supply an integer from 1000 through 3600000."),
                Arguments.of("unsupported-key", "UNSUPPORTED_KEY", "kb",
                        "Key-by 'kb': only 'key' or 'payload.truckId' is supported in v1 because it matches the Kafka partition key. Set field to one of these paths."));
    }

    @Test
    void collectsThreeIndependentMistakesAndPlanListsAllOfThem() throws Exception {
        PipelineDefinition pipeline = fixture("three-errors");
        var result = compiler.validate(pipeline);
        assertThat(result.valid()).isFalse();
        assertThat(result.plan()).isNull();
        assertThat(result.errors()).extracting(ValidationError::code, ValidationError::nodeId).containsExactly(
                tuple("INVALID_CONFIG", "src"), tuple("UNSUPPORTED_KEY", "kb"), tuple("INVALID_CONFIG", "win"));
        assertThatThrownBy(() -> compiler.plan(pipeline, 6)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Source 'src'").hasMessageContaining("Key-by 'kb'")
                .hasMessageContaining("Window 'win'").hasMessageContaining("INVALID_CONFIG").hasMessageContaining("UNSUPPORTED_KEY");
    }

    @Test
    void typeErrorNamesIdLabelAndRepair() throws Exception {
        assertThat(compiler.validate(fixture("type-mismatch")).errors()).anySatisfy(error -> {
            assertThat(error.code()).isEqualTo("TYPE_MISMATCH");
            assertThat(error.nodeId()).isEqualTo("win");
            assertThat(error.message()).contains("Window 'win'", "One minute speed", "Key-by", "Add");
        });
    }

    @ParameterizedTest(name = "{0}: {2}")
    @MethodSource("invalidConfigs")
    void rejectsInvalidConfigWithFieldAndNode(String nodeId, String patch, String field) throws Exception {
        var result = compiler.validate(patched(nodeId, patch));
        assertThat(result.valid()).isFalse();
        assertThat(result.plan()).isNull();
        assertThat(result.errors()).anySatisfy(error -> {
            assertThat(error.code()).isEqualTo("INVALID_CONFIG");
            assertThat(error.nodeId()).isEqualTo(nodeId);
            assertThat(error.message()).contains(field, nodeId, "fix");
        });
    }

    static Stream<Arguments> invalidConfigs() {
        return Stream.of(
                Arguments.of("src", "{\"topic\":null}", "topic"),
                Arguments.of("src", "{\"topic\":123}", "topic"),
                Arguments.of("src", "{\"topic\":\" \"}", "topic"),
                Arguments.of("src", "{\"startFrom\":\"middle\"}", "startFrom"),
                Arguments.of("src", "{\"startFrom\":null}", "startFrom"),
                Arguments.of("pos", "{\"conditions\":[]}", "conditions"),
                Arguments.of("pos", "{\"conditions\":{}}", "conditions"),
                Arguments.of("pos", "{\"conditions\":[null]}", "conditions[0]"),
                Arguments.of("pos", "{\"conditions\":[{\"field\":\"payload.a.b\",\"op\":\"==\",\"value\":1}]}", "conditions[0].field"),
                Arguments.of("pos", "{\"conditions\":[{\"field\":\"schemaVersion\",\"op\":\"==\",\"value\":1}]}", "conditions[0].field"),
                Arguments.of("pos", "{\"conditions\":[{\"field\":\"type\",\"op\":\"contains\",\"value\":\"truck\"}]}", "conditions[0].op"),
                Arguments.of("pos", "{\"conditions\":[{\"field\":\"type\",\"op\":\"==\",\"value\":null}]}", "conditions[0].value"),
                Arguments.of("pos", "{\"conditions\":[{\"field\":\"type\",\"op\":\"==\",\"value\":{}}]}", "conditions[0].value"),
                Arguments.of("pos", "{\"conditions\":[{\"field\":\"type\",\"op\":\">\",\"value\":\"truck\"}]}", "conditions[0].op"),
                Arguments.of("pos", "{\"conditions\":[{\"field\":\"payload.ready\",\"op\":\"<=\",\"value\":true}]}", "conditions[0].op"),
                Arguments.of("mapped", "{\"ops\":[]}", "ops"),
                Arguments.of("mapped", "{\"ops\":{}}", "ops"),
                Arguments.of("mapped", "{\"ops\":[null]}", "ops[0]"),
                Arguments.of("mapped", "{\"ops\":[{\"op\":\"delete\"}]}", "ops[0].op"),
                Arguments.of("mapped", "{\"ops\":[{\"op\":\"scale\",\"field\":\"payload.speedKmh\",\"as\":\"payload.speed\"}]}", "ops[0].factor"),
                Arguments.of("mapped", "{\"ops\":[{\"op\":\"scale\",\"field\":\"payload.speedKmh\",\"factor\":\"2\",\"as\":\"payload.speed\"}]}", "ops[0].factor"),
                Arguments.of("mapped", "{\"ops\":[{\"op\":\"scale\",\"factor\":2,\"as\":\"payload.speed\"}]}", "ops[0].field"),
                Arguments.of("mapped", "{\"ops\":[{\"op\":\"rename\",\"field\":\"payload.speed\",\"as\":\"key\"}]}", "ops[0].as"),
                Arguments.of("mapped", "{\"ops\":[{\"op\":\"drop\",\"field\":\"eventId\"}]}", "ops[0].field"),
                Arguments.of("mapped", "{\"ops\":[{\"op\":\"set\",\"as\":\"payload.a.b\",\"value\":1}]}", "ops[0].as"),
                Arguments.of("mapped", "{\"ops\":[{\"op\":\"set\",\"as\":\"payload.flag\"}]}", "ops[0].value"),
                Arguments.of("kb1", "{\"field\":null}", "field"),
                Arguments.of("speed1m", "{\"kind\":\"sliding\"}", "kind"),
                Arguments.of("speed1m", "{\"sizeMs\":999}", "sizeMs"),
                Arguments.of("speed1m", "{\"sizeMs\":3600001}", "sizeMs"),
                Arguments.of("speed1m", "{\"sizeMs\":60000.5}", "sizeMs"),
                Arguments.of("speed1m", "{\"sizeMs\":\"60000\"}", "sizeMs"),
                Arguments.of("speed1m", "{\"outOfOrdernessMs\":-1}", "outOfOrdernessMs"),
                Arguments.of("speed1m", "{\"outOfOrdernessMs\":600001}", "outOfOrdernessMs"),
                Arguments.of("speed1m", "{\"outOfOrdernessMs\":1.5}", "outOfOrdernessMs"),
                Arguments.of("speed1m", "{\"aggregations\":[]}", "aggregations"),
                Arguments.of("speed1m", "{\"aggregations\":[null]}", "aggregations[0]"),
                Arguments.of("speed1m", "{\"aggregations\":[{\"name\":\"\",\"fn\":\"count\"}]}", "aggregations[0].name"),
                Arguments.of("speed1m", "{\"aggregations\":[{\"name\":\"n\",\"fn\":\"median\",\"field\":\"payload.speedKmh\"}]}", "aggregations[0].fn"),
                Arguments.of("speed1m", "{\"aggregations\":[{\"name\":\"n\",\"fn\":\"sum\"}]}", "aggregations[0].field"),
                Arguments.of("speed1m", "{\"aggregations\":[{\"name\":\"n\",\"fn\":\"avg\",\"field\":\"payload.a.b\"}]}", "aggregations[0].field"),
                Arguments.of("speed1m", "{\"aggregations\":[{\"name\":\"n\",\"fn\":\"count\"},{\"name\":\"n\",\"fn\":\"count\"}]}", "aggregations[1].name"),
                Arguments.of("stuck", "{\"ruleId\":\"\"}", "ruleId"),
                Arguments.of("stuck", "{\"condition\":null}", "condition"),
                Arguments.of("stuck", "{\"condition\":{\"field\":\"missing\",\"op\":\"<\",\"value\":1}}", "condition.field"),
                Arguments.of("stuck", "{\"condition\":{\"field\":\"\",\"op\":\"<\",\"value\":1}}", "condition.field"),
                Arguments.of("stuck", "{\"condition\":{\"field\":\"avgSpeedKmh\",\"op\":\"near\",\"value\":1}}", "condition.op"),
                Arguments.of("stuck", "{\"forWindows\":0}", "forWindows"),
                Arguments.of("stuck", "{\"forWindows\":1.5}", "forWindows"),
                Arguments.of("stuck", "{\"forWindows\":2147483648}", "forWindows"),
                Arguments.of("stuck", "{\"message\":\"stuck\"}", "message"),
                Arguments.of("stuck", "{\"message\":\"{key} stuck\"}", "message"),
                Arguments.of("stuck", "{\"message\":123}", "message"),
                Arguments.of("speedSink", "{\"kind\":\"stdout\"}", "kind"));
    }

    @Test
    void acceptsAllMapOperationsFieldPathsAndComparisonOperators() throws Exception {
        PipelineDefinition pipeline = patched("mapped", """
                {"ops":[
                  {"op":"scale","field":"payload.speedKmh","factor":0.5,"as":"payload.speed"},
                  {"op":"rename","field":"payload.speed","as":"payload.velocity"},
                  {"op":"drop","field":"payload.velocity"},
                  {"op":"set","as":"payload.flag","value":true},
                  {"op":"set","as":"payload.optional","value":null}
                ]}
                """);
        List<JsonNode> conditions = new ArrayList<>();
        for (String op : List.of("==", "!=", ">", ">=", "<", "<=")) {
            conditions.add(Json.MAPPER.readTree("{\"field\":\"payload.speedKmh\",\"op\":\"" + op + "\",\"value\":10}"));
        }
        for (String field : List.of("type", "key", "eventTime", "eventId")) {
            conditions.add(Json.MAPPER.readTree("{\"field\":\"" + field + "\",\"op\":\"==\",\"value\":\"test\"}"));
        }
        conditions.add(Json.MAPPER.readTree("{\"field\":\"payload.flag\",\"op\":\"!=\",\"value\":false}"));
        ObjectNode config = Json.MAPPER.createObjectNode();
        config.putArray("conditions").addAll(conditions);
        pipeline = replaceConfig(pipeline, "pos", config);
        var result = compiler.validate(pipeline);
        assertThat(result.errors()).isEmpty();
        assertThat(result.valid()).isTrue();
        assertThat(result.plan().operators()).filteredOn(operator -> operator.id().equals("mapped"))
                .extracting(PlannedOperator::outputType, PlannedOperator::inputs).containsExactly(tuple("EVENT", List.of("src")));
    }

    @ParameterizedTest
    @MethodSource("validBounds")
    void acceptsInclusiveConfigBounds(String patch) throws Exception {
        assertThat(compiler.validate(patched("speed1m", patch)).errors()).isEmpty();
    }

    static Stream<String> validBounds() {
        return Stream.of("{\"sizeMs\":1000}", "{\"sizeMs\":3600000}",
                "{\"outOfOrdernessMs\":0}", "{\"outOfOrdernessMs\":600000}");
    }

    @Test
    void acceptsBothKeysAllAggregationsAndOptionalDefaults() throws Exception {
        PipelineDefinition pipeline = patched("kb1", "{\"field\":\"payload.truckId\"}");
        for (Node node : pipeline.nodes()) {
            if (node.type().equals("source")) {
                ((ObjectNode) node.config()).remove("startFrom");
            }
            if (node.id().equals("speed1m")) {
                ObjectNode config = (ObjectNode) node.config();
                config.remove("outOfOrdernessMs");
                config.withArray("aggregations").add(Json.MAPPER.readTree("{\"name\":\"sum\",\"fn\":\"sum\",\"field\":\"payload.speedKmh\"}"));
                config.withArray("aggregations").add(Json.MAPPER.readTree("{\"name\":\"min\",\"fn\":\"min\",\"field\":\"payload.speedKmh\"}"));
            }
        }
        assertThat(compiler.validate(pipeline).errors()).isEmpty();
        assertThat(compiler.plan(pipeline, 2).startFrom()).isEqualTo("latest");
        assertThat(compiler.plan(patched("src", "{\"startFrom\":\"earliest\"}"), 2).startFrom()).isEqualTo("earliest");
        assertThat(compiler.validate(patched("stuck", "{\"forWindows\":1}")).errors()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("wrongInputs")
    void enforcesTypeFlowForEveryConsumer(String target, String parent) {
        PipelineDefinition demo = PipelineCompiler.demoPipeline();
        List<Edge> edges = new ArrayList<>(demo.edges().stream().filter(edge -> !edge.to().equals(target)).toList());
        edges.add(new Edge(parent, target));
        var result = compiler.validate(new PipelineDefinition(demo.id(), demo.version(), demo.name(), demo.nodes(), edges));
        assertThat(result.plan()).isNull();
        String message = switch (target) {
            case "pos" -> "Filter 'pos' needs event input. Connect it after the Source, a Filter or a Map, before Key-by.";
            case "kb1" -> "Key-by 'kb1' needs event input. Connect it after the Source, a Filter or a Map, before Key-by.";
            case "speed1m" -> "Window 'speed1m' needs a Key-by before it. Add a Key-by block between Filter 'pos' and this Window.";
            case "stuck" -> "Rule 'stuck' needs a Window before it. Connect it to a Window's output.";
            case "speedSink" -> "Sink 'speedSink' needs a Window before it. Connect it to a Window's output.";
            case "alertSink" -> "Sink 'alertSink' of kind postgres.alerts needs a Rule before it. Add a Rule after a Window and connect this Sink to the Rule.";
            case "src" -> "Source 'src' (Truck events) cannot receive input. Remove incoming edges from the Source.";
            default -> throw new AssertionError("Missing type diagnostic for " + target);
        };
        assertThat(result.errors()).contains(new ValidationError(target, "TYPE_MISMATCH", message));
    }

    static Stream<Arguments> wrongInputs() {
        return Stream.of(Arguments.of("pos", "kb2"), Arguments.of("kb1", "del1m"), Arguments.of("speed1m", "pos"),
                Arguments.of("stuck", "kb1"), Arguments.of("speedSink", "stuck"), Arguments.of("alertSink", "speed1m"),
                Arguments.of("src", "del1m"));
    }

    @Test
    void rejectsMapAfterKeyBy() throws Exception {
        PipelineDefinition pipeline = patched("mapped", "{}");
        List<Edge> edges = new ArrayList<>(pipeline.edges().stream().filter(edge -> !edge.to().equals("mapped")).toList());
        edges.add(new Edge("kb2", "mapped"));
        var result = compiler.validate(new PipelineDefinition(pipeline.id(), pipeline.version(), pipeline.name(), pipeline.nodes(), edges));
        assertThat(result.errors()).extracting(ValidationError::code, ValidationError::nodeId).contains(tuple("TYPE_MISMATCH", "mapped"));
    }

    @Test
    void handlesMissingShapesWithoutLosingValidationErrors() throws Exception {
        assertThat(compiler.validate(null).errors()).extracting(ValidationError::code).contains("INVALID_CONFIG");
        assertThat(compiler.validate(new PipelineDefinition("empty", 1, "", null, null)).errors())
                .extracting(ValidationError::code).contains("INVALID_CONFIG", "NO_SOURCE", "NO_SINK");
        PipelineDefinition demo = PipelineCompiler.demoPipeline();
        PipelineDefinition nullConfig = replaceConfig(demo, "src", null);
        assertThat(compiler.validate(nullConfig).errors()).extracting(ValidationError::code, ValidationError::nodeId)
                .contains(tuple("INVALID_CONFIG", "src"));
        List<Node> nodes = new ArrayList<>(demo.nodes());
        nodes.add(null);
        nodes.add(new Node("", "filter", null, null, Json.MAPPER.readTree("{\"conditions\":[]}")));
        List<Edge> edges = Arrays.asList(null, new Edge(null, "pos"), new Edge("src", null));
        var result = compiler.validate(new PipelineDefinition("broken", 1, "", nodes, edges));
        assertThat(result.valid()).isFalse();
        assertThat(result.plan()).isNull();
        assertThat(result.errors()).extracting(ValidationError::code).contains("INVALID_CONFIG", "EDGE_UNKNOWN_NODE", "MULTIPLE_INPUTS");
    }

    @Test
    void rejectsNonPositivePartitionCount() {
        assertThatThrownBy(() -> compiler.plan(PipelineCompiler.demoPipeline(), 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("partitions");
    }

    @ParameterizedTest(name = "{0}: exact {2} diagnostic")
    @MethodSource("exactInvalidConfigs")
    void reportsExactConfigDiagnostics(String nodeId, String patch, String field, String description, String repair) throws Exception {
        var result = compiler.validate(patched(nodeId, patch));
        assertThat(result.valid()).isFalse();
        assertThat(result.plan()).isNull();
        assertThat(result.errors()).containsExactly(new ValidationError(nodeId, "INVALID_CONFIG",
                description + ": invalid " + field + ". To fix it, " + repair + "."));
    }

    static Stream<Arguments> exactInvalidConfigs() {
        return Stream.of(
                Arguments.of("speed1m", "{\"sizeMs\":999}", "sizeMs", "Window 'speed1m'", "supply an integer from 1000 through 3600000"),
                Arguments.of("speed1m", "{\"sizeMs\":3600001}", "sizeMs", "Window 'speed1m'", "supply an integer from 1000 through 3600000"),
                Arguments.of("speed1m", "{\"sizeMs\":null}", "sizeMs", "Window 'speed1m'", "supply an integer from 1000 through 3600000"),
                Arguments.of("speed1m", "{\"sizeMs\":9223372036854775808}", "sizeMs", "Window 'speed1m'", "supply an integer from 1000 through 3600000"),
                Arguments.of("speed1m", "{\"outOfOrdernessMs\":-1}", "outOfOrdernessMs", "Window 'speed1m'", "supply an integer from 0 through 600000"),
                Arguments.of("speed1m", "{\"outOfOrdernessMs\":600001}", "outOfOrdernessMs", "Window 'speed1m'", "supply an integer from 0 through 600000"),
                Arguments.of("speed1m", "{\"outOfOrdernessMs\":null}", "outOfOrdernessMs", "Window 'speed1m'", "supply an integer from 0 through 600000"),
                Arguments.of("speed1m", "{\"outOfOrdernessMs\":\"5000\"}", "outOfOrdernessMs", "Window 'speed1m'", "supply an integer from 0 through 600000"),
                Arguments.of("src", "{\"startFrom\":false}", "startFrom", "Source 'src' (Truck events)", "use 'latest' or 'earliest', or omit it for 'latest'"),
                Arguments.of("mapped", "{\"ops\":[{\"op\":\"scale\",\"field\":\"payload.speedKmh\",\"factor\":1e400,\"as\":\"payload.speed\"}]}",
                        "ops[0].factor", "Map 'mapped'", "supply a finite numeric scale factor"),
                Arguments.of("speed1m", "{\"aggregations\":{}}", "aggregations", "Window 'speed1m'", "supply a non-empty list"),
                Arguments.of("stuck", "{\"forWindows\":null}", "forWindows", "Rule 'stuck'", "supply an integer from 1 through 2147483647"));
    }

    private static PipelineDefinition fixture(String name) throws Exception {
        try (InputStream stream = PipelineCompilerTest.class.getResourceAsStream("/pipelines/" + name + ".json")) {
            assertThat(stream).as("fixture %s", name).isNotNull();
            return Json.MAPPER.readValue(stream, PipelineDefinition.class);
        }
    }

    private static PipelineDefinition patched(String nodeId, String patch) throws Exception {
        PipelineDefinition pipeline = PipelineCompiler.demoPipeline();
        if (nodeId.equals("mapped")) {
            List<Node> nodes = new ArrayList<>(pipeline.nodes());
            nodes.add(new Node("mapped", "map", null, null, Json.MAPPER.readTree("{\"ops\":[{\"op\":\"set\",\"as\":\"payload.flag\",\"value\":true}]}")));
            List<Edge> edges = new ArrayList<>(pipeline.edges().stream()
                    .filter(edge -> !edge.from().equals("src") || !edge.to().equals("pos")).toList());
            edges.add(new Edge("src", "mapped"));
            edges.add(new Edge("mapped", "pos"));
            pipeline = new PipelineDefinition(pipeline.id(), pipeline.version(), pipeline.name(), nodes, edges);
        }
        Node target = pipeline.nodes().stream().filter(node -> node.id().equals(nodeId)).findFirst().orElseThrow();
        ObjectNode config = ((ObjectNode) target.config()).deepCopy();
        config.setAll((ObjectNode) Json.MAPPER.readTree(patch));
        return replaceConfig(pipeline, nodeId, config);
    }

    private static PipelineDefinition replaceConfig(PipelineDefinition pipeline, String id, JsonNode config) {
        List<Node> nodes = pipeline.nodes().stream().map(node -> node.id().equals(id)
                ? new Node(node.id(), node.type(), node.label(), node.position(), config) : node).toList();
        return new PipelineDefinition(pipeline.id(), pipeline.version(), pipeline.name(), nodes, pipeline.edges());
    }
}
