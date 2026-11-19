package io.aetherstream.engine.worker;

import static io.aetherstream.engine.worker.WorkerFixtures.await;
import static org.assertj.core.api.Assertions.assertThat;

import io.aetherstream.common.pipeline.ExecutionPlan;
import io.aetherstream.common.protocol.CheckpointAck;
import io.aetherstream.common.protocol.CheckpointManifest;
import io.aetherstream.common.protocol.Command;
import io.aetherstream.common.protocol.WorkerSnapshot;
import io.aetherstream.compiler.ops.ListCollector;
import io.aetherstream.engine.storage.InMemoryCheckpointStore;
import io.aetherstream.engine.worker.WorkerFixtures.FakeCoordinator;
import io.aetherstream.engine.worker.WorkerFixtures.FakeSource;
import io.aetherstream.engine.worker.WorkerFixtures.MemorySink;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TaskRuntimeTest {
    private static final int PARTITIONS = 3;
    private static final String EXEC = "exec-test";

    private final ExecutionPlan plan = WorkerFixtures.plan(PARTITIONS);
    private final Map<Integer, List<String>> data = WorkerFixtures.workload(PARTITIONS, 600);
    private final Map<Integer, List<SinkRow>> expected = WorkerFixtures.direct(plan, data);
    private final InMemoryCheckpointStore store = new InMemoryCheckpointStore();
    private final FakeCoordinator coordinator = new FakeCoordinator();
    private TaskRuntime runtime;

    @AfterEach
    void tearDown() {
        if (runtime != null) {
            runtime.stop();
        }
    }

    private Command.Assign assign(long generation, long restoreCheckpointId, String manifestKey,
                                  Map<Integer, Long> startOffsets) {
        return new Command.Assign(EXEC, generation, plan, List.of(0, 1, 2), restoreCheckpointId, manifestKey,
                startOffsets);
    }

    private static Map<Integer, Long> zeros() {
        return Map.of(0, 0L, 1, 0L, 2, 0L);
    }

    private TaskRuntime start(Command.Assign a, FakeSource source, MemorySink sink, long delayMs,
                              TaskRuntime.Listener listener) {
        TaskRuntime r = new TaskRuntime("w-1", a, topic -> source, sink, store, coordinator, () -> delayMs, listener);
        r.start();
        return r;
    }

    private int totalRecords() {
        return data.values().stream().mapToInt(List::size).sum();
    }

    private int totalExpected() {
        return expected.values().stream().mapToInt(List::size).sum();
    }

    @Test
    void workloadIsMeaningful() {
        List<ListCollector.Output> all = expected.values().stream().flatMap(List::stream).map(SinkRow::output).toList();
        assertThat(all).anySatisfy(o -> assertThat(o).isInstanceOf(ListCollector.WindowOutput.class));
        assertThat(all).anySatisfy(o -> assertThat(o).isInstanceOf(ListCollector.AlertOutput.class));
        assertThat(all).anySatisfy(o -> assertThat(o).isInstanceOf(ListCollector.DeadLetterOutput.class));
    }

    @Test
    void outputsEqualDirectPartitionPipeline() throws Exception {
        FakeSource source = new FakeSource(data);
        MemorySink sink = new MemorySink();
        runtime = start(assign(1, 0, null, zeros()), source, sink, 0, null);

        await("all outputs", 20_000, () -> sink.rows.size() >= totalExpected()
                && runtime.processedRecords() == totalRecords());
        Thread.sleep(300);

        assertThat(runtime.state()).isEqualTo(TaskRuntime.State.RUNNING);
        assertThat(sink.rows).hasSize(totalExpected());
        for (int p = 0; p < PARTITIONS; p++) {
            assertThat(sink.partition(p)).as("partition %d", p).containsExactlyElementsOf(expected.get(p));
        }
        await("lag refreshed", 5_000, () -> runtime.lag().size() == PARTITIONS
                && runtime.lag().values().stream().allMatch(l -> l == 0));
    }

    @Test
    void barrierAckOffsetsAndRestoreEquivalence() throws Exception {
        FakeSource source1 = new FakeSource(data);
        source1.limit.set(700);
        MemorySink sink1 = new MemorySink();
        runtime = start(assign(1, 0, null, zeros()), source1, sink1, 0, null);

        await("700 processed", 20_000, () -> runtime.processedRecords() == 700);
        Map<Integer, Long> before = source1.nextOffsets();
        runtime.triggerCheckpoint(1);
        await("ack", 10_000, () -> coordinator.acks.size() == 1);

        CheckpointAck ack = coordinator.acks.get(0);
        assertThat(ack.checkpointId()).isEqualTo(1);
        assertThat(ack.generation()).isEqualTo(1);
        assertThat(ack.executionId()).isEqualTo(EXEC);
        assertThat(ack.workerId()).isEqualTo("w-1");
        assertThat(ack.partitions()).isEqualTo(before);
        assertThat(ack.partitions().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(700);
        assertThat(ack.objectKey()).isEqualTo("executions/" + EXEC + "/checkpoints/1/worker-w-1.json");
        await("lastCheckpointId 1", 10_000, () -> runtime.lastCheckpointId() == 1); // set just after the ack returns

        // Everything before the barrier was committed before the ack.
        for (int p = 0; p < PARTITIONS; p++) {
            long cut = ack.partitions().get(p);
            List<SinkRow> prefix = expected.get(p).stream().filter(r -> r.offset() < cut).toList();
            assertThat(sink1.partition(p)).as("partition %d before barrier", p).containsExactlyElementsOf(prefix);
        }
        WorkerSnapshot snapshot = store.getJson(ack.objectKey(), WorkerSnapshot.class);
        assertThat(snapshot.checkpointId()).isEqualTo(1);
        assertThat(snapshot.generation()).isEqualTo(1);
        assertThat(snapshot.partitions().keySet()).containsExactlyInAnyOrder(0, 1, 2);
        assertThat(snapshot.partitions().get(0).nextOffset()).isEqualTo(ack.partitions().get(0));

        runtime.stop();
        assertThat(source1.closed).isTrue();

        // The coordinator would write this manifest once all partitions are acked.
        Map<Integer, CheckpointManifest.PartitionEntry> entries = new TreeMap<>();
        ack.partitions().forEach((p, o) -> entries.put(p, new CheckpointManifest.PartitionEntry(o, ack.objectKey())));
        String manifestKey = WorkerKeys.manifestKey(EXEC, 1);
        store.putJson(manifestKey, new CheckpointManifest(EXEC, 1, 1, plan.pipelineId(), plan.pipelineVersion(), 1,
                "2026-11-19T00:00:00Z", entries));

        FakeSource source2 = new FakeSource(data);
        MemorySink sink2 = new MemorySink();
        runtime = start(assign(2, 1, manifestKey, ack.partitions()), source2, sink2, 0, null);

        Map<Integer, List<SinkRow>> remaining = new TreeMap<>();
        for (int p = 0; p < PARTITIONS; p++) {
            long cut = ack.partitions().get(p);
            remaining.put(p, expected.get(p).stream().filter(r -> r.offset() >= cut).toList());
        }
        int remainingCount = remaining.values().stream().mapToInt(List::size).sum();
        assertThat(remainingCount).isPositive();
        await("remaining outputs", 20_000, () -> sink2.rows.size() >= remainingCount
                && runtime.processedRecords() == totalRecords() - 700);
        Thread.sleep(300);
        assertThat(sink2.rows).hasSize(remainingCount);
        for (int p = 0; p < PARTITIONS; p++) {
            assertThat(sink2.partition(p)).as("partition %d after restore", p)
                    .containsExactlyElementsOf(remaining.get(p));
        }
    }

    @Test
    void fencedSinkStopsRuntimeAndWritesNothingMore() throws Exception {
        FakeSource source = new FakeSource(data);
        source.limit.set(300);
        MemorySink sink = new MemorySink();
        AtomicReference<String> stopReason = new AtomicReference<>();
        runtime = start(assign(1, 0, null, zeros()), source, sink, 0, (r, why) -> stopReason.set(why));

        await("300 processed", 20_000, () -> runtime.processedRecords() == 300 && runtime.sinkQueueSize() == 0);
        Thread.sleep(2 * TaskRuntime.FLUSH_INTERVAL_MS + 100);
        int written = sink.rows.size();

        sink.fenced.set(true);
        source.limit.set(Long.MAX_VALUE);

        await("runtime stopped", 10_000, () -> runtime.isStopped() && stopReason.get() != null);
        assertThat(runtime.isFenced()).isTrue();
        assertThat(stopReason.get()).contains("fenced");
        assertThat(sink.rejectedWrites.get()).isEqualTo(1);
        // STOPPED is published before the operator/source threads finish their in-flight work.
        // Join them before snapshotting counters; processedRecords counts batches already forwarded to the sink queue.
        runtime.stop();
        long emittedAtStop = source.emitted.get();
        long processedAtStop = runtime.processedRecords();
        Thread.sleep(500);
        assertThat(sink.rows).hasSize(written);
        assertThat(sink.rejectedWrites.get()).isEqualTo(1);
        assertThat(source.emitted.get()).isEqualTo(emittedAtStop);
        assertThat(runtime.processedRecords()).isEqualTo(processedAtStop);
        await("consumer closed", 5_000, () -> source.closed.get());
    }

    @Test
    void sinkDelayFillsQueuesAndBlocksSource() throws Exception {
        FakeSource source = new FakeSource(data);
        MemorySink sink = new MemorySink();
        AtomicLong delay = new AtomicLong(20);
        runtime = new TaskRuntime("w-1", assign(1, 0, null, zeros()), topic -> source, sink, store, coordinator,
                delay::get, null);
        runtime.start();

        await("sink queue full", 20_000, () -> runtime.sinkQueueSize() == TaskRuntime.QUEUE_CAPACITY);
        assertThat(runtime.backpressured()).isTrue();
        await("source queue full", 20_000, () -> runtime.sourceQueueSize() == TaskRuntime.QUEUE_CAPACITY);
        // Bounded memory: emitted = processed (sunk or in queue 2) + queue 1 + one in-hand item + rest of a poll batch.
        long bound = runtime.processedRecords() + TaskRuntime.QUEUE_CAPACITY + 1 + 500 + 50;
        assertThat(source.emitted.get()).isLessThanOrEqualTo(bound);
        assertThat(source.emitted.get()).isLessThan(totalRecords());

        long emitted = source.emitted.get();
        Thread.sleep(300);
        // At ~50 items/s drained by the sink, the source can only advance a little (at most one more poll batch).
        assertThat(source.emitted.get() - emitted).isLessThanOrEqualTo(500 + 50);
        assertThat(runtime.backpressured()).isTrue();
    }

    @Test
    void checkpointFloodRetainsOnlyTwoNewestRequestsBeforeAdmission() throws Exception {
        CountDownLatch creating = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeSource source = new FakeSource(Map.of());
        runtime = new TaskRuntime("w-1", assign(1, 0, null, zeros()), topic -> {
            creating.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return source;
        }, new MemorySink(), store, coordinator, () -> 0, null);
        runtime.start();
        try {
            assertThat(creating.await(5, TimeUnit.SECONDS)).isTrue();
            for (long id = 1; id <= 10_000; id++) {
                runtime.triggerCheckpoint(id);
                assertThat(runtime.pendingCheckpointCount()).isLessThanOrEqualTo(2);
            }
            runtime.triggerCheckpoint(12);
            runtime.triggerCheckpoint(10_000);
            assertThat(runtime.pendingCheckpointCount()).isEqualTo(2);
        } finally {
            release.countDown();
        }
        await("two retained checkpoints acknowledged", 5_000, () -> coordinator.acks.size() == 2);
        assertThat(coordinator.acks).extracting(CheckpointAck::checkpointId).containsExactly(9999L, 10000L);
        assertThat(coordinator.acks).allSatisfy(ack -> assertThat(ack.partitions()).isEqualTo(zeros()));
        assertThat(runtime.lastCheckpointId()).isEqualTo(10000);
    }

    @Test
    void calculatesLagOnlyForAssignedPositionsWithKnownEndsAndClampsStaleEnds() {
        assertThat(TaskRuntime.calculateLag(
                Map.of(0, 100L, 1, 50L, 2, 90L, 4, 999L),
                Map.of(0, 25L, 1, 50L, 2, 95L, 3, 10L)))
                .containsExactlyInAnyOrderEntriesOf(Map.of(0, 75L, 1, 0L, 2, 0L));
    }

    @Test
    void lagUsesPerPartitionConsumerPositionsAndStaysOffStatusThread() throws Exception {
        FakeSource source = new FakeSource(data);
        source.limit.set(0);
        Map<Integer, Long> starts = Map.of(0, 5L, 1, 11L, 2, 17L);
        runtime = start(assign(1, 0, null, starts), source, new MemorySink(), 0, null);
        Map<Integer, Long> initialLag = new TreeMap<>();
        starts.forEach((p, offset) -> initialLag.put(p, data.get(p).size() - offset));
        await("initial lag at restored offsets", 5_000, () -> runtime.lag().equals(initialLag));
        int calls = source.lagRefreshNanos.size();
        for (int i = 0; i < 1000; i++) {
            runtime.lag();
            runtime.recordsPerSec();
        }
        assertThat(source.lagRefreshNanos).hasSize(calls);
        source.limit.set(37);
        await("37 consumed", 5_000, () -> source.emitted.get() == 37);
        Map<Integer, Long> expectedLag = new TreeMap<>();
        source.nextOffsets().forEach((p, offset) -> expectedLag.put(p, data.get(p).size() - offset));
        await("lag after poll", 5_000, () -> runtime.lag().equals(expectedLag));
        assertThat(source.lagThread).isSameAs(source.owner).isNotSameAs(Thread.currentThread());
        for (int i = 1; i < source.lagRefreshNanos.size(); i++) {
            assertThat(source.lagRefreshNanos.get(i) - source.lagRefreshNanos.get(i - 1))
                    .isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(TaskRuntime.LAG_REFRESH_MS - 1));
        }
    }

    @Test
    void lagRefreshesWhileSourceQueueIsFullUsingFetchedNotCheckpointOffsets() throws Exception {
        FakeSource source = new FakeSource(data);
        runtime = start(assign(1, 0, null, zeros()), source, new MemorySink(), 60_000, null);
        await("both queues full", 10_000, () -> runtime.sourceQueueSize() == TaskRuntime.QUEUE_CAPACITY
                && runtime.sinkQueueSize() == TaskRuntime.QUEUE_CAPACITY);
        Map<Integer, Long> expectedLag = new TreeMap<>();
        source.nextOffsets().forEach((p, offset) -> expectedLag.put(p, data.get(p).size() - offset));
        await("lag refreshed under backpressure", 5_000, () -> runtime.lag().equals(expectedLag));
        assertThat(source.lagRefreshNanos.size()).isGreaterThanOrEqualTo(2);
        assertThat(source.lagThread).isSameAs(source.owner);
    }

    @Test
    void restoreFromAnotherExecutionsSavepointAcceptsCheckpointsFromOne() throws Exception {
        // Savepoint manifest of checkpoint 5 of another execution, copied under savepoints/ (empty state, offsets 0).
        Map<Integer, CheckpointManifest.PartitionEntry> entries = new TreeMap<>();
        for (int p = 0; p < PARTITIONS; p++) {
            String objectKey = "executions/old-exec/checkpoints/5/worker-w-9.json";
            entries.put(p, new CheckpointManifest.PartitionEntry(0L, objectKey));
        }
        FakeSource warm = new FakeSource(data);
        warm.limit.set(0);
        TaskRuntime empty = new TaskRuntime("w-9", new Command.Assign("old-exec", 1, plan, List.of(0, 1, 2), 0, null,
                zeros()), topic -> warm, new MemorySink(), store, new FakeCoordinator(), () -> 0, null);
        empty.start();
        empty.triggerCheckpoint(5);
        await("old snapshot", 10_000, () -> empty.lastCheckpointId() == 5);
        empty.stop();
        String savepointKey = "savepoints/sp-old-exec-5/manifest.json";
        store.putJson(savepointKey, new CheckpointManifest("old-exec", 5, 1, plan.pipelineId(), plan.pipelineVersion(), 1,
                "2026-11-19T00:00:00Z", entries));

        FakeSource source = new FakeSource(data);
        source.limit.set(100);
        runtime = start(assign(1, 5, savepointKey, zeros()), source, new MemorySink(), 0, null);
        assertThat(runtime.lastCheckpointId()).isZero();
        await("100 processed", 20_000, () -> runtime.processedRecords() == 100);
        runtime.triggerCheckpoint(1);
        await("ack 1", 10_000, () -> coordinator.acks.size() == 1);
        runtime.triggerCheckpoint(2);
        await("ack 2", 10_000, () -> coordinator.acks.size() == 2);
        assertThat(coordinator.acks).extracting(CheckpointAck::checkpointId).containsExactly(1L, 2L);
        assertThat(coordinator.acks).allSatisfy(a -> assertThat(a.executionId()).isEqualTo(EXEC));
        assertThat(runtime.lastCheckpointId()).isEqualTo(2);
    }
}
