package io.aetherstream.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.aetherstream.common.pipeline.Edge;
import io.aetherstream.common.pipeline.ExecutionPlan;
import io.aetherstream.common.pipeline.PipelineDefinition;
import io.aetherstream.common.pipeline.ValidationError;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class PipelineLibraryTest {
    private final PipelineCompiler compiler = new PipelineCompiler();

    @Test
    void listsEveryExampleWithDemoFirst() {
        assertThat(PipelineLibrary.list()).extracting(PipelineLibrary.Entry::id).containsExactly(
                "logistics-demo", "speeding-alerts", "fleet-activity", "broken-for-demo");
    }

    @Test
    void everyRunnableExampleValidatesAndHasEditorLayout() {
        for (PipelineLibrary.Entry entry : PipelineLibrary.list()) {
            PipelineDefinition pipeline = PipelineLibrary.load(entry.id());
            assertThat(pipeline.id()).isEqualTo(entry.id());
            if (entry.id().equals("broken-for-demo")) {
                continue;
            }

            assertThat(compiler.validate(pipeline).errors()).as(entry.id()).isEmpty();
            if (!entry.id().equals("logistics-demo")) {
                assertEditorLayout(pipeline);
            }
        }
    }

    @Test
    void brokenDemoContainsExactlyTheThreePresenterErrors() {
        PipelineDefinition pipeline = PipelineLibrary.load("broken-for-demo");
        var result = compiler.validate(pipeline);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).extracting(ValidationError::code, ValidationError::nodeId).containsExactly(
                tuple("TYPE_MISMATCH", "alertSink"),
                tuple("DANGLING_NODE", "del"),
                tuple("TYPE_MISMATCH", "speed1m"));
        assertEditorLayout(pipeline);
    }

    @ParameterizedTest(name = "{0} golden plan")
    @MethodSource("runnableExamples")
    void runnableExampleHasGoldenCompilation(String id) {
        PipelineDefinition pipeline = PipelineLibrary.load(id);
        var result = compiler.validate(pipeline);
        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
        ExecutionPlan plan = compiler.plan(pipeline, 6);
        assertThat(result.plan()).isEqualTo(plan);
        assertThat(plan.pipelineId()).isEqualTo(id);
        assertThat(plan.pipelineVersion()).isEqualTo(1);
        assertThat(plan.topic()).isEqualTo("truck-events");
        assertThat(plan.startFrom()).isEqualTo("latest");
        assertThat(plan.partitions()).isEqualTo(6);
        List<String> expected = switch (id) {
            case "logistics-demo" -> List.of("src:EVENT", "del:EVENT", "kb2:KEYED", "del1m:WINDOW_RESULT",
                    "delSink:NONE", "pos:EVENT", "kb1:KEYED", "speed1m:WINDOW_RESULT", "speedSink:NONE", "stuck:ALERT", "alertSink:NONE");
            case "speeding-alerts" -> List.of("src:EVENT", "pos:EVENT", "kb:KEYED", "speed1m:WINDOW_RESULT",
                    "speeding:ALERT", "alertsSink:NONE", "windowsSink:NONE");
            case "fleet-activity" -> List.of("src:EVENT", "scale:EVENT", "kb:KEYED", "activity5m:WINDOW_RESULT", "windowsSink:NONE");
            default -> throw new AssertionError("Add a golden plan for library example " + id);
        };
        assertThat(plan.operators()).extracting(operator -> operator.id() + ":" + operator.outputType())
                .containsExactlyElementsOf(expected);
        assertThat(plan.operators()).allSatisfy(operator -> {
            var node = pipeline.nodes().stream().filter(candidate -> candidate.id().equals(operator.id())).findFirst().orElseThrow();
            assertThat(operator.type()).isEqualTo(node.type());
            assertThat(operator.config()).isEqualTo(node.config());
            assertThat(operator.inputs()).containsExactlyElementsOf(pipeline.edges().stream()
                    .filter(edge -> edge.to().equals(operator.id())).map(Edge::from).sorted().toList());
        });
    }

    static Stream<String> runnableExamples() {
        return PipelineLibrary.list().stream().map(PipelineLibrary.Entry::id)
                .filter(id -> !id.equals("broken-for-demo"));
    }

    @Test
    @Disabled("BUG: all-example no-errors requirement conflicts with intentional broken-for-demo teaching example containing three mistakes")
    void intentionalBrokenExampleAlsoCompilesWithoutErrors() {
        PipelineDefinition pipeline = PipelineLibrary.load("broken-for-demo");
        var result = compiler.validate(pipeline);
        assertThat(result.errors()).isEmpty();
        assertThat(result.valid()).isTrue();
        assertThat(result.plan()).isEqualTo(compiler.plan(pipeline, 6));
    }

    private static void assertEditorLayout(PipelineDefinition pipeline) {
        assertThat(pipeline.nodes()).allSatisfy(node -> {
            assertThat(node.label()).as(node.id() + " label").isNotBlank();
            assertThat(node.position()).as(node.id() + " position").isNotNull();
            assertThat(node.position().x() % 220).as(node.id() + " x position").isZero();
        });
        assertThat(pipeline.edges()).allSatisfy(edge -> {
            double fromX = pipeline.nodes().stream().filter(node -> node.id().equals(edge.from())).findFirst().orElseThrow().position().x();
            double toX = pipeline.nodes().stream().filter(node -> node.id().equals(edge.to())).findFirst().orElseThrow().position().x();
            assertThat(toX - fromX).as(edge.from() + " -> " + edge.to()).isEqualTo(220.0);
        });
    }
}
