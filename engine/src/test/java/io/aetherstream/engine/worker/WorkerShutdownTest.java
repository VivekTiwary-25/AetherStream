package io.aetherstream.engine.worker;

import static io.aetherstream.engine.worker.WorkerFixtures.await;
import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.aetherstream.common.protocol.AckResponse;
import io.aetherstream.common.protocol.CheckpointAck;
import io.aetherstream.common.protocol.Command;
import io.aetherstream.common.protocol.HeartbeatResponse;
import io.aetherstream.common.protocol.RegisterRequest;
import io.aetherstream.common.protocol.RegisterResponse;
import io.aetherstream.common.protocol.WorkerStatus;
import io.aetherstream.engine.storage.InMemoryCheckpointStore;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class WorkerShutdownTest {
    private final Command.Assign assignment = new Command.Assign("e1", 1, WorkerFixtures.plan(1),
            List.of(0), 0, null, Map.of(0, 0L));

    @Test
    @Timeout(10)
    void hookFinishesActiveBatchClosesResourcesAndReportsIdleExactlyOnce() throws Exception {
        var source = new WorkerFixtures.FakeSource(Map.of(0, Collections.nCopies(3000, "invalid-json")));
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean writeInterrupted = new AtomicBoolean();
        AtomicBoolean committed = new AtomicBoolean();
        AtomicInteger batches = new AtomicInteger();
        ResultSink sink = (execution, generation, rows) -> {
            batches.incrementAndGet();
            writing.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                writeInterrupted.set(true);
                throw e;
            }
            committed.set(true);
            return true;
        };
        ReportingCoordinator coordinator = new ReportingCoordinator();
        var worker = new Worker("w-stop", topic -> source, sink, new InMemoryCheckpointStore(), coordinator, () -> { });
        AtomicBoolean poolClosed = new AtomicBoolean();
        HttpServer server = WorkerMain.startHealthServer(0, worker::metrics);
        var shutdown = new WorkerMain.Shutdown(worker, coordinator, () -> poolClosed.set(true), server, new Thread());
        worker.handle(assignment);
        assertThat(writing.await(5, TimeUnit.SECONDS)).isTrue();
        Thread hook = new Thread(shutdown, "test-shutdown-hook");
        long started = System.nanoTime();
        try {
            hook.start();
            await("source closed while batch finishes", 2000, () -> source.closed.get());
            assertThat(hook.isAlive()).isTrue();
            assertThat(writeInterrupted).isFalse();
            assertThat(committed).isFalse();
            assertThat(coordinator.statuses).isEmpty();
        } finally {
            release.countDown();
            hook.join(4000);
            server.stop(0);
            worker.shutdown();
        }
        assertThat(hook.isAlive()).isFalse();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(4000);
        assertThat(committed).isTrue();
        assertThat(writeInterrupted).isFalse();
        assertThat(batches).hasValue(1);
        assertThat(poolClosed).isTrue();
        assertThat(source.closeThread).isSameAs(source.owner);
        assertThat(coordinator.statuses).singleElement().satisfies(status -> {
            assertThat(status.state()).isEqualTo("IDLE");
            assertThat(status.partitions()).isEmpty();
            assertThat(status.generation()).isEqualTo(1);
        });
        shutdown.run();
        worker.handle(assignment);
        assertThat(coordinator.statuses).hasSize(1);
        assertThat(worker.runtime()).isNull();
    }

    @Test
    void shutdownFlushesCurrentBufferedRowsWithoutWaitingForChaosDelay() throws Exception {
        var source = new WorkerFixtures.FakeSource(Map.of(0, List.of("invalid-json")));
        var sink = new WorkerFixtures.MemorySink();
        var worker = new Worker("w-buffered", topic -> source, sink, new InMemoryCheckpointStore(),
                new ReportingCoordinator(), () -> { });
        try {
            worker.handle(new Command.SetSinkDelay(60_000));
            worker.handle(assignment);
            await("sink took current item", 5000, () -> worker.status().processedRecords() == 1
                    && worker.status().sinkQueue().size() == 0);
            long started = System.nanoTime();
            worker.shutdown();
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(4000);
            assertThat(sink.rows).singleElement().satisfies(row -> assertThat(row.offset()).isZero());
            assertThat(source.closed).isTrue();
            assertThat(worker.status().state()).isEqualTo("IDLE");
        } finally {
            worker.shutdown();
        }
    }

    @Test
    @Timeout(8)
    void unavailableCoordinatorAndPoolCannotExtendSharedShutdownDeadline() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch interrupted = new CountDownLatch(2);
        ReportingCoordinator coordinator = new ReportingCoordinator() {
            @Override
            public HeartbeatResponse heartbeat(String workerId, WorkerStatus status) {
                super.heartbeat(workerId, status);
                block(started, interrupted);
                return new HeartbeatResponse(List.of());
            }
        };
        var worker = new Worker("w-timeout", topic -> new WorkerFixtures.FakeSource(Map.of()),
                new WorkerFixtures.MemorySink(), new InMemoryCheckpointStore(), coordinator, () -> { });
        HttpServer server = WorkerMain.startHealthServer(0, worker::metrics);
        var shutdown = new WorkerMain.Shutdown(worker, coordinator, () -> block(started, interrupted),
                server, new Thread());
        try {
            long before = System.nanoTime();
            shutdown.run();
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before)).isLessThan(4000);
            assertThat(started.getCount()).isZero();
            assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(coordinator.statuses).singleElement().satisfies(status ->
                    assertThat(status.state()).isEqualTo("IDLE"));
        } finally {
            server.stop(0);
            worker.shutdown();
        }
    }

    private static void block(CountDownLatch started, CountDownLatch interrupted) {
        started.countDown();
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException e) {
            interrupted.countDown();
            Thread.currentThread().interrupt();
        }
    }

    private static class ReportingCoordinator implements CoordinatorClient {
        final List<WorkerStatus> statuses = new CopyOnWriteArrayList<>();

        @Override
        public RegisterResponse register(RegisterRequest request) {
            return new RegisterResponse("w-stop", 1000, 4000);
        }

        @Override
        public HeartbeatResponse heartbeat(String workerId, WorkerStatus status) {
            statuses.add(status);
            return new HeartbeatResponse(List.of(new Command.Kill())); // Shutdown must ignore returned commands.
        }

        @Override
        public AckResponse ack(CheckpointAck ack) {
            return new AckResponse(true, null);
        }
    }
}
