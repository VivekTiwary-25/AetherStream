package io.aetherstream.engine.worker;

import static io.aetherstream.engine.worker.WorkerFixtures.await;
import static org.assertj.core.api.Assertions.assertThat;

import io.aetherstream.common.pipeline.ExecutionPlan;
import io.aetherstream.common.protocol.Command;
import io.aetherstream.common.protocol.WorkerStatus;
import io.aetherstream.engine.storage.InMemoryCheckpointStore;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WorkerTest {
    private final ExecutionPlan plan = WorkerFixtures.plan(2);
    private final Map<Integer, List<String>> data = WorkerFixtures.workload(2, 50);
    private final AtomicBoolean halted = new AtomicBoolean();
    private final Worker worker = new Worker("w-1", topic -> new WorkerFixtures.FakeSource(data),
            new WorkerFixtures.MemorySink(), new InMemoryCheckpointStore(), new WorkerFixtures.FakeCoordinator(),
            () -> halted.set(true));

    @AfterEach
    void tearDown() {
        worker.shutdown();
    }

    private Command.Assign assign(String exec, long generation) {
        return new Command.Assign(exec, generation, plan, List.of(0, 1), 0, null, Map.of(0, 0L, 1, 0L));
    }

    @Test
    void idleStatusBeforeAnyAssign() {
        WorkerStatus s = worker.status();
        assertThat(s.state()).isEqualTo("IDLE");
        assertThat(s.partitions()).isEmpty();
        assertThat(s.sourceQueue().capacity()).isEqualTo(1000);
        assertThat(s.protocolVersion()).isEqualTo(1);
    }

    @Test
    void generationRules() throws Exception {
        worker.handle(assign("e1", 2));
        TaskRuntime first = worker.runtime();
        await("running", 5_000, () -> "RUNNING".equals(worker.status().state()));
        WorkerStatus s = worker.status();
        assertThat(s.executionId()).isEqualTo("e1");
        assertThat(s.generation()).isEqualTo(2);
        assertThat(s.partitions()).containsExactly(0, 1);

        worker.handle(assign("e1", 2));
        assertThat(worker.runtime()).isSameAs(first); // same generation: no-op

        worker.handle(assign("e1", 1));
        assertThat(worker.runtime()).isSameAs(first); // lower generation: ignored

        worker.handle(new Command.Cancel("e1", 1));
        assertThat(worker.runtime()).isSameAs(first); // stale cancel ignored

        worker.handle(assign("e1", 3));
        TaskRuntime second = worker.runtime();
        assertThat(second).isNotSameAs(first);
        assertThat(first.isStopped()).isTrue();
        assertThat(second.generation()).isEqualTo(3);

        worker.handle(new Command.Cancel("e1", 3));
        assertThat(worker.runtime()).isNull();
        assertThat(second.isStopped()).isTrue();
        assertThat(worker.status().state()).isEqualTo("IDLE");

        // A new execution starts at generation 1 again and must not be treated as stale.
        worker.handle(assign("e2", 1));
        assertThat(worker.runtime()).isNotNull();
        assertThat(worker.runtime().executionId()).isEqualTo("e2");
    }

    @Test
    void cancellationLeavesStatusAndMetricsAvailableWhileStopping() throws Exception {
        assertReadsDuringStop(false);
    }

    @Test
    void reassignmentLeavesStatusAndMetricsAvailableWhileStopping() throws Exception {
        assertReadsDuringStop(true);
    }

    private void assertReadsDuringStop(boolean reassign) throws Exception {
        var source = new StopBlockingSource();
        var sink = new WorkerFixtures.MemorySink();
        var firstSource = new AtomicBoolean(true);
        var w = new Worker("w-reads", topic -> firstSource.getAndSet(false)
                ? source : new WorkerFixtures.FakeSource(Map.of()), sink, new InMemoryCheckpointStore(),
                new WorkerFixtures.FakeCoordinator(), () -> { });
        Command command = reassign ? assign("e1", 3) : new Command.Cancel("e1", 3);
        var stopping = new FutureTask<Void>(() -> {
            w.handle(command);
            return null;
        });
        Thread stopThread = new Thread(stopping, "test-runtime-stop");
        try {
            w.handle(assign("e1", 1));
            TaskRuntime first = w.runtime();
            await("initial batch committed", 5000, () -> first.processedRecords() == 2
                    && first.sinkBatchesTotal() == 1);
            stopThread.start();
            assertThat(source.stopping.await(5, TimeUnit.SECONDS)).isTrue();

            var reading = new FutureTask<String>(() -> {
                WorkerStatus status = w.status();
                assertThat(status.state()).isEqualTo("IDLE");
                assertThat(status.executionId()).isEqualTo("e1");
                assertThat(status.generation()).isEqualTo(1);
                assertThat(status.partitions()).isEmpty();
                assertThat(w.runtime()).isSameAs(first);
                return w.metrics();
            });
            Thread reader = new Thread(reading, "test-status-metrics-reader");
            reader.setDaemon(true);
            reader.start();
            String duringStop = reading.get(1, TimeUnit.SECONDS);
            assertThat(duringStop).contains(
                    "aether_worker_processed_records_total{worker=\"w-reads\"} 2\n",
                    "aether_worker_sink_batches_total{worker=\"w-reads\"} 1\n",
                    "aether_worker_fenced_total{worker=\"w-reads\"} 0\n");
            assertThat(source.release.getCount()).isEqualTo(1);
            assertThat(stopping.isDone()).isFalse();

            source.release.countDown();
            stopping.get(5, TimeUnit.SECONDS);
            assertThat(first.isStopped()).isTrue();
            TaskRuntime current = w.runtime();
            if (reassign) {
                assertThat(current).isNotNull().isNotSameAs(first);
                assertThat(current.generation()).isEqualTo(3);
            } else {
                assertThat(current).isNull();
            }
            assertThat(w.status().generation()).isEqualTo(3);
            assertThat(w.metrics()).contains(
                    "aether_worker_processed_records_total{worker=\"w-reads\"} 2\n",
                    "aether_worker_sink_batches_total{worker=\"w-reads\"} 1\n",
                    "aether_worker_fenced_total{worker=\"w-reads\"} 0\n");
            w.handle(assign("e1", 2));
            assertThat(w.runtime()).isSameAs(current); // stopping must not weaken generation fencing
        } finally {
            source.release.countDown();
            stopThread.join(5000);
            w.shutdown();
        }
    }

    @Test
    void sinkDelayAndKill() {
        worker.handle(new Command.SetSinkDelay(250));
        assertThat(worker.sinkDelayMs()).isEqualTo(250);
        worker.handle(new Command.Kill());
        assertThat(halted).isTrue();
    }

    @Test
    void configuredQueuesReportActualBoundsAndClearPressureAfterChaosEnds() throws Exception {
        Worker w = new Worker("w-demo", topic -> new WorkerFixtures.FakeSource(data),
                new WorkerFixtures.MemorySink(), new InMemoryCheckpointStore(),
                new WorkerFixtures.FakeCoordinator(), () -> { }, 32);
        try {
            w.handle(new Command.SetSinkDelay(200));
            w.handle(assign("demo", 1));
            await("bounded demo queues full", 5_000, () ->
                    w.status().sourceQueue().size() == 32 && w.status().sinkQueue().size() == 32);
            WorkerStatus status = w.status();
            assertThat(status.sourceQueue().capacity()).isEqualTo(32);
            assertThat(status.sinkQueue().capacity()).isEqualTo(32);
            assertThat(status.backpressured()).isTrue();

            w.handle(new Command.SetSinkDelay(0));
            await("pressure cleared after delay removal", 5_000, () ->
                    w.status().sourceQueue().size() == 0 && w.status().sinkQueue().size() == 0);
            assertThat(w.status().backpressured()).isFalse();
        } finally {
            w.shutdown();
        }
    }

    @Test
    void fencedRuntimeMakesWorkerIdle() throws Exception {
        WorkerFixtures.MemorySink sink = new WorkerFixtures.MemorySink();
        sink.fenced.set(true);
        Worker w = new Worker("w-2", topic -> new WorkerFixtures.FakeSource(data), sink,
                new InMemoryCheckpointStore(), new WorkerFixtures.FakeCoordinator(), () -> { });
        try {
            w.handle(assign("e1", 1));
            await("idle after fencing", 10_000, () -> w.runtime() == null && "IDLE".equals(w.status().state()));
            assertThat(sink.rows).isEmpty();
        } finally {
            w.shutdown();
        }
    }

    /** Blocks the stop caller itself, so reads cannot pass merely because the runtime join timed out. */
    private static final class StopBlockingSource implements RecordSource {
        private final WorkerFixtures.FakeSource delegate =
                new WorkerFixtures.FakeSource(Map.of(0, List.of("bad-json", "also-bad")));
        private final CountDownLatch stopping = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void assign(Collection<Integer> partitions, Map<Integer, Long> startOffsets) {
            delegate.assign(partitions, startOffsets);
        }

        @Override
        public List<SourceRecord> poll(Duration timeout) {
            return delegate.poll(timeout);
        }

        @Override
        public Map<Integer, Long> nextOffsets() {
            return delegate.nextOffsets();
        }

        @Override
        public Map<Integer, Long> endOffsets() {
            return delegate.endOffsets();
        }

        @Override
        public void wakeup() {
            stopping.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while blocking stop", e);
            }
            delegate.wakeup();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
