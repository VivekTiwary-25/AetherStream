package io.aetherstream.api.glorious;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
        evaluator.tick(12_100);
        assertThat(results).hasSize(3);
        assertThat(results.get(2).data()).containsEntry("group", "c");
        evaluator.accept(row("a", 100, 2_000), 1_000);
        evaluator.tick(20_000);
        assertThat(results).hasSize(3);
    }

    @Test
    void closesGlobalWindowAfterFullIdleWindowAndTwoSecondGrace() throws Exception {
        PipelineEvaluator evaluator = evaluator(windowPipeline());
        evaluator.accept(row("a", 20, 1_000), 1_000);
        evaluator.accept(row("b", 40, 2_000), 2_000);
        evaluator.tick(8_999);
        assertThat(results).isEmpty();
        evaluator.tick(9_000);
        assertThat(results).singleElement().satisfies(result ->
                assertThat(result.data()).containsEntry("group", "global").containsEntry("avg", 30.0).containsEntry("count", 2L));
        evaluator.tick(10_000);
        assertThat(results).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1_000})
    void repeatedOrOlderSourceTimestampsDoNotCountAsIdle(long eventTime) throws Exception {
        PipelineEvaluator evaluator = evaluator(windowPipeline());
        evaluator.accept(row("a", 20, 1_000), 1_000);
        for (long wall = 2_000; wall <= 10_000; wall += 1_000) {
            evaluator.accept(row("a", 40, eventTime), wall);
            evaluator.tick(wall + 999);
        }
        assertThat(results).isEmpty();
        evaluator.tick(16_999);
        assertThat(results).isEmpty();
        evaluator.tick(17_000);
        assertThat(results).singleElement().satisfies(result ->
                assertThat(result.data()).containsEntry("count", 10L).containsEntry("avg", 38.0));
    }

    @ParameterizedTest(name = "real simulator flow with {0} ms consumer stall")
    @ValueSource(longs = {0, 10_000})
    void preservesRealSimulatorWindowsDuringFlowStallsAndPauses(long stallMs) throws Exception {
        // First 40 real truck-002 rows from design/ui-glorious/proof/standby-utkarsh/pipelines/
        // partition-2-results.json: {wall ts the row was observed, speedKmh}.
        double[][] real = {
            {1795151394714L, 34.672611800177364},
            {1795151394909L, 35.744393751091174},
            {1795151395111L, 41.44446990389692},
            {1795151395310L, 45.810939619448},
            {1795151395510L, 48.251277824031256},
            {1795151395710L, 48.43433334318509},
            {1795151395910L, 46.33526574698646},
            {1795151396312L, 36.701154680492074},
            {1795151396510L, 0.0},
            {1795151396712L, 0.0},
            {1795151396912L, 32.66600181356878},
            {1795151397112L, 38.1747256548612},
            {1795151397313L, 42.706868399787915},
            {1795151397510L, 45.69357157502329},
            {1795151397712L, 46.75995479745428},
            {1795151397912L, 45.77216942946431},
            {1795151398112L, 42.85419878491463},
            {1795151398311L, 38.372296183589945},
            {1795151398513L, 32.88901413428705},
            {1795151398712L, 36.217459076221665},
            {1795151398908L, 42.28525466579911},
            {1795151399111L, 46.94846569183548},
            {1795151399313L, 49.54059003197381},
            {1795151399513L, 49.69114126291706},
            {1795151399713L, 47.37860144040176},
            {1795151399914L, 42.933496609922265},
            {1795151400113L, 36.99115547246857},
            {1795151400312L, 31.207910474067027},
            {1795151400513L, 36.461602919815654},
            {1795151400712L, 40.89215900930147},
            {1795151400912L, 43.97510805847822},
            {1795151401113L, 45.34550346379457},
            {1795151401313L, 44.841123553495656},
            {1795151401511L, 42.52167471008308},
            {1795151401709L, 38.661723578678824},
            {1795151401912L, 33.71819501688191},
            {1795151402113L, 30.187484415752053},
            {1795151402314L, 35.309445842755586},
            {1795151402511L, 39.709769973585864},
            {1795151402909L, 44.47247895481307},
        };
        // The simulator stamps eventTime on its own clock: START_EPOCH_MS + (wall - SIM_ANCHOR_EPOCH_MS) * 10,
        // one position every 2000 ms of simulated time. For this demo that is ~7.8 days behind the wall clock.
        long firstWall = (long) real[0][0];
        long firstEvent = 1_795_251_600_000L + (firstWall - 1_795_219_200_000L) * 10;
        assertThat(firstWall - firstEvent).isGreaterThan(24L * 3_600_000);
        PipelineEvaluator evaluator = validatedEvaluator(new ObjectMapper().readTree(new java.io.File(
                examplesDir(), "avg-speed-30s.json")).path("pipeline").toString());
        long firstResultWall = -1;
        for (int i = 0; i < real.length; i++) {
            if (i == 10) {
                long lastArrival = (long) real[i - 1][0];
                for (long elapsed = 1_000; elapsed <= stallMs; elapsed += 1_000) {
                    evaluator.tick(lastArrival + elapsed);
                }
                assertThat(results).as("no partial window emitted during stall").isEmpty();
            }
            long wall = (long) real[i][0] + (i >= 10 ? stallMs : 0);
            evaluator.accept(row("truck-002", real[i][1], firstEvent + 2_000L * i), wall);
            evaluator.tick(wall);
            if (firstResultWall < 0 && !results.isEmpty()) firstResultWall = wall;
        }
        assertThat(firstResultWall).as("first window result").isPositive();
        assertThat(firstResultWall - firstWall).isLessThan(35_000);

        long windowStart = Math.floorDiv(firstEvent, 30_000L) * 30_000L;
        int inFirst = (int) ((windowStart + 30_000L - firstEvent + 1_999) / 2_000);
        double expected = 0;
        for (int i = 0; i < inFirst; i++) expected += real[i][1];
        PipelineEvaluator.Emission first = results.get(0);
        assertThat(first.kind()).isEqualTo("average");
        assertThat(first.output()).isEqualTo("30-second average speed");
        assertThat(first.data()).containsEntry("group", "truck-002").containsEntry("count", (long) inFirst)
                .containsEntry("windowStart", windowStart).containsEntry("windowEnd", windowStart + 30_000L);
        assertThat((Double) first.data().get("avg")).isCloseTo(expected / inFirst, org.assertj.core.data.Offset.offset(1e-9));

        // With no further events, the last open window still closes from wall-clock idle time.
        int before = results.size();
        long lastWall = (long) real[real.length - 1][0] + stallMs;
        long lastEvent = firstEvent + 2_000L * (real.length - 1);
        long lastEnd = Math.floorDiv(lastEvent, 30_000L) * 30_000L + 30_000L;
        evaluator.tick(lastWall + 31_999);
        assertThat(results).hasSize(before);
        evaluator.tick(lastWall + 32_000);
        assertThat(results).hasSize(before + 1);
        assertThat(results.get(results.size() - 1).data()).containsEntry("windowEnd", lastEnd);
        long total = results.stream().mapToLong(e -> (Long) e.data().get("count")).sum();
        assertThat(total).as("no real row dropped").isEqualTo(real.length);
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
        evaluator.tick(8_000);
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
        evaluator.tick(8_000);
        assertThat(results).hasSize(4);
        assertThat(results.stream().filter(e -> e.kind().equals("alert")).map(e -> e.data().get("truckId")))
                .containsExactly("a", "b");
        assertThat(results.get(1).data()).containsEntry("value", 80.0);
    }

    @Test
    void projectedGroupedAveragesKeepIndependentAlertCooldowns() throws Exception {
        PipelineEvaluator evaluator = validatedEvaluator("""
            {"name":"projected route alerts","nodes":[
              {"id":"w","type":"windowAverage","params":{"field":"speedKmh","windowSec":5,"groupBy":"routeId"}},
              {"id":"p","type":"pickFields","params":{"fields":["speedKmh"]}},
              {"id":"a","type":"thresholdAlert","params":{"field":"speedKmh","op":">=","value":60,"message":"fast route"}},
              {"id":"o","type":"output","params":{"name":"alerts"}}],
             "edges":[{"from":"w","to":"p"},{"from":"p","to":"a"},{"from":"a","to":"o"}]}
            """);
        long base = 1_700_000_000_000L;
        for (int window = 0; window < 3; window++) {
            long eventTime = base + window * 5_000L + 1_000;
            evaluator.accept(Map.of("truckId", "a", "routeId", "east", "speedKmh", 80.0,
                    "eventTime", eventTime), eventTime);
            evaluator.accept(Map.of("truckId", "b", "routeId", "west", "speedKmh", 70.0,
                    "eventTime", eventTime), eventTime);
            evaluator.tick(base + window * 5_000L + 8_000);
            assertThat(results.stream().filter(e -> e.kind().equals("alert")))
                    .hasSize(window < 2 ? 2 : 4);
        }
        assertThat(results.stream().filter(e -> e.kind().equals("average"))).hasSize(6);
        assertThat(results.stream().filter(e -> e.kind().equals("alert")).map(e -> e.data().get("value")))
                .containsExactly(80.0, 70.0, 80.0, 70.0);
    }

    @Test
    void idleClosureIncludesEveryUpstreamWindowBeforeFinalizingDownstream() throws Exception {
        PipelineEvaluator evaluator = validatedEvaluator("""
            {"name":"averaged event times","nodes":[
              {"id":"first","type":"windowAverage","params":{"field":"eventTime","windowSec":5}},
              {"id":"second","type":"windowAverage","params":{"field":"eventTime","windowSec":10}},
              {"id":"o","type":"output","params":{"name":"averages"}}],
             "edges":[{"from":"first","to":"second"},{"from":"second","to":"o"}]}
            """);
        long base = 1_700_000_000_000L;
        evaluator.accept(row("a", 20, base + 1_000), base + 1_000);
        evaluator.accept(row("a", 40, base + 2_000), base + 2_000);
        evaluator.accept(row("b", 60, base + 6_000), base + 6_000);
        evaluator.accept(row("b", 80, base + 7_000), base + 7_000);
        evaluator.tick(base + 18_999);
        assertThat(results).isEmpty();
        evaluator.tick(base + 19_000);
        assertThat(results).singleElement().satisfies(result ->
                assertThat(result.data()).containsExactlyInAnyOrderEntriesOf(Map.of(
                        "group", "global", "field", "eventTime", "avg", base + 4_000.0,
                        "count", 2L, "windowStart", base, "windowEnd", base + 10_000)));
        evaluator.accept(row("a", 100, base + 6_000), base + 19_001);
        evaluator.tick(base + 30_000);
        assertThat(results).hasSize(1);
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

    private PipelineEvaluator validatedEvaluator(String json) throws Exception {
        var pipeline = new ObjectMapper().readTree(json);
        assertThat(new PipelineValidator().validate(pipeline).errors()).isEmpty();
        return new PipelineEvaluator(pipeline, results::add);
    }

    private static java.io.File examplesDir() {
        java.nio.file.Path root = java.nio.file.Path.of("").toAbsolutePath();
        while (root != null && !java.nio.file.Files.isDirectory(root.resolve("schemas/examples"))) root = root.getParent();
        assertThat(root).as("repository schemas/examples directory").isNotNull();
        return root.resolve("schemas/examples").toFile();
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
