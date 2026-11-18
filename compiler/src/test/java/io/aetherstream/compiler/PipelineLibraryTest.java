package io.aetherstream.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.aetherstream.common.pipeline.PipelineDefinition;
import io.aetherstream.common.pipeline.ValidationError;
import org.junit.jupiter.api.Test;

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
