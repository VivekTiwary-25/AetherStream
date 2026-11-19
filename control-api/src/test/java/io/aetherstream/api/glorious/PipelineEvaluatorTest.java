package io.aetherstream.api.glorious;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelineEvaluatorTest {
    private final List<PipelineEvaluator.Emission> results = new ArrayList<>();

    @Test
    void closesGroupedWindowsAtWatermarkAndNeverReopensLateWindows() throws Exception {
        PipelineEvaluator evaluator = evaluator("""
            {"nodes":[
              {"id":"w","type":"windowAverage","params":{"field":"speedKmh","windowSec":5,"groupBy":"truckId"}},
              {"id":"o","type":"output","params":{"name":"averages"}}],
             "edges":[{"from":"w","to":"o"}]}
            """);
        evaluator.accept(row("a", 20, 1_000), 1_000);
        evaluator.accept(row("a", 40, 2_000), 2_000);
        evaluator.accept(row("b", 80, 3_000), 3_000);
        assertThat(results).isEmpty();
        evaluator.accept(row("c", 10, 5_000), 5_000);
        assertThat(results).hasSize(2);
        assertThat(results.get(0).kind()).isEqualTo("average");
        assertThat(results.get(0).output()).isEqualTo("averages");
        assertThat(results.get(0).data()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "group", "a", "field", "speedKmh", "avg", 30.0, "count", 2L,
                "windowStart", 0L, "windowEnd", 5_000L));
        assertThat(results.get(1).data()).containsEntry("group", "b").containsEntry("avg", 80.0);
        evaluator.accept(row("a", 100, 2_000), 5_100);
        evaluator.tick(12_000);
        assertThat(results).hasSize(3);
        assertThat(results.get(2).data()).containsEntry("group", "c");
        evaluator.accept(row("a", 100, 2_000), 1_000);
        evaluator.tick(20_000);
        assertThat(results).hasSize(3);
    }

    @Test
    void closesGlobalWindowAfterTwoSecondGraceWithoutNewRecords() throws Exception {
        PipelineEvaluator evaluator = evaluator(windowPipeline());
        evaluator.accept(row("a", 20, 1_000), 1_000);
        evaluator.accept(row("b", 40, 2_000), 2_000);
        evaluator.tick(6_999);
        assertThat(results).isEmpty();
        evaluator.tick(7_000);
        assertThat(results).singleElement().satisfies(result ->
                assertThat(result.data()).containsEntry("group", "global").containsEntry("avg", 30.0).containsEntry("count", 2L));
        evaluator.tick(8_000);
        assertThat(results).hasSize(1);
    }

    @Test
    void projectionDoesNotMutateSiblingOrCallerAndPreservesWindowTime() throws Exception {
        PipelineEvaluator evaluator = evaluator("""
            {"nodes":[
              {"id":"p","type":"pickFields","params":{"fields":["speedKmh"]}},
              {"id":"projected","type":"output","params":{"name":"projected"}},
              {"id":"raw","type":"output","params":{"name":"raw"}},
              {"id":"w","type":"windowAverage","params":{"field":"speedKmh","windowSec":5}},
              {"id":"avg","type":"output","params":{"name":"avg"}}],
             "edges":[{"from":"p","to":"projected"},{"from":"p","to":"w"},{"from":"w","to":"avg"}]}
            """);
        Map<String, Object> original = new LinkedHashMap<>(row("a", 42, 1_000));
        evaluator.accept(original, 1_000);
        original.put("speedKmh", 999);
        assertThat(results.get(0).data()).containsOnlyKeys("truckId", "speedKmh");
        assertThat(results.get(1).data()).containsEntry("eventTime", 1_000L).containsEntry("speedKmh", 42.0);
        assertThatThrownBy(() -> results.get(0).data().put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
        evaluator.tick(7_000);
        assertThat(results.get(2).data()).containsEntry("avg", 42.0);
    }

    @Test
    void alertsPassThroughAndDeduplicateByNodeTruckAndWallTime() throws Exception {
        PipelineEvaluator evaluator = evaluator("""
            {"nodes":[
              {"id":"a","type":"thresholdAlert","params":{"field":"speedKmh","op":">","value":60,"message":"fast"}},
              {"id":"o","type":"output","params":{"name":"alerts"}}],
             "edges":[{"from":"a","to":"o"}]}
            """);
        evaluator.accept(row("a", 70, 1_000), 1_000);
        evaluator.accept(row("a", 80, 100_000), 1_001);
        evaluator.accept(row("b", 90, 100_000), 1_001);
        evaluator.accept(row("a", 90, 100_001), 10_999);
        evaluator.accept(row("a", 90, 100_002), 11_000);
        assertThat(results.stream().filter(e -> e.kind().equals("row"))).hasSize(5);
        var alerts = results.stream().filter(e -> e.kind().equals("alert")).toList();
        assertThat(alerts).hasSize(3);
        assertThat(alerts.get(0).data()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "truckId", "a", "field", "speedKmh", "value", 70.0, "message", "fast"));
    }

    @Test
    void thresholdResolvesAverageFieldAndGroupedTruckIdentity() throws Exception {
        PipelineEvaluator evaluator = evaluator("""
            {"nodes":[
              {"id":"w","type":"windowAverage","params":{"field":"speedKmh","windowSec":5,"groupBy":"truckId"}},
              {"id":"a","type":"thresholdAlert","params":{"field":"speedKmh","op":">=","value":60,"message":"fast average"}},
              {"id":"o","type":"output","params":{"name":"averages"}}],
             "edges":[{"from":"w","to":"a"},{"from":"a","to":"o"}]}
            """);
        evaluator.accept(row("a", 80, 1_000), 1_000);
        evaluator.accept(row("b", 70, 1_000), 1_000);
        evaluator.tick(7_000);
        assertThat(results).hasSize(4);
        assertThat(results.stream().filter(e -> e.kind().equals("alert")).map(e -> e.data().get("truckId")))
                .containsExactly("a", "b");
        assertThat(results.get(1).data()).containsEntry("value", 80.0);
    }

    @Test
    void rateLimitsRollingSecondsIndependentlyPerOutputAndCountsDrops() throws Exception {
        PipelineEvaluator evaluator = evaluator("""
            {"nodes":[{"id":"a","type":"output","params":{"name":"a"}},
                      {"id":"b","type":"output","params":{"name":"b"}}],"edges":[]}
            """);
        for (int i = 0; i < 21; i++) evaluator.accept(row("a", 10, i), 999);
        assertThat(results).hasSize(40);
        assertThat(evaluator.droppedRows()).isEqualTo(2);
        evaluator.accept(row("a", 10, 1_000), 1_000);
        assertThat(results).hasSize(40);
        assertThat(evaluator.droppedRows()).isEqualTo(4);
        evaluator.accept(row("a", 10, 1_999), 1_999);
        assertThat(results).hasSize(42);
    }

    @Test
    void filtersNumericAndStringFieldsWithoutTreatingMissingValuesAsUnequal() throws Exception {
        PipelineEvaluator evaluator = evaluator("""
            {"nodes":[
              {"id":"f","type":"filter","params":{"field":"speedKmh","op":">","value":60}},
              {"id":"s","type":"filter","params":{"field":"truckId","op":"!=","value":"b"}},
              {"id":"o","type":"output","params":{"name":"filtered"}}],
             "edges":[{"from":"f","to":"s"},{"from":"s","to":"o"}]}
            """);
        evaluator.accept(row("a", 60, 1_000), 1_000);
        evaluator.accept(row("b", 70, 1_001), 1_001);
        evaluator.accept(row("a", 70, 1_002), 1_002);
        assertThat(results).singleElement().satisfies(result -> assertThat(result.data()).containsEntry("truckId", "a"));
    }

    @Test
    void propagatesSinkFailuresForRunOwner() throws Exception {
        PipelineEvaluator evaluator = new PipelineEvaluator(new ObjectMapper().readTree("""
            {"nodes":[{"id":"o","type":"output","params":{"name":"out"}}],"edges":[]}
            """), emission -> { throw new IllegalStateException("buffer unavailable"); });
        assertThatThrownBy(() -> evaluator.accept(row("a", 10, 1_000), 1_000))
                .isInstanceOf(IllegalStateException.class).hasMessage("buffer unavailable");
    }

    private PipelineEvaluator evaluator(String json) throws Exception {
        return new PipelineEvaluator(new ObjectMapper().readTree(json), results::add);
    }

    private static Map<String, Object> row(String truck, double speed, long time) {
        return Map.of("truckId", truck, "speedKmh", speed, "eventTime", time, "partition", 2);
    }

    private static String windowPipeline() {
        return """
            {"nodes":[
              {"id":"w","type":"windowAverage","params":{"field":"speedKmh","windowSec":5}},
              {"id":"o","type":"output","params":{"name":"out"}}],
             "edges":[{"from":"w","to":"o"}]}
            """;
    }
}
