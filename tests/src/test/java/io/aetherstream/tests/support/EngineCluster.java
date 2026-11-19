package io.aetherstream.tests.support;

import io.aetherstream.common.protocol.HeartbeatResponse;
import io.aetherstream.common.protocol.Protocol;
import io.aetherstream.common.protocol.RegisterRequest;
import io.aetherstream.engine.coordinator.Clock;
import io.aetherstream.engine.coordinator.Coordinator;
import io.aetherstream.engine.coordinator.CoordinatorHttpServer;
import io.aetherstream.engine.coordinator.KafkaOffsetsLookup;
import io.aetherstream.engine.coordinator.PostgresExecutionRepository;
import io.aetherstream.engine.storage.S3CheckpointStore;
import io.aetherstream.engine.worker.HttpCoordinatorClient;
import io.aetherstream.engine.worker.KafkaRecordSource;
import io.aetherstream.engine.worker.PostgresResultSink;
import io.aetherstream.engine.worker.Worker;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.postgresql.ds.PGSimpleDataSource;

/** Real HTTP coordination, Kafka sources, fenced Postgres sinks and S3 checkpoints in one JVM. */
public final class EngineCluster implements AutoCloseable {
    private final List<WorkerSlot> workers = new ArrayList<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final ScheduledExecutorService loops;
    private CoordinatorHttpServer http;
    private Coordinator coordinator;
    private final Object coordinatorLock = new Object();

    private final PGSimpleDataSource dataSource;
    private final S3CheckpointStore store;
    private boolean stopped;

    private static final class WorkerSlot {
        final Worker worker;
        final Object heartbeatLock = new Object();
        ScheduledFuture<?> heartbeat;
        volatile boolean crashed;

        WorkerSlot(Worker worker) { this.worker = worker; }
    }
    public EngineCluster(int workerCount) throws Exception {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(IntegrationInfrastructure.jdbcUrl());
        dataSource.setUser("aether");
        dataSource.setPassword("aether");
        store = new S3CheckpointStore(IntegrationInfrastructure.s3Endpoint(), "aether", "aether-secret",
                IntegrationInfrastructure.BUCKET);
        coordinator = new Coordinator(new PostgresExecutionRepository(dataSource), store,
                new KafkaOffsetsLookup(IntegrationInfrastructure.bootstrap()), Clock.SYSTEM, 2000);
        coordinator.loadActiveExecutions();
        http = new CoordinatorHttpServer(coordinator, 0);
        loops = Executors.newScheduledThreadPool(Math.max(2, workerCount + 1));
        try {
            http.start();
            loops.scheduleWithFixedDelay(() -> {
                synchronized (coordinatorLock) {
                    guarded(coordinator::tick);
                }
            }, 250, 250, TimeUnit.MILLISECONDS);
            for (int i = 0; i < workerCount; i++) {
                addWorker();
            }
        } catch (Exception | Error e) {
            stop();
            throw e;
        }
    }

    public Worker worker(int index) { return workers.get(index).worker; }

    /** Registers a fresh identity; it joins the next coordinator assignment. */
    public synchronized Worker addWorker() throws Exception {
        if (stopped) throw new IllegalStateException("Cluster is stopped");
        int index = workers.size();
        var client = new HttpCoordinatorClient(coordinatorUrl());
        var registration = client.register(new RegisterRequest(Protocol.VERSION, "e2e-worker-" + index, "localhost"));
        String workerId = registration.workerId();
        var worker = new Worker(workerId,
                topic -> new KafkaRecordSource(IntegrationInfrastructure.bootstrap(), topic, workerId),
                new PostgresResultSink(dataSource), store, client, () -> crash(index));
        var slot = new WorkerSlot(worker);
        workers.add(slot);
        slot.heartbeat = loops.scheduleWithFixedDelay(() -> {
            synchronized (slot.heartbeatLock) {
                if (!slot.crashed) {
                    guarded(() -> {
                        HeartbeatResponse response;
                        synchronized (coordinatorLock) {
                            response = client.heartbeat(workerId, worker.status());
                        }
                        worker.handleAll(response.commands());
                    });
                }
            }
        }, 0, registration.heartbeatIntervalMs(), TimeUnit.MILLISECONDS);
        return worker;
    }

    /** Drops queued/pending output and barriers, without the graceful sink flush in Worker.shutdown(). */
    public void crash(int index) {
        WorkerSlot slot = workers.get(index);
        slot.crashed = true;
        slot.heartbeat.cancel(false);
        synchronized (slot.heartbeatLock) {
            var runtime = slot.worker.runtime();
            if (runtime != null) runtime.stop();
        }
    }

    /** Replaces only the coordinator, preserving its address, durable stores and live worker runtimes. */
    public synchronized void restartCoordinator() throws Exception {
        if (stopped) throw new IllegalStateException("Cluster is stopped");
        synchronized (coordinatorLock) {
            int port = http.port();
            // No execution stop or graceful checkpoint: discard all coordinator in-memory state.
            http.stop();
            coordinator = new Coordinator(new PostgresExecutionRepository(dataSource), store,
                    new KafkaOffsetsLookup(IntegrationInfrastructure.bootstrap()), Clock.SYSTEM, 2000);
            coordinator.loadActiveExecutions();
            http = new CoordinatorHttpServer(coordinator, port);
            http.start();
        }
    }

    public String coordinatorUrl() { return "http://localhost:" + http.port(); }

    public void checkHealthy() {
        Throwable cause = failure.get();
        if (cause != null) throw new AssertionError("Engine cluster background loop failed", cause);
    }

    private void guarded(CheckedAction action) {
        try {
            action.run();
        } catch (Throwable e) {
            if (!loops.isShutdown()) failure.compareAndSet(null, e);
        }
    }

    public synchronized void stop() {
        if (stopped) return;
        stopped = true;
        loops.shutdownNow();
        try {
            if (!loops.awaitTermination(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Engine cluster loops did not stop");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted stopping engine cluster", e);
        } finally {
            try {
                for (WorkerSlot slot : workers) slot.worker.shutdown();
            } finally {
                http.stop();
            }
        }
    }

    @Override public void close() { stop(); }

    @FunctionalInterface
    private interface CheckedAction { void run() throws Exception; }
}
