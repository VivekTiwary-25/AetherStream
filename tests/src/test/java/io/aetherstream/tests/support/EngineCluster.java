package io.aetherstream.tests.support;

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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.postgresql.ds.PGSimpleDataSource;

/** Real HTTP coordination, Kafka sources, fenced Postgres sinks and S3 checkpoints in one JVM. */
public final class EngineCluster implements AutoCloseable {
    private final List<Worker> workers = new ArrayList<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final ScheduledExecutorService loops;
    private final CoordinatorHttpServer http;

    public EngineCluster(int workerCount) throws Exception {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(IntegrationInfrastructure.jdbcUrl());
        dataSource.setUser("aether");
        dataSource.setPassword("aether");
        var store = new S3CheckpointStore(IntegrationInfrastructure.s3Endpoint(), "aether", "aether-secret",
                IntegrationInfrastructure.BUCKET);
        var coordinator = new Coordinator(new PostgresExecutionRepository(dataSource), store,
                new KafkaOffsetsLookup(IntegrationInfrastructure.bootstrap()), Clock.SYSTEM, 2000);
        coordinator.loadActiveExecutions();
        http = new CoordinatorHttpServer(coordinator, 0);
        loops = Executors.newScheduledThreadPool(workerCount + 1);
        try {
            http.start();
            loops.scheduleWithFixedDelay(() -> guarded(coordinator::tick), 250, 250, TimeUnit.MILLISECONDS);
            for (int i = 0; i < workerCount; i++) {
                var client = new HttpCoordinatorClient(coordinatorUrl());
                var registration = client.register(new RegisterRequest(Protocol.VERSION, "e2e-worker-" + i, "localhost"));
                String workerId = registration.workerId();
                var worker = new Worker(workerId,
                        topic -> new KafkaRecordSource(IntegrationInfrastructure.bootstrap(), topic, workerId),
                        new PostgresResultSink(dataSource), store, client,
                        () -> { throw new IllegalStateException("KILL requested for in-process worker " + workerId); });
                workers.add(worker);
                loops.scheduleWithFixedDelay(() -> guarded(() -> worker.handleAll(
                        client.heartbeat(workerId, worker.status()).commands())),
                        0, registration.heartbeatIntervalMs(), TimeUnit.MILLISECONDS);
            }
        } catch (Exception | Error e) {
            stop();
            throw e;
        }
    }

    public Worker worker(int index) { return workers.get(index); }

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

    public void stop() {
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
                for (Worker worker : workers) worker.shutdown();
            } finally {
                http.stop();
            }
        }
    }

    @Override public void close() { stop(); }

    @FunctionalInterface
    private interface CheckedAction { void run() throws Exception; }
}
