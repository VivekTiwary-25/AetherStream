package io.aetherstream.api.compare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aetherstream.common.Json;
import io.aetherstream.common.api.CompareResult;
import io.aetherstream.common.pipeline.ExecutionPlan;
import io.aetherstream.compiler.PipelineCompiler;
import io.aetherstream.compiler.ops.ListCollector;
import io.aetherstream.compiler.ops.PartitionPipeline;
import io.aetherstream.reference.EngineAlertRow;
import io.aetherstream.reference.EngineWindowRow;
import io.aetherstream.reference.RawRecord;
import io.aetherstream.reference.ResultComparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** R08 #1: comparison memory is bounded by the event-time horizon, not by run length. */
class BoundedReplayTest {
    private static final long START = 1_795_251_600_000L;
    private static final long STEP_MS = 15_000L;
    private static final int KEYS_PER_PARTITION = 2;
    private static final ExecutionPlan PLAN = new PipelineCompiler().plan(PipelineCompiler.demoPipeline(), 2);

    @Test
    void heldResultsDoNotGrowWithRunLengthAndRecordsAreNeverMaterialised() {
        CountingReader shortReader = new CountingReader();
        CountingReader longReader = new CountingReader();
        long shortRecords = records(600);
        long longRecords = records(6_000);

        var shortRun = BoundedReplay.run(List.of(stage(0, shortRecords)), shortReader,
                CompareService.DEFAULT_RETENTION_MS);
        var longRun = BoundedReplay.run(List.of(stage(0, longRecords)), longReader,
                CompareService.DEFAULT_RETENTION_MS);

        assertThat(longRun.reference().recordsReplayed()).isEqualTo(2 * longRecords).isGreaterThan(90_000);
        assertThat(longReader.delivered).isEqualTo(2 * longRecords);
        // Ten times the run, same peak: memory depends on the horizon only.
        long horizonResults = 2L * KEYS_PER_PARTITION * (CompareService.DEFAULT_RETENTION_MS / 60_000L);
        assertThat(shortRun.peakRetained()).isPositive();
        assertThat(longRun.peakRetained()).isLessThanOrEqualTo(shortRun.peakRetained() + 2L * KEYS_PER_PARTITION)
                .isLessThan(2 * horizonResults);
        long fullRunWindows = 2L * KEYS_PER_PARTITION * 6_000;
        assertThat(longRun.peakRetained()).isLessThan(fullRunWindows / 20);
        assertThat(heldResults(longRun)).isPositive().isLessThanOrEqualTo(longRun.peakRetained());
    }

    @Test
    void longRunComparesTheHorizonExactlyAgainstAFullEngineRun() {
        long total = records(6_000);
        var stages = List.of(stage(0, total));
        var replay = BoundedReplay.run(stages, new CountingReader(), CompareService.DEFAULT_RETENTION_MS);
        assertMatches(replay, stages);
    }

    @Test
    void restoredLineageKeepsInheritedStateAndComparesOnlyTheFinalStage() {
        long total = records(6_000);
        long split = records(5_970);
        var stages = List.of(stage(0, split), stage(split, total));
        var replay = BoundedReplay.run(stages, new CountingReader(), CompareService.DEFAULT_RETENTION_MS);
        // The final stage is 30 event-minutes long: every window it wrote is within the horizon.
        assertMatches(replay, stages);
        assertThat(replay.reference().recordsReplayed()).isEqualTo(2 * total);
    }

    @Test
    void retentionAlwaysCoversSeveralOfThePlansWidestWindows() {
        assertThat(BoundedReplay.effectiveRetention(PLAN, 0))
                .isEqualTo(BoundedReplay.MIN_WINDOWS_RETAINED * (60_000L + 5_000L));
        assertThat(BoundedReplay.effectiveRetention(PLAN, CompareService.DEFAULT_RETENTION_MS))
                .isEqualTo(CompareService.DEFAULT_RETENTION_MS);
    }

    @Test
    void outOfOrderOrForeignRecordsAreRejected() {
        BoundedReplay.PartitionReader backwards = (partition, from, to, sink) -> {
            sink.accept(record(partition, from + 1));
            sink.accept(record(partition, from));
        };
        assertThatThrownBy(() -> BoundedReplay.run(List.of(stage(0, 10)), backwards, 0))
                .isInstanceOf(IllegalStateException.class);
    }

    private static void assertMatches(BoundedReplay.Outcome replay, List<BoundedReplay.Stage> stages) {
        List<EngineWindowRow> allWindows = new ArrayList<>();
        List<EngineWindowRow> windows = new ArrayList<>();
        List<EngineAlertRow> alerts = new ArrayList<>();
        int allAlerts = 0;
        for (int partition = 0; partition < PLAN.partitions(); partition++) {
            PartitionPipeline pipeline = null;
            ListCollector collector = null;
            for (BoundedReplay.Stage stage : stages) {
                pipeline = pipeline == null ? new PartitionPipeline(stage.plan(), partition)
                        : PartitionPipeline.restore(stage.plan(), partition, pipeline.snapshot());
                collector = new ListCollector();
                for (long offset = stage.from().get(partition); offset < stage.to().get(partition); offset++) {
                    pipeline.process(offset, record(partition, offset).value(), collector);
                }
            }
            for (ListCollector.Output output : collector.outputs()) {
                if (output instanceof ListCollector.WindowOutput window) {
                    var r = window.result();
                    var row = new EngineWindowRow(window.sinkNodeId(), r.key(), r.windowStart(), r.windowEnd(),
                            r.values(), r.eventCount());
                    allWindows.add(row);
                    if (replay.keeps(r.key(), r.windowEnd())) windows.add(row);
                } else if (output instanceof ListCollector.AlertOutput alert) {
                    allAlerts++;
                    if (replay.keeps(alert.alert().key(), alert.alert().eventTime())) {
                        alerts.add(new EngineAlertRow(alert.sinkNodeId(), alert.alert().alertId()));
                    }
                }
            }
        }
        CompareResult result = new ResultComparator().compare(replay.reference(), windows, alerts, 7, 0);
        assertThat(result.mismatches()).isEmpty();
        assertThat(result.match()).isTrue();
        assertThat(result.referenceResults()).isPositive()
                .isEqualTo(result.matched()).isEqualTo(result.engineResults());
        assertThat(windows).isNotEmpty();
        if (stages.size() == 1) {
            assertThat(alerts).isNotEmpty();
            assertThat(windows.size() + alerts.size()).isLessThan((allWindows.size() + allAlerts) / 20);
        }
    }

    private static long heldResults(BoundedReplay.Outcome outcome) {
        long held = 0;
        for (var rows : outcome.reference().windowsBySink().values()) held += rows.size();
        for (var rows : outcome.reference().alertsBySink().values()) held += rows.size();
        return held;
    }

    private static long records(int minutes) {
        return (long) minutes * (60_000L / STEP_MS) * KEYS_PER_PARTITION;
    }

    private static BoundedReplay.Stage stage(long from, long to) {
        return new BoundedReplay.Stage(PLAN, Map.of(0, from, 1, from), Map.of(0, to, 1, to));
    }

    /** Generates records lazily: nothing is buffered, so only the replay could hold them. */
    private static final class CountingReader implements BoundedReplay.PartitionReader {
        long delivered;

        @Override
        public void read(int partition, long from, long to, Consumer<RawRecord> sink) {
            for (long offset = from; offset < to; offset++) {
                sink.accept(record(partition, offset));
                delivered++;
            }
        }
    }

    private static RawRecord record(int partition, long offset) {
        int keyIndex = (int) (offset % KEYS_PER_PARTITION);
        long step = offset / KEYS_PER_PARTITION;
        long eventTime = START + step * STEP_MS + 1_000L;
        String key = "truck-" + partition + "-" + keyIndex;
        // Stopped for the first 20 minutes of each hour so the stopped-truck rule fires all day.
        boolean stopped = (step * STEP_MS / 60_000L) % 60 < 20;
        var event = Json.MAPPER.createObjectNode();
        event.put("eventId", key + "-" + step).put("schemaVersion", 1).put("type", "truck.position")
                .put("eventTime", eventTime).put("key", key);
        event.putObject("payload").put("truckId", key).put("x", 100).put("y", 100)
                .put("speedKmh", stopped ? 0 : 30).put("heading", 0)
                .put("routeId", "r-1").put("status", stopped ? "STOPPED" : "DRIVING");
        return new RawRecord(partition, offset, event.toString());
    }
}
