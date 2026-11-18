package io.aetherstream.reference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.aetherstream.common.Alert;
import io.aetherstream.common.WindowResult;
import io.aetherstream.common.api.CompareResult;
import io.aetherstream.common.api.CompareResult.Mismatch;
import io.aetherstream.common.pipeline.Edge;
import io.aetherstream.common.pipeline.ExecutionPlan;
import io.aetherstream.common.pipeline.Node;
import io.aetherstream.common.pipeline.PipelineDefinition;
import io.aetherstream.compiler.PipelineCompiler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ResultComparatorTest {
    private final ResultComparator comparator = new ResultComparator();
    private final ExecutionPlan plan = new PipelineCompiler().plan(PipelineCompiler.demoPipeline(), 3);

    @Test
    void matchesEmptyResults() {
        CompareResult result = compare(reference(Map.of(), Map.of()), List.of(), List.of());

        assertThat(result.match()).isTrue();
        assertThat(result.referenceResults()).isZero();
        assertThat(result.engineResults()).isZero();
        assertThat(result.matched()).isZero();
        assertThat(result.mismatches()).isEmpty();
    }

    @Test
    void countsWindowsAndAlertsAndPreservesReplayMetadata() {
        ReferenceResult ref = reference(
                Map.of("speedSink", List.of(window("truck", 0, Map.of("count", 2.0), 2))),
                Map.of("alertSink", List.of(alert("rule|truck|0"))));

        CompareResult result = compare(ref,
                List.of(row("speedSink", "truck", 0, 60_000, Map.of("count", 2.0), 2)),
                List.of(new EngineAlertRow("alertSink", "rule|truck|0")));

        assertThat(result.match()).isTrue();
        assertThat(result.referenceResults()).isEqualTo(2);
        assertThat(result.engineResults()).isEqualTo(2);
        assertThat(result.matched()).isEqualTo(2);
        assertThat(result.mismatches()).isEmpty();
        assertThat(result.checkpointId()).isEqualTo(17);
        assertThat(result.durationMs()).isEqualTo(23);
        assertThat(result.recordsReplayed()).isEqualTo(123);
    }

    @Test
    void reportsMissingDifferentAndClosedExtraWindows() {
        ReferenceResult ref = reference(Map.of("speedSink", List.of(
                window("missing", 0, Map.of("count", 1.0), 1),
                window("different", 0, Map.of("count", 1.0), 1))), Map.of());

        CompareResult result = compare(ref, List.of(
                row("speedSink", "different", 0, 60_000, Map.of("count", 1.0), 2),
                row("speedSink", "truck", 0, 60_000, Map.of("count", 1.0), 1)), List.of());

        assertThat(result.match()).isFalse();
        assertThat(result.referenceResults()).isEqualTo(2);
        assertThat(result.engineResults()).isEqualTo(2);
        assertThat(result.matched()).isZero();
        assertThat(result.mismatches()).extracting(Mismatch::type, Mismatch::sinkId, Mismatch::key)
                .containsExactly(tuple("DIFFERENT", "speedSink", "different"),
                        tuple("MISSING", "speedSink", "missing"), tuple("EXTRA", "speedSink", "truck"));
        assertThat(result.mismatches().getFirst().detail()).contains("eventCount");
    }

    @ParameterizedTest
    @MethodSource("aggregationCases")
    void comparesExactAggregationShapeAndAbsoluteTolerance(Map<String, Double> values, boolean matches) {
        ReferenceResult ref = reference(Map.of("speedSink", List.of(
                window("truck", 0, Map.of("a", 0.0, "b", 2.0), 1))), Map.of());

        CompareResult result = compare(ref,
                List.of(row("speedSink", "truck", 0, 60_000, values, 1)), List.of());

        assertThat(result.match()).isEqualTo(matches);
        assertThat(result.matched()).isEqualTo(matches ? 1 : 0);
        if (matches) {
            assertThat(result.mismatches()).isEmpty();
        } else {
            assertThat(result.mismatches()).extracting(Mismatch::type).containsExactly("DIFFERENT");
        }
    }

    static Stream<Arguments> aggregationCases() {
        return Stream.of(
                Arguments.of(Map.of("a", 0.0, "b", 2.0), true),
                Arguments.of(Map.of("a", 0.999e-6, "b", 2.0), true),
                Arguments.of(Map.of("a", 1e-6, "b", 2.0), true),
                Arguments.of(Map.of("a", -1e-6, "b", 2.0), true),
                Arguments.of(Map.of("a", Math.nextUp(1e-6), "b", 2.0), false),
                Arguments.of(Map.of("a", -Math.nextUp(1e-6), "b", 2.0), false),
                Arguments.of(Map.of("a", 0.0, "b", 2.00001), false),
                Arguments.of(Map.of("a", 0.0), false),
                Arguments.of(Map.of("a", 0.0, "b", 2.0, "c", 3.0), false),
                Arguments.of(Map.of("a", 0.0, "c", 2.0), false),
                Arguments.of(Map.<String, Double>of(), false));
    }

    @Test
    void matchesOmittedAggregationsWhenBothShapesAreEmpty() {
        ReferenceResult ref = reference(Map.of("speedSink", List.of(window("truck", 0, Map.of(), 1))), Map.of());

        CompareResult result = compare(ref,
                List.of(row("speedSink", "truck", 0, 60_000, Map.of(), 1)), List.of());

        assertThat(result.match()).isTrue();
        assertThat(result.matched()).isEqualTo(1);
    }

    @Test
    void identifiesWindowsBySinkKeyAndStartNotWindowEnd() {
        ReferenceResult ref = reference(Map.of(
                "speedSink", List.of(window("truck", 0, Map.of(), 1)),
                "delSink", List.of(new WindowResult("del1m", "truck", 0, 60_000, Map.of(), 1))), Map.of());

        CompareResult result = compare(ref, List.of(
                row("speedSink", "truck", 0, 60_001, Map.of(), 1),
                row("speedSink", "truck", 60_000, 120_000, Map.of(), 1)), List.of());

        assertThat(result.matched()).isEqualTo(1);
        assertThat(result.mismatches()).extracting(Mismatch::type, Mismatch::sinkId, Mismatch::windowStart)
                .containsExactly(tuple("MISSING", "delSink", 0L), tuple("EXTRA", "speedSink", 60_000L));
    }

    @Test
    void ignoresOpenAndUnseenEngineWindowsButStillCountsRawRows() {
        CompareResult result = compare(reference(Map.of(), Map.of()), List.of(
                row("speedSink", "truck", 120_000, 180_000, Map.of(), 1),
                row("speedSink", "unseen", 0, 60_000, Map.of(), 1)), List.of());

        assertThat(result.match()).isTrue();
        assertThat(result.mismatches()).isEmpty();
        assertThat(result.referenceResults()).isZero();
        assertThat(result.engineResults()).isEqualTo(2);
        assertThat(result.matched()).isZero();
    }

    @Test
    void usesTheKeyPartitionAndEachSinksOwnWindowWatermarkInclusively() {
        ReferenceResult ref = new ReferenceResult(plan, Map.of(), Map.of(), List.of(),
                Map.of(0, Map.of("speed1m", 120_000L, "del1m", 60_000L),
                        1, Map.of("speed1m", 60_000L, "del1m", 120_000L),
                        2, Map.of("speed1m", Long.MIN_VALUE, "del1m", Long.MIN_VALUE)),
                Map.of(0, Set.of("fast", "both"), 1, Set.of("slow", "both"), 2, Set.of("idle")), 123);

        CompareResult result = compare(ref, List.of(
                row("speedSink", "fast", 60_000, 120_000, Map.of(), 1),
                row("delSink", "fast", 60_000, 120_000, Map.of(), 1),
                row("speedSink", "slow", 60_000, 120_000, Map.of(), 1),
                row("delSink", "slow", 60_000, 120_000, Map.of(), 1),
                row("speedSink", "slow", 0, 60_000, Map.of(), 1),
                row("speedSink", "fast", 120_000, 120_001, Map.of(), 1),
                row("speedSink", "both", 60_000, 120_000, Map.of(), 1),
                row("delSink", "both", 60_000, 120_000, Map.of(), 1),
                row("speedSink", "idle", -60_000, 0, Map.of(), 1)), List.of());

        assertThat(result.engineResults()).isEqualTo(9);
        assertThat(result.mismatches()).extracting(Mismatch::type, Mismatch::sinkId, Mismatch::key, Mismatch::windowStart)
                .containsExactly(tuple("EXTRA", "delSink", "both", 60_000L),
                        tuple("EXTRA", "delSink", "slow", 60_000L),
                        tuple("EXTRA", "speedSink", "both", 60_000L),
                        tuple("EXTRA", "speedSink", "fast", 60_000L),
                        tuple("EXTRA", "speedSink", "slow", 0L));
    }

    @Test
    void resolvesWindowNodeFromCompiledSinkInputRatherThanNames() {
        PipelineDefinition demo = PipelineCompiler.demoPipeline();
        Map<String, String> names = Map.of("speed1m", "renamed-window", "speedSink", "renamed-sink");
        List<Node> nodes = demo.nodes().stream().map(node -> new Node(names.getOrDefault(node.id(), node.id()),
                node.type(), node.label(), node.position(), node.config())).toList();
        List<Edge> edges = demo.edges().stream().map(edge -> new Edge(names.getOrDefault(edge.from(), edge.from()),
                names.getOrDefault(edge.to(), edge.to()))).toList();
        ExecutionPlan renamedPlan = new PipelineCompiler().plan(
                new PipelineDefinition(demo.id(), demo.version(), demo.name(), nodes, edges), 1);
        ReferenceResult ref = new ReferenceResult(renamedPlan, Map.of(), Map.of(), List.of(),
                Map.of(0, Map.of("renamed-window", 60_000L, "del1m", 0L)), Map.of(0, Set.of("truck")), 0);

        CompareResult result = compare(ref,
                List.of(row("renamed-sink", "truck", 0, 60_000, Map.of(), 1)), List.of());

        assertThat(result.mismatches()).extracting(Mismatch::type, Mismatch::sinkId)
                .containsExactly(tuple("EXTRA", "renamed-sink"));
    }

    @Test
    void comparesAlertsBySinkAndIdAndIncludesIdInMismatchDetails() {
        ReferenceResult ref = reference(Map.of(), Map.of("alertSink", List.of(alert("same"), alert("missing"))));

        CompareResult result = compare(ref, List.of(), List.of(
                new EngineAlertRow("alertSink", "same"), new EngineAlertRow("alertSink", "extra"),
                new EngineAlertRow("otherAlertSink", "missing")));

        assertThat(result.match()).isFalse();
        assertThat(result.referenceResults()).isEqualTo(2);
        assertThat(result.engineResults()).isEqualTo(3);
        assertThat(result.matched()).isEqualTo(1);
        assertThat(result.mismatches()).extracting(Mismatch::type, Mismatch::sinkId)
                .containsExactly(tuple("MISSING", "alertSink"), tuple("EXTRA", "alertSink"),
                        tuple("EXTRA", "otherAlertSink"));
        assertThat(result.mismatches().get(0).detail()).contains("missing");
        assertThat(result.mismatches().get(1).detail()).contains("extra");
        assertThat(result.mismatches().get(2).detail()).contains("missing");
    }

    @Test
    void capsSamplesWithoutTruncatingCountsAndIsIndependentOfInputOrdering() {
        List<WindowResult> windows = new ArrayList<>();
        for (int i = 59; i >= 0; i--) {
            windows.add(window("missing-" + String.format("%02d", i), 0, Map.of(), 1));
        }
        windows.add(window("truck", 0, Map.of(), 1));
        ReferenceResult ref = reference(Map.of("speedSink", windows),
                Map.of("alertSink", List.of(alert("same"), alert("missing"))));
        List<EngineWindowRow> engineRows = new ArrayList<>(List.of(
                row("speedSink", "truck", 0, 60_000, Map.of(), 1),
                row("speedSink", "truck", 60_000, 120_000, Map.of(), 1),
                row("speedSink", "truck", 120_000, 180_000, Map.of(), 1),
                row("speedSink", "unseen", 0, 60_000, Map.of(), 1)));
        List<EngineAlertRow> engineAlerts = new ArrayList<>(List.of(
                new EngineAlertRow("alertSink", "same"), new EngineAlertRow("alertSink", "extra")));

        CompareResult result = compare(ref, engineRows, engineAlerts);

        assertThat(result.match()).isFalse();
        assertThat(result.referenceResults()).isEqualTo(63);
        assertThat(result.engineResults()).isEqualTo(6);
        assertThat(result.matched()).isEqualTo(2);
        assertThat(result.mismatches()).hasSize(50);
        assertThat(result.mismatches()).allSatisfy(mismatch -> assertThat(mismatch.type()).isEqualTo("MISSING"));
        assertThat(result.mismatches().getFirst().key()).isEqualTo("missing-00");
        assertThat(result.mismatches().getLast().key()).isEqualTo("missing-49");

        Collections.reverse(windows);
        Collections.reverse(engineRows);
        Collections.reverse(engineAlerts);
        assertThat(compare(reference(Map.of("speedSink", windows),
                Map.of("alertSink", List.of(alert("missing"), alert("same")))), engineRows, engineAlerts))
                .isEqualTo(result);
    }

    private ReferenceResult reference(Map<String, List<WindowResult>> windows, Map<String, List<Alert>> alerts) {
        return new ReferenceResult(plan, windows, alerts, List.of(),
                Map.of(0, Map.of("speed1m", 120_000L, "del1m", 120_000L),
                        1, Map.of("speed1m", Long.MIN_VALUE, "del1m", Long.MIN_VALUE),
                        2, Map.of("speed1m", Long.MIN_VALUE, "del1m", Long.MIN_VALUE)),
                Map.of(0, Set.of("truck"), 1, Set.of(), 2, Set.of()), 123);
    }

    private CompareResult compare(ReferenceResult ref, List<EngineWindowRow> windows, List<EngineAlertRow> alerts) {
        return comparator.compare(ref, windows, alerts, 17, 23);
    }

    private static WindowResult window(String key, long start, Map<String, Double> values, long eventCount) {
        return new WindowResult("speed1m", key, start, start + 60_000, values, eventCount);
    }

    private static EngineWindowRow row(String sink, String key, long start, long end,
                                       Map<String, Double> values, long eventCount) {
        return new EngineWindowRow(sink, key, start, end, values, eventCount);
    }

    private static Alert alert(String id) {
        return new Alert(id, "truck", "stuck-truck", "Stopped", 60_000);
    }
}
