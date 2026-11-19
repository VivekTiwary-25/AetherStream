package io.aetherstream.reference;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aetherstream.common.Alert;
import io.aetherstream.common.DeadLetter;
import io.aetherstream.common.Json;
import io.aetherstream.common.WindowResult;
import io.aetherstream.common.pipeline.ExecutionPlan;
import io.aetherstream.common.pipeline.Node;
import io.aetherstream.common.pipeline.PipelineDefinition;
import io.aetherstream.compiler.PipelineCompiler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.Set;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

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

    @ParameterizedTest
    @MethodSource("interleavingSeeds")
    void partitionPreservingInterleavingsHaveIdenticalOutputsAndFrontiers(long seed) {
        ExecutionPlan plan = semanticsPlan("key", 0);
        Map<Integer, List<RawRecord>> partitions = new TreeMap<>();
        for (int partition = 0; partition < 2; partition++) {
            String stopped = "stopped-" + partition;
            String moving = "moving-" + partition;
            String first = position("first-" + partition, stopped, START, 0).toString();
            add(partitions, partition, first);
            add(partitions, partition, first);
            add(partitions, partition, position("moving-first-" + partition, moving, START + 1, 30).toString());
            add(partitions, partition, position("second-" + partition, stopped, START + 60_000, 0).toString());
            add(partitions, partition, position("moving-second-" + partition, moving, START + 60_001, 40).toString());
            add(partitions, partition, position("clock-" + partition, stopped, START + 120_000, 0).toString());
            add(partitions, partition, position("late-" + partition, stopped, START + 59_999, 10).toString());
            add(partitions, partition, "not-json");
        }

        // Replay accepts buckets, not a live merged stream. Construct genuinely offset-preserving
        // merges, then adapt each to that API without inventing a global event-time order.
        SplittableRandom random = new SplittableRandom(seed);
        int[] next = new int[2];
        List<RawRecord> schedule = new ArrayList<>();
        while (next[0] < partitions.get(0).size() || next[1] < partitions.get(1).size()) {
            int partition = random.nextInt(2);
            if (next[partition] == partitions.get(partition).size()) partition = 1 - partition;
            schedule.add(partitions.get(partition).get(next[partition]++));
        }
        Map<Integer, List<RawRecord>> merged = new LinkedHashMap<>();
        for (RawRecord record : schedule) {
            merged.computeIfAbsent(record.partition(), ignored -> new ArrayList<>()).add(record);
        }
        assertThat(merged.get(0)).containsExactlyElementsOf(partitions.get(0));
        assertThat(merged.get(1)).containsExactlyElementsOf(partitions.get(1));

        ReferenceResult result = new ReferenceExecutor().run(plan, merged);
        assertThat(result).isEqualTo(new ReferenceExecutor().run(plan, partitions));
        List<WindowResult> expectedWindows = new ArrayList<>();
        List<Alert> expectedAlerts = new ArrayList<>();
        List<DeadLetter> expectedDeadLetters = new ArrayList<>();
        for (int partition = 0; partition < 2; partition++) {
            String stopped = "stopped-" + partition;
            String moving = "moving-" + partition;
            for (int minute = 0; minute < 2; minute++) {
                expectedWindows.add(speedWindow(moving, START + minute * 60_000L, 1, 30 + minute * 10, 30 + minute * 10));
                expectedWindows.add(speedWindow(stopped, START + minute * 60_000L, 1, 0, 0));
            }
            expectedAlerts.add(new Alert("stuck|" + stopped + "|" + (START + 60_000), stopped,
                    "stuck-truck", stopped + " has not moved for 2 min", START + 120_000));
            RawRecord late = partitions.get(partition).get(6);
            expectedDeadLetters.add(new DeadLetter("late-" + partition, DeadLetter.LATE,
                    "window [" + START + "," + (START + 60_000) + ") already closed (watermark "
                            + (START + 120_000) + ")", partition, 6, START + 59_999, late.value()));
            expectedDeadLetters.add(new DeadLetter("p" + partition + "-o7", DeadLetter.REJECTED,
                    "unparseable JSON", partition, 7, null, "not-json"));
            assertThat(result.watermarksByPartition().get(partition))
                    .containsExactlyInAnyOrderEntriesOf(Map.of("speed1m", START + 120_000, "del1m", START + 120_000));
            assertThat(result.keysByPartition().get(partition)).containsExactlyInAnyOrder(stopped, moving);
        }
        assertThat(result.windowsBySink()).containsOnlyKeys("speedSink", "delSink");
        assertThat(result.windowsBySink().get("speedSink")).containsExactlyElementsOf(expectedWindows);
        assertThat(result.windowsBySink().get("delSink")).isEmpty();
        assertThat(result.alertsBySink()).containsOnlyKeys("alertSink");
        assertThat(result.alertsBySink().get("alertSink")).containsExactlyElementsOf(expectedAlerts);
        assertThat(result.deadLetters()).containsExactlyElementsOf(expectedDeadLetters);
        assertThat(result.watermarksByPartition().get(2).values()).containsOnly(Long.MIN_VALUE);
        assertThat(result.keysByPartition().get(2)).isEmpty();
        assertThat(result.recordsReplayed()).isEqualTo(16);
    }

    static LongStream interleavingSeeds() {
        return LongStream.range(0, 32).map(index -> 0x1A7E_12EAL ^ index);
    }

    @Test
    void exactWindowEndBelongsToNextWindowAndDoesNotFlushThatWindow() {
        ExecutionPlan plan = semanticsPlan("key", 0);
        Map<Integer, List<RawRecord>> records = new TreeMap<>();
        add(records, 0, position("before", "truck", START + 59_999, 10).toString());
        add(records, 0, position("at-end", "truck", START + 60_000, 30).toString());
        ReferenceResult atEnd = new ReferenceExecutor().run(plan, records);
        assertThat(atEnd.windowsBySink().get("speedSink"))
                .containsExactly(speedWindow("truck", START, 1, 10, 10));
        assertThat(atEnd.watermarksByPartition().get(0)).containsEntry("speed1m", START + 60_000);
        assertThat(atEnd.deadLetters()).isEmpty();

        add(records, 0, position("next-end", "truck", START + 120_000, 50).toString());
        ReferenceResult nextEnd = new ReferenceExecutor().run(plan, records);
        assertThat(nextEnd.windowsBySink().get("speedSink")).containsExactly(
                speedWindow("truck", START, 1, 10, 10),
                speedWindow("truck", START + 60_000, 1, 30, 30));
        assertThat(nextEnd.deadLetters()).isEmpty();
        assertThat(nextEnd.recordsReplayed()).isEqualTo(3);
    }

    @Test
    void windowEndEqualToPriorWatermarkIsLateButOneMillisecondAboveItIsAccepted() {
        Map<Integer, List<RawRecord>> records = new TreeMap<>();
        String original = position("original", "truck", START + 59_999, 10).toString();
        add(records, 0, original);
        add(records, 0, position("before-close", "truck", START + 64_999, 40).toString());
        add(records, 0, position("still-open", "truck", START + 1, 30).toString());
        ReferenceResult stillOpen = new ReferenceExecutor().run(PLAN, records);
        assertThat(stillOpen.windowsBySink().get("speedSink")).isEmpty();
        assertThat(stillOpen.deadLetters()).isEmpty();
        assertThat(stillOpen.watermarksByPartition().get(0)).containsEntry("speed1m", START + 59_999);

        add(records, 0, position("close", "truck", START + 65_000, 50).toString());
        String late = position("late-at-equality", "truck", START + 59_999, 90).toString();
        add(records, 0, late);
        add(records, 0, original);
        ReferenceResult closed = new ReferenceExecutor().run(PLAN, records);
        assertThat(closed.windowsBySink().get("speedSink"))
                .containsExactly(speedWindow("truck", START, 2, 20, 30));
        String reason = "window [" + START + "," + (START + 60_000)
                + ") already closed (watermark " + (START + 60_000) + ")";
        assertThat(closed.deadLetters()).containsExactly(
                new DeadLetter("late-at-equality", DeadLetter.LATE, reason, 0, 4, START + 59_999, late),
                new DeadLetter("original", DeadLetter.LATE, reason, 0, 5, START + 59_999, original));
        assertThat(closed.watermarksByPartition().get(0))
                .containsExactlyInAnyOrderEntriesOf(Map.of("speed1m", START + 60_000, "del1m", START + 60_000));
        assertThat(closed.recordsReplayed()).isEqualTo(6);
    }

    @ParameterizedTest
    @ValueSource(strings = {"key", "payload.truckId"})
    void keyByIsolatesAggregatesDuplicateIdsAndRuleStreaks(String keyField) {
        ExecutionPlan plan = semanticsPlan(keyField, 0);
        Map<Integer, List<RawRecord>> records = new TreeMap<>();
        for (int minute = 0; minute < 2; minute++) {
            long time = START + minute * 60_000L;
            add(records, 0, position("shared-id", "stopped", time, 0).toString());
            add(records, 0, position("shared-id", "moving", time, 40).toString());
            add(records, 0, position("other-" + minute, "moving", time + 1, 60).toString());
            add(records, 0, position("shared-id", "moving", time, 40).toString());
        }
        add(records, 0, position("clock", "stopped", START + 120_000, 0).toString());
        ReferenceResult result = new ReferenceExecutor().run(plan, records);
        assertThat(result.windowsBySink().get("speedSink")).containsExactly(
                speedWindow("moving", START, 2, 50, 60),
                speedWindow("stopped", START, 1, 0, 0),
                speedWindow("moving", START + 60_000, 2, 50, 60),
                speedWindow("stopped", START + 60_000, 1, 0, 0));
        assertThat(result.alertsBySink().get("alertSink")).containsExactly(
                new Alert("stuck|stopped|" + (START + 60_000), "stopped", "stuck-truck",
                        "stopped has not moved for 2 min", START + 120_000));
        assertThat(result.deadLetters()).isEmpty();
        assertThat(result.keysByPartition().get(0)).containsExactlyInAnyOrder("moving", "stopped");
        assertThat(result.recordsReplayed()).isEqualTo(9);
    }

    @ParameterizedTest
    @MethodSource("oracleSeeds")
    void seededCountAndAverageAgreeWithIndependentBatchOracle(long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        Map<Integer, List<OracleEvent>> events = new TreeMap<>();
        Map<Integer, List<RawRecord>> records = new LinkedHashMap<>();
        for (int partition = 0; partition < 3; partition++) {
            List<OracleEvent> source = new ArrayList<>();
            String key = "truck-" + partition + "-0";
            OracleEvent first = new OracleEvent("shared", key, START + 59_999, random.nextInt(1, 81));
            source.add(first);
            source.add(first);
            source.add(new OracleEvent("shared", "truck-" + partition + "-1", START + 1_000, 20));
            source.add(new OracleEvent("boundary", key, START + 60_000, 40));
            source.add(new OracleEvent("out-of-order", key, START + 2_000, 60));
            source.add(new OracleEvent("close", key, START + 65_000, 30));
            source.add(new OracleEvent("late", key, START + 1_000, 80));
            source.add(first);
            long cursor = 65_000;
            for (int index = 0; index < 60; index++) {
                cursor += random.nextLong(1, 30_001);
                long elapsed = index % 7 == 0 ? cursor / 60_000 * 60_000
                        : Math.max(0, cursor - random.nextLong(90_001));
                source.add(new OracleEvent("event-" + index, "truck-" + partition + "-" + random.nextInt(3),
                        START + elapsed, random.nextInt(4, 321) / 4.0));
                if (index % 9 == 0) source.add(source.get(random.nextInt(source.size())));
            }
            source.add(new OracleEvent("frontier", key, START + cursor + 120_000, 41));
            source.add(new OracleEvent("last-late", key, START, 99));
            events.put(partition, source);
            for (OracleEvent event : source) {
                add(records, partition, position(event.id(), event.key(), event.time(), event.speed()).toString());
            }
            // Offset ordering, not list ordering, is the replay API's promise.
            Collections.reverse(records.get(partition));
        }

        ReferenceResult result = new ReferenceExecutor().run(PLAN, records);
        List<WindowResult> expectedWindows = new ArrayList<>();
        List<DeadLetter> expectedDeadLetters = new ArrayList<>();
        long totalRecords = 0;
        for (Map.Entry<Integer, List<OracleEvent>> partition : events.entrySet()) {
            List<OracleEvent> source = partition.getValue();
            totalRecords += source.size();
            long finalWatermark = source.stream().mapToLong(OracleEvent::time).max().orElseThrow() - 5_000;
            Map<OracleWindow, Map<String, OracleEvent>> admitted = new LinkedHashMap<>();
            Set<String> keys = new java.util.TreeSet<>();
            for (int offset = 0; offset < source.size(); offset++) {
                OracleEvent event = source.get(offset);
                keys.add(event.key());
                // Batch oracle: classify each row using only its earlier source prefix, then
                // group admitted rows. No PartitionPipeline, validator, or operator is reused.
                long priorMaximum = source.subList(0, offset).stream()
                        .mapToLong(OracleEvent::time).max().orElse(Long.MIN_VALUE);
                long priorWatermark = offset == 0 ? Long.MIN_VALUE : priorMaximum - 5_000;
                long start = event.time() - event.time() % 60_000;
                long end = start + 60_000;
                if (end <= priorWatermark) {
                    expectedDeadLetters.add(new DeadLetter(event.id(), DeadLetter.LATE,
                            "window [" + start + "," + end + ") already closed (watermark " + priorWatermark + ")",
                            partition.getKey(), offset, event.time(),
                            position(event.id(), event.key(), event.time(), event.speed()).toString()));
                } else {
                    admitted.computeIfAbsent(new OracleWindow(event.key(), start), ignored -> new LinkedHashMap<>())
                            .putIfAbsent(event.id(), event);
                }
            }
            List<WindowResult> closed = new ArrayList<>();
            admitted.forEach((window, unique) -> {
                if (window.start() + 60_000 <= finalWatermark) {
                    double average = unique.values().stream().mapToDouble(OracleEvent::speed).average().orElseThrow();
                    double maximum = unique.values().stream().mapToDouble(OracleEvent::speed).max().orElseThrow();
                    closed.add(speedWindow(window.key(), window.start(), unique.size(), average, maximum));
                }
            });
            closed.sort(Comparator.comparingLong(WindowResult::windowEnd).thenComparing(WindowResult::key));
            expectedWindows.addAll(closed);
            assertThat(result.watermarksByPartition().get(partition.getKey()))
                    .containsExactlyInAnyOrderEntriesOf(Map.of("speed1m", finalWatermark, "del1m", finalWatermark));
            assertThat(result.keysByPartition().get(partition.getKey())).isEqualTo(keys);
        }
        List<WindowResult> actual = result.windowsBySink().get("speedSink");
        assertThat(expectedWindows).isNotEmpty();
        assertThat(actual).hasSameSizeAs(expectedWindows);
        for (int index = 0; index < expectedWindows.size(); index++) {
            WindowResult expected = expectedWindows.get(index);
            WindowResult row = actual.get(index);
            assertThat(row.nodeId()).isEqualTo("speed1m");
            assertThat(row.key()).isEqualTo(expected.key());
            assertThat(row.windowStart()).isEqualTo(expected.windowStart());
            assertThat(row.windowEnd()).isEqualTo(expected.windowEnd());
            assertThat(row.eventCount()).isEqualTo(expected.eventCount());
            assertThat(row.values()).containsOnlyKeys("count", "avgSpeedKmh", "maxSpeedKmh");
            assertThat(row.values().get("count")).isEqualTo((double) expected.eventCount());
            assertThat(row.values().get("avgSpeedKmh")).isCloseTo(expected.values().get("avgSpeedKmh"), within(1e-9));
            assertThat(row.values().get("maxSpeedKmh")).isEqualTo(expected.values().get("maxSpeedKmh"));
        }
        assertThat(result.windowsBySink()).containsOnlyKeys("speedSink", "delSink");
        assertThat(result.windowsBySink().get("delSink")).isEmpty();
        assertThat(result.alertsBySink()).containsOnlyKeys("alertSink");
        assertThat(result.alertsBySink().get("alertSink")).isEmpty();
        assertThat(result.deadLetters()).containsExactlyElementsOf(expectedDeadLetters);
        assertThat(result.deadLetters()).hasSizeGreaterThanOrEqualTo(9);
        assertThat(result.recordsReplayed()).isEqualTo(totalRecords);
        assertThat(result.watermarksByPartition().get(3).values()).containsOnly(Long.MIN_VALUE);
        assertThat(result.keysByPartition().get(3)).isEmpty();
    }

    static LongStream oracleSeeds() {
        return LongStream.range(0, 240).map(index -> 0x5EED_C0A7L ^ index);
    }

    private record OracleEvent(String id, String key, long time, double speed) {}

    private record OracleWindow(String key, long start) {}

    private static ExecutionPlan semanticsPlan(String keyField, long outOfOrdernessMs) {
        PipelineDefinition demo = PipelineCompiler.demoPipeline();
        List<Node> nodes = demo.nodes().stream().map(node -> {
            ObjectNode config = node.config().deepCopy();
            if (node.type().equals("keyBy")) config.put("field", keyField);
            if (node.type().equals("window")) config.put("outOfOrdernessMs", outOfOrdernessMs);
            if (node.type().equals("rule")) config.put("forWindows", 2);
            return new Node(node.id(), node.type(), node.label(), node.position(), config);
        }).toList();
        return new PipelineCompiler().plan(
                new PipelineDefinition(demo.id(), demo.version(), demo.name(), nodes, demo.edges()), 3);
    }

    private static WindowResult speedWindow(String key, long start, long count, double average, double maximum) {
        return new WindowResult("speed1m", key, start, start + 60_000,
                Map.of("count", (double) count, "avgSpeedKmh", average, "maxSpeedKmh", maximum), count);
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
