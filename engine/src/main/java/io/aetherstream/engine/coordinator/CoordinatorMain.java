package io.aetherstream.engine.coordinator;

import io.aetherstream.engine.db.DataSources;
import io.aetherstream.engine.storage.CheckpointStore;
import io.aetherstream.engine.storage.S3CheckpointStore;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Coordinator process: env (contract 00), dependencies, 250 ms tick, HTTP on 8081. */
public final class CoordinatorMain {
    private static final Logger log = LoggerFactory.getLogger(CoordinatorMain.class);
    public static final long TICK_MS = 250;

    private CoordinatorMain() {}

    public static void main(String[] args) throws Exception {
        long checkpointIntervalMs = Long.parseLong(env("CHECKPOINT_INTERVAL_MS", "10000"));
        int port = Integer.parseInt(env("COORDINATOR_PORT", "8081"));
        String bootstrap = env("KAFKA_BOOTSTRAP", "kafka:9092");

        DataSource dataSource = DataSources.fromEnv("coordinator");
        ExecutionRepository repo = new PostgresExecutionRepository(dataSource);
        CheckpointStore store = retry("S3 checkpoint store", S3CheckpointStore::fromEnv);
        Coordinator coordinator = new Coordinator(repo, store, new KafkaOffsetsLookup(bootstrap), Clock.SYSTEM,
                checkpointIntervalMs);
        retry("load active executions", () -> {
            coordinator.loadActiveExecutions();
            return Boolean.TRUE;
        });

        ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "coordinator-tick");
            t.setDaemon(true);
            return t;
        });
        ticker.scheduleWithFixedDelay(() -> {
            try {
                coordinator.tick();
            } catch (Throwable t) {
                log.error("Coordinator tick failed", t);
            }
        }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);

        CoordinatorHttpServer http = new CoordinatorHttpServer(coordinator, port);
        http.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            http.stop();
            ticker.shutdownNow();
        }, "coordinator-shutdown"));
        log.info("Coordinator started (checkpoint interval {} ms)", checkpointIntervalMs);
    }

    /** Retries a startup step for up to ~2 minutes (dependencies may still be starting). */
    private static <T> T retry(String what, Supplier<T> step) throws InterruptedException {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 60; attempt++) {
            try {
                return step.get();
            } catch (RuntimeException e) {
                last = e;
                log.warn("{} failed (attempt {}): {}", what, attempt, e.getMessage());
                Thread.sleep(2000);
            }
        }
        throw last;
    }

    private static String env(String name, String def) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? def : v;
    }
}
