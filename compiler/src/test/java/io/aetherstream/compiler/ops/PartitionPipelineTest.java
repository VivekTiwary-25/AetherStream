package io.aetherstream.compiler.ops;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aetherstream.common.Alert;
import io.aetherstream.common.DeadLetter;
import io.aetherstream.common.Json;
import io.aetherstream.common.WindowResult;
import io.aetherstream.common.pipeline.ExecutionPlan;
import io.aetherstream.common.pipeline.PlannedOperator;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import static io.aetherstream.compiler.ops.OpsFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class PartitionPipelineTest {
    @Test void closesAtWatermarkEqualityDeduplicatesAndRejectsLateBeforeUpdatingWatermark() {
        PartitionPipeline pipeline = new PartitionPipeline(demo(), 2);
        ListCollector out = new ListCollector();
        assertThat(pipeline.maxTs()).isEqualTo(Long.MIN_VALUE);
        assertThat(pipeline.watermarks()).containsEntry("speed1m", Long.MIN_VALUE);
        pipeline.process(0, position("first", "truck-007", 1000, 10).toString(), out);
        pipeline.process(1, position("second", "truck-007", 30000, 20).toString(), out);
        pipeline.process(2, position("first", "truck-007", 1000, 100).toString(), out);
        pipeline.process(3, position("advance", "truck-008", 64999, 0).toString(), out);
        assertThat(out.outputs()).isEmpty();
        pipeline.process(4, position("boundary", "truck-008", 65000, 0).toString(), out);
        assertThat(windows(out)).containsExactly(new WindowResult("speed1m", "truck-007", 0, 60000,
                java.util.Map.of("avgSpeedKmh", 15.0, "maxSpeedKmh", 20.0, "count", 2.0), 2));
        assertThat(((ListCollector.WindowOutput) out.outputs().getFirst()).sinkNodeId()).isEqualTo("speedSink");
        pipeline.process(5, position("first", "truck-007", 1000, 10).toString(), out);
        DeadLetter late = deadLetters(out).getFirst();
        assertThat(late.kind()).isEqualTo(DeadLetter.LATE);
        assertThat(late.reason()).isEqualTo("window [0,60000) already closed (watermark 60000)");
        assertThat(late.partition()).isEqualTo(2);
        assertThat(late.offset()).isEqualTo(5);
        assertThat(pipeline.watermarks()).containsEntry("speed1m", 60000L).containsEntry("del1m", 60000L);
        long before = pipeline.maxTs();
        ObjectNode rejected = position("bad", "truck-007", 1000000, -1);
        pipeline.process(6, rejected.toString(), out);
        assertThat(pipeline.maxTs()).isEqualTo(before);
        assertThat(deadLetters(out).getLast().kind()).isEqualTo(DeadLetter.REJECTED);
    }

    @Test void partitionsAdvanceEvenWhenTheEventIsFilteredOutOfAWindowBranch() {
        PartitionPipeline pipeline = new PartitionPipeline(demo(), 0);
        ListCollector out = new ListCollector();
        pipeline.process(0, position("p", "truck-007", 1000, 2).toString(), out);
        pipeline.process(1, delivery("d", "truck-007", 65000, 3).toString(), out);
        assertThat(windows(out)).extracting(WindowResult::nodeId).containsExactly("speed1m");
        pipeline.process(2, delivery("d2", "truck-007", 125000, 2).toString(), out);
        assertThat(windows(out).getLast().values()).containsEntry("parcels", 3.0).containsEntry("deliveries", 1.0);
        assertThat(((ListCollector.WindowOutput) out.outputs().getLast()).sinkNodeId()).isEqualTo("delSink");
    }

    @Test void firingIsOrderedByEndThenKeyAndSinksByNodeId() {
        List<PlannedOperator> nodes = new ArrayList<>(basicWindow("{\"name\":\"n\",\"fn\":\"count\"}").operators());
        nodes.add(op("aSink", "sink", "{\"kind\":\"postgres.windows\"}", "w", "NONE"));
        PartitionPipeline pipeline = new PartitionPipeline(plan(nodes), 0);
        ListCollector out = new ListCollector();
        pipeline.process(0, position("b0", "b", 1000, 1).toString(), out);
        pipeline.process(1, position("a0", "a", 1000, 1).toString(), out);
        pipeline.process(2, position("b1", "b", 61000, 1).toString(), out);
        pipeline.process(3, position("a1", "a", 61000, 1).toString(), out);
        pipeline.process(4, position("flush", "z", 185000, 1).toString(), out);
        assertThat(windows(out)).extracting(WindowResult::key).containsExactly("a", "a", "b", "b", "a", "a", "b", "b");
        assertThat(windows(out)).extracting(WindowResult::windowEnd)
                .containsExactly(60000L, 60000L, 60000L, 60000L, 120000L, 120000L, 120000L, 120000L);
        assertThat(out.outputs().stream().map(o -> ((ListCollector.WindowOutput) o).sinkNodeId()).toList())
                .containsExactly("aSink", "sink", "aSink", "sink", "aSink", "sink", "aSink", "sink");
    }

    @Test void tenConsecutiveWindowsAlertExactlyOnceAndGapRestartsAtOne() {
        PartitionPipeline pipeline = new PartitionPipeline(demo(), 0);
        ListCollector out = new ListCollector();
        for (int i = 0; i <= 13; i++) pipeline.process(i, position("p" + i, "truck-007", i * 60000L + 1000, 0).toString(), out);
        assertThat(alerts(out)).containsExactly(new Alert("stuck|truck-007|540000", "truck-007", "stuck-truck",
                "truck-007 has not moved for 10 min", 600000));
        assertThat(out.outputs().stream().filter(ListCollector.AlertOutput.class::isInstance)
                .map(ListCollector.AlertOutput.class::cast).map(ListCollector.AlertOutput::sinkNodeId).toList())
                .containsExactly("alertSink");

        PartitionPipeline gap = new PartitionPipeline(demo(), 0);
        ListCollector gapOut = new ListCollector();
        for (int i = 0; i < 5; i++) gap.process(i, position("p" + i, "truck-007", i * 60000L + 1000, 0).toString(), gapOut);
        for (int i = 6; i <= 15; i++) gap.process(i, position("p" + i, "truck-007", i * 60000L + 1000, 0).toString(), gapOut);
        assertThat(alerts(gapOut)).isEmpty();
        gap.process(16, position("p16", "truck-007", 965000, 0).toString(), gapOut);
        assertThat(alerts(gapOut)).extracting(Alert::alertId).containsExactly("stuck|truck-007|900000");
    }

    @Test void aFailingWindowResetsTheRuleEvenWithoutAGap() {
        PartitionPipeline pipeline = new PartitionPipeline(demo(), 0);
        ListCollector out = new ListCollector();
        for (int i = 0; i <= 15; i++) {
            pipeline.process(i, position("p" + i, "truck-007", i * 60000L + 1000, i == 5 ? 20 : 0).toString(), out);
        }
        assertThat(alerts(out)).isEmpty();
        pipeline.process(16, position("p16", "truck-007", 965000, 0).toString(), out);
        assertThat(alerts(out)).extracting(Alert::alertId).containsExactly("stuck|truck-007|900000");
    }

    @Test void mapOperationsAreSequentialAndCannotMutateSiblingBranches() {
        List<PlannedOperator> nodes = new ArrayList<>();
        nodes.add(op("src", "source", "{}", null, "EVENT"));
        nodes.add(op("aMap", "map", """
                {"ops":[{"op":"scale","field":"payload.speedKmh","factor":2,"as":"payload.scaled"},
                 {"op":"rename","field":"payload.scaled","as":"payload.renamed"},
                 {"op":"set","as":"payload.fixed","value":5},{"op":"drop","field":"payload.speedKmh"},
                 {"op":"scale","field":"payload.missing","factor":2,"as":"payload.missing2"},
                 {"op":"rename","field":"payload.absent","as":"payload.absent2"}]}
                """, "src", "EVENT"));
        nodes.add(op("aFilter", "filter", """
                {"conditions":[{"field":"payload.renamed","op":"==","value":20},
                 {"field":"payload.fixed","op":">=","value":5.0}]}
                """, "aMap", "EVENT"));
        nodes.add(op("aKey", "keyBy", "{\"field\":\"payload.truckId\"}", "aFilter", "KEYED"));
        String aggregations = """
                {"kind":"tumbling","sizeMs":60000,"outOfOrdernessMs":5000,"aggregations":[
                 {"name":"count","fn":"count"},{"name":"renamed","fn":"avg","field":"payload.renamed"},
                 {"name":"speed","fn":"avg","field":"payload.speedKmh"},{"name":"sum","fn":"sum","field":"payload.fixed"},
                 {"name":"min","fn":"min","field":"payload.fixed"}]}
                """;
        nodes.add(op("aWindow", "window", aggregations, "aKey", "WINDOW_RESULT"));
        nodes.add(op("aSink", "sink", "{\"kind\":\"postgres.windows\"}", "aWindow", "NONE"));
        nodes.add(op("bKey", "keyBy", "{\"field\":\"key\"}", "src", "KEYED"));
        nodes.add(op("bWindow", "window", aggregations, "bKey", "WINDOW_RESULT"));
        nodes.add(op("bSink", "sink", "{\"kind\":\"postgres.windows\"}", "bWindow", "NONE"));
        PartitionPipeline pipeline = new PartitionPipeline(plan(nodes), 0);
        ListCollector out = new ListCollector();
        pipeline.process(0, position("p", "truck-007", 1000, 10).toString(), out);
        pipeline.process(1, position("advance", "truck-008", 65000, 10).toString(), out);
        assertThat(windows(out).get(0).values()).containsExactlyInAnyOrderEntriesOf(
                java.util.Map.of("count", 1.0, "renamed", 20.0, "sum", 5.0, "min", 5.0));
        assertThat(windows(out).get(1).values()).containsExactlyInAnyOrderEntriesOf(java.util.Map.of("count", 1.0, "speed", 10.0));
    }

    @Test void onlyLexicallyFirstLateWindowEmitsEvenWhenDepthFirstRoutingVisitsItLast() {
        List<PlannedOperator> nodes = new ArrayList<>();
        nodes.add(op("src", "source", "{}", null, "EVENT"));
        nodes.add(op("aKey", "keyBy", "{\"field\":\"key\"}", "src", "KEYED"));
        nodes.add(op("bKey", "keyBy", "{\"field\":\"key\"}", "src", "KEYED"));
        nodes.add(op("zWindow", "window", "{\"sizeMs\":60000,\"aggregations\":[{\"name\":\"n\",\"fn\":\"count\"}]}", "aKey", "WINDOW_RESULT"));
        nodes.add(op("aWindow", "window", "{\"sizeMs\":30000,\"aggregations\":[{\"name\":\"n\",\"fn\":\"count\"}]}", "bKey", "WINDOW_RESULT"));
        nodes.add(op("zSink", "sink", "{}", "zWindow", "NONE"));
        nodes.add(op("aSink", "sink", "{}", "aWindow", "NONE"));
        PartitionPipeline pipeline = new PartitionPipeline(plan(nodes), 0);
        ListCollector out = new ListCollector();
        pipeline.process(0, position("advance", "truck-007", 125000, 0).toString(), out);
        pipeline.process(1, position("late", "truck-007", 1000, 0).toString(), out);
        assertThat(deadLetters(out)).hasSize(1);
        assertThat(deadLetters(out).getFirst().reason()).isEqualTo("window [0,30000) already closed (watermark 120000)");
    }

    @Test void snapshotRoundTripsMatchUninterruptedExecutionAcrossFiveThousandRecords() throws Exception {
        ExecutionPlan plan = demo();
        PartitionPipeline a = new PartitionPipeline(plan, 3);
        PartitionPipeline b = new PartitionPipeline(plan, 3);
        ListCollector aOut = new ListCollector();
        ListCollector bOut = new ListCollector();
        SplittableRandom random = new SplittableRandom(42);
        String previous = "";
        for (int i = 0; i < 5000; i++) {
            String key = "truck-00" + (i % 7 + 1);
            long time = Math.max(0, i * 2000L - random.nextInt(5001));
            ObjectNode record = i % 5 == 0 ? delivery("e" + i, key, time, i % 4 + 1)
                    : position("e" + i, key, time, 0);
            if (i % 73 == 0) record.put("eventTime", time - 180000);
            if (i % 97 == 0) ((ObjectNode) record.get("payload")).remove("truckId");
            String raw = i > 0 && i % 31 == 0 ? previous : i % 211 == 0 ? "{" : record.toString();
            a.process(i, raw, aOut);
            b.process(i, raw, bOut);
            previous = raw;
            if (i == 777 || i == 2345 || i == 4321) {
                b = PartitionPipeline.restore(plan, 3, Json.MAPPER.readTree(Json.MAPPER.writeValueAsString(b.snapshot())));
            }
        }
        assertThat(bOut.outputs()).containsExactlyElementsOf(aOut.outputs());
        assertThat(windows(aOut)).isNotEmpty();
        assertThat(alerts(aOut)).isNotEmpty();
        assertThat(deadLetters(aOut)).extracting(DeadLetter::kind).contains(DeadLetter.LATE, DeadLetter.REJECTED);
        assertThat(b.maxTs()).isEqualTo(a.maxTs());
        assertThat(b.watermarks()).isEqualTo(a.watermarks());
        String snapshot = Json.MAPPER.writeValueAsString(a.snapshot());
        assertThat(Json.MAPPER.writeValueAsString(a.snapshot())).isEqualTo(snapshot);
        assertThat(Json.MAPPER.writeValueAsString(b.snapshot())).isEqualTo(snapshot);
        PartitionPipeline restored = PartitionPipeline.restore(plan, 3, Json.MAPPER.readTree(snapshot));
        assertThat(Json.MAPPER.writeValueAsString(restored.snapshot())).isEqualTo(snapshot);
    }

    private static ExecutionPlan basicWindow(String aggregation) {
        return plan(List.of(op("src", "source", "{}", null, "EVENT"),
                op("key", "keyBy", "{\"field\":\"key\"}", "src", "KEYED"),
                op("w", "window", "{\"sizeMs\":60000,\"aggregations\":[" + aggregation + "]}", "key", "WINDOW_RESULT"),
                op("sink", "sink", "{}", "w", "NONE")));
    }

    private static List<WindowResult> windows(ListCollector out) {
        return out.outputs().stream().filter(ListCollector.WindowOutput.class::isInstance)
                .map(ListCollector.WindowOutput.class::cast).map(ListCollector.WindowOutput::result).toList();
    }
    private static List<Alert> alerts(ListCollector out) {
        return out.outputs().stream().filter(ListCollector.AlertOutput.class::isInstance)
                .map(ListCollector.AlertOutput.class::cast).map(ListCollector.AlertOutput::alert).toList();
    }
    private static List<DeadLetter> deadLetters(ListCollector out) {
        return out.outputs().stream().filter(ListCollector.DeadLetterOutput.class::isInstance)
                .map(ListCollector.DeadLetterOutput.class::cast).map(ListCollector.DeadLetterOutput::deadLetter).toList();
    }
}
