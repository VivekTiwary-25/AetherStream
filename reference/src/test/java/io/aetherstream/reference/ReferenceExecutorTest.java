package io.aetherstream.reference;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aetherstream.common.DeadLetter;
import io.aetherstream.common.Json;
import io.aetherstream.common.WindowResult;
import io.aetherstream.common.pipeline.ExecutionPlan;
import io.aetherstream.compiler.PipelineCompiler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReferenceExecutorTest {
    private static final long START = 1_795_251_600_000L;
    private static final ExecutionPlan PLAN = new PipelineCompiler().plan(PipelineCompiler.demoPipeline(), 4);

    @Test
    void replayIsDeterministicInOffsetOrderWithChaosAndAnIdlePartition() {
        Map<Integer, List<RawRecord>> records = cityRecords();
        ReferenceExecutor executor = new ReferenceExecutor();
        ReferenceResult first = executor.run(PLAN, records);
        Map<Integer, List<RawRecord>> reversed = new LinkedHashMap<>();
        for (int partition = 2; partition >= 0; partition--) {
            List<RawRecord> values = new ArrayList<>(records.get(partition));
            Collections.reverse(values);
            reversed.put(partition, values);
        }
        assertThat(executor.run(PLAN, reversed)).isEqualTo(first);
        assertThat(executor.run(PLAN, records)).isEqualTo(first);
        assertThat(first.recordsReplayed()).isEqualTo(records.values().stream().mapToLong(List::size).sum());
        assertThat(first.windowsBySink().get("speedSink")).hasSize(19 * 8);
        assertThat(first.windowsBySink().get("delSink")).hasSize(19 * 8);
        WindowResult stopped = first.windowsBySink().get("speedSink").stream()
                .filter(row -> row.key().equals("truck-007") && row.windowStart() == START + 300_000)
                .findFirst().orElseThrow();
        assertThat(stopped.eventCount()).isEqualTo(30);
        assertThat(stopped.values()).containsEntry("avgSpeedKmh", 0.0).containsEntry("maxSpeedKmh", 0.0);
        assertThat(first.alertsBySink().get("alertSink")).singleElement()
                .satisfies(alert -> assertThat(alert.alertId()).isEqualTo("stuck|truck-007|" + (START + 840_000)));
        assertThat(first.deadLetters()).extracting(DeadLetter::kind)
                .containsExactlyInAnyOrder(DeadLetter.LATE, DeadLetter.REJECTED, DeadLetter.REJECTED);
        assertThat(first.watermarksByPartition().get(0))
                .containsEntry("speed1m", START + 1_197_000).containsEntry("del1m", START + 1_197_000);
        assertThat(first.watermarksByPartition().get(3))
                .containsEntry("speed1m", Long.MIN_VALUE).containsEntry("del1m", Long.MIN_VALUE);
        assertThat(first.keysByPartition().get(3)).isEmpty();
        assertThat(first.keysByPartition().values().stream().flatMap(java.util.Set::stream))
                .doesNotContain("rejected-only");
        assertThat(first.windowsBySink().get("speedSink"))
                .allSatisfy(row -> assertThat(row.windowEnd()).isLessThanOrEqualTo(START + 1_197_000));
    }

    @Test
    void comparesGeneratedCityOutputAtTheReplayFrontier() {
        ReferenceResult reference = new ReferenceExecutor().run(PLAN, cityRecords());
        List<EngineWindowRow> windows = engineWindows(reference);
        List<EngineAlertRow> alerts = reference.alertsBySink().entrySet().stream()
                .flatMap(entry -> entry.getValue().stream().map(alert ->
                        new EngineAlertRow(entry.getKey(), alert.alertId()))).toList();
        ResultComparator comparator = new ResultComparator();
        var equal = comparator.compare(reference, windows, alerts, 12, 7);
        assertThat(equal.match()).isTrue();
        assertThat(equal.matched()).isEqualTo(305);
        assertThat(equal.referenceResults()).isEqualTo(305);
        assertThat(equal.recordsReplayed()).isEqualTo(reference.recordsReplayed());

        List<EngineWindowRow> missing = new ArrayList<>(windows);
        missing.removeFirst();
        assertThat(comparator.compare(reference, missing, alerts, 12, 7).mismatches())
                .singleElement().satisfies(mismatch -> assertThat(mismatch.type()).isEqualTo("MISSING"));

        List<EngineWindowRow> changed = new ArrayList<>(windows);
        EngineWindowRow original = changed.getFirst();
        Map<String, Double> changedValues = new TreeMap<>(original.values());
        String aggregation = changedValues.keySet().iterator().next();
        changedValues.compute(aggregation, (key, value) -> value + 0.1);
        changed.set(0, new EngineWindowRow(original.sinkId(), original.key(), original.windowStart(),
                original.windowEnd(), changedValues, original.eventCount()));
        assertThat(comparator.compare(reference, changed, alerts, 12, 7).mismatches())
                .singleElement().satisfies(mismatch -> assertThat(mismatch.type()).isEqualTo("DIFFERENT"));

        List<EngineWindowRow> extra = new ArrayList<>(windows);
        extra.add(new EngineWindowRow("speedSink", "truck-001", START - 60_000, START, Map.of("count", 1.0), 1));
        assertThat(comparator.compare(reference, extra, alerts, 12, 7).mismatches())
                .singleElement().satisfies(mismatch -> assertThat(mismatch.type()).isEqualTo("EXTRA"));

        List<EngineWindowRow> stillOpen = new ArrayList<>(windows);
        stillOpen.add(new EngineWindowRow("speedSink", "truck-001", START + 1_140_000, START + 1_200_000,
                Map.of("count", 30.0), 30));
        assertThat(comparator.compare(reference, stillOpen, alerts, 12, 7).match()).isTrue();
    }

    @Test
    void emptyInputLeavesEveryPartitionIdleAndRejectsMisBucketedRecords() {
        ReferenceResult empty = new ReferenceExecutor().run(PLAN, Map.of());
        assertThat(empty.recordsReplayed()).isZero();
        assertThat(empty.windowsBySink().values()).allSatisfy(rows -> assertThat(rows).isEmpty());
        assertThat(empty.watermarksByPartition()).hasSize(4);
        assertThat(empty.watermarksByPartition().values()).allSatisfy(watermarks ->
                assertThat(watermarks.values()).containsOnly(Long.MIN_VALUE));
        assertThatThrownBy(() -> new ReferenceExecutor().run(PLAN,
                Map.of(0, List.of(new RawRecord(1, 0, "{}"))))).isInstanceOf(IllegalArgumentException.class);
    }

    private static List<EngineWindowRow> engineWindows(ReferenceResult reference) {
        return reference.windowsBySink().entrySet().stream().flatMap(entry -> entry.getValue().stream()
                .map(row -> new EngineWindowRow(entry.getKey(), row.key(), row.windowStart(), row.windowEnd(),
                        row.values(), row.eventCount()))).toList();
    }

    /** Eight trucks, 20 simulated minutes, deliveries, a stopped truck and deliberate source chaos. */
    private static Map<Integer, List<RawRecord>> cityRecords() {
        Map<Integer, List<RawRecord>> records = new TreeMap<>();
        SplittableRandom[] random = new SplittableRandom[8];
        for (int truck = 0; truck < 8; truck++) random[truck] = new SplittableRandom(42 ^ (truck + 1));
        for (int elapsed = 0; elapsed <= 1_202_000; elapsed += 2_000) {
            for (int truck = 1; truck <= 8; truck++) {
                int partition = (truck - 1) % 3;
                String key = "truck-%03d".formatted(truck);
                double speed = truck == 7 && elapsed >= 300_000 ? 0 : random[truck - 1].nextDouble(20, 50);
                String value = position("s42-" + key + "-" + elapsed, key, START + elapsed, speed).toString();
                add(records, partition, value);
                if (truck == 1 && elapsed % 120_000 == 0) add(records, partition, value);
                if (elapsed % 30_000 == 0) {
                    ObjectNode delivery = envelope("delivery-" + key + "-" + elapsed, key, START + elapsed,
                            "truck.delivery");
                    ObjectNode payload = (ObjectNode) delivery.get("payload");
                    payload.put("stopId", "stop-" + truck).put("parcels", 2);
                    add(records, partition, delivery.toString());
                }
            }
            if (elapsed == 180_000) {
                add(records, 0, position("late", "truck-001", START, 25).toString());
                add(records, 0, position("malformed", "rejected-only", START + elapsed, -1).toString());
                add(records, 0, "not-json");
            }
        }
        return records;
    }

    private static void add(Map<Integer, List<RawRecord>> records, int partition, String value) {
        List<RawRecord> bucket = records.computeIfAbsent(partition, ignored -> new ArrayList<>());
        bucket.add(new RawRecord(partition, bucket.size(), value));
    }

    private static ObjectNode position(String id, String key, long time, double speed) {
        ObjectNode root = envelope(id, key, time, "truck.position");
        ((ObjectNode) root.get("payload")).put("speedKmh", speed).put("heading", 90)
                .put("routeId", "r-1").put("status", speed == 0 ? "STOPPED" : "DRIVING");
        return root;
    }

    private static ObjectNode envelope(String id, String key, long time, String type) {
        ObjectNode root = Json.MAPPER.createObjectNode();
        root.put("eventId", id).put("schemaVersion", 1).put("type", type).put("eventTime", time).put("key", key);
        root.putObject("payload").put("truckId", key).put("x", 100).put("y", 200);
        return root;
    }
}
