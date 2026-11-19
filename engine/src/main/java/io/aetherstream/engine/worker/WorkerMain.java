package io.aetherstream.engine.worker;

import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariDataSource;
import io.aetherstream.common.protocol.HeartbeatResponse;
import io.aetherstream.common.protocol.Protocol;
import io.aetherstream.common.protocol.RegisterRequest;
import io.aetherstream.common.protocol.RegisterResponse;
import io.aetherstream.engine.db.DataSources;
import io.aetherstream.engine.storage.CheckpointStore;
import io.aetherstream.engine.storage.S3CheckpointStore;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Worker process: coordinator heartbeats, health and Prometheus on 8090, bounded JVM-hook shutdown. */
public final class WorkerMain {
    public static final int HEALTH_PORT = 8090;
    private static final Logger log = LoggerFactory.getLogger(WorkerMain.class);

    private WorkerMain() {}

    public static void main(String[] args) throws Exception {
        String bootstrap = env("KAFKA_BOOTSTRAP", "kafka:9092");
        String coordinatorUrl = env("COORDINATOR_URL", "http://coordinator:8081");
        String host = env("WORKER_HOST", defaultHost());
        String requestedId = env("WORKER_ID", null);
        // Default contract capacity; Compose opts into smaller queues for the low-rate interactive demo.
        int queueCapacity = Integer.parseInt(env("WORKER_QUEUE_CAPACITY",
                Integer.toString(TaskRuntime.QUEUE_CAPACITY)));

        HikariDataSource dataSource = DataSources.fromEnv("worker-sink");
        CheckpointStore store = connectStore();
        CoordinatorClient coordinator = new HttpCoordinatorClient(coordinatorUrl);

        RegisterResponse reg = register(coordinator, requestedId, host);
        String workerId = reg.workerId();
        long interval = reg.heartbeatIntervalMs() > 0 ? reg.heartbeatIntervalMs() : 1000;
        log.info("Registered as {} (heartbeat every {} ms)", workerId, interval);

        RecordSourceFactory sources = topic -> new KafkaRecordSource(bootstrap, topic, "worker-" + workerId);
        Worker worker = new Worker(workerId, sources, new PostgresResultSink(dataSource), store, coordinator,
                () -> Runtime.getRuntime().halt(137), queueCapacity);
        HttpServer health = startHealthServer(HEALTH_PORT, worker::metrics);
        Shutdown shutdown = new Shutdown(worker, coordinator, dataSource, health, Thread.currentThread());
        Runtime.getRuntime().addShutdownHook(new Thread(shutdown, "worker-shutdown"));

        try {
            while (!shutdown.closing.get()) {
                long started = System.currentTimeMillis();
                try {
                    HeartbeatResponse resp = coordinator.heartbeat(workerId, worker.status());
                    if (resp != null && !shutdown.closing.get()) {
                        worker.handleAll(resp.commands());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.warn("Heartbeat failed: {}", e.toString());
                }
                long sleep = interval - (System.currentTimeMillis() - started);
                if (sleep > 0 && !shutdown.closing.get()) {
                    Thread.sleep(sleep);
                }
            }
        } finally {
            shutdown.run();
        }
    }

    /** Retries every second until the coordinator answers. */
    static RegisterResponse register(CoordinatorClient coordinator, String workerId, String host)
            throws InterruptedException {
        while (true) {
            try {
                return coordinator.register(new RegisterRequest(Protocol.VERSION, workerId, host));
            } catch (Exception e) {
                log.info("Register failed ({}), retrying in 1 s", e.toString());
                Thread.sleep(1000);
            }
        }
    }

    private static CheckpointStore connectStore() throws InterruptedException {
        while (true) {
            try {
                return S3CheckpointStore.fromEnv();
            } catch (RuntimeException e) {
                log.info("Checkpoint store not reachable ({}), retrying in 1 s", e.toString());
                Thread.sleep(1000);
            }
        }
    }

    static HttpServer startHealthServer(int port, Supplier<String> metrics) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", ex -> {
            byte[] body = "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);
            int code = "GET".equals(ex.getRequestMethod()) ? 200 : 405;
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(code, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/metrics", ex -> {
            int code = !"/metrics".equals(ex.getRequestURI().getPath()) ? 404
                    : "GET".equals(ex.getRequestMethod()) ? 200 : 405;
            byte[] body = (code == 200 ? metrics.get() : "").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            ex.sendResponseHeaders(code, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        return server;
    }

    /** JVM-hook coordinator: no new commands, one final IDLE report, shared deadline below the 4 s timeout. */
    static final class Shutdown implements Runnable {
        private final Worker worker;
        private final CoordinatorClient coordinator;
        private final AutoCloseable pool;
        private final HttpServer health;
        private final Thread heartbeatThread;
        private final AtomicBoolean closing = new AtomicBoolean();

        Shutdown(Worker worker, CoordinatorClient coordinator, AutoCloseable pool, HttpServer health,
                 Thread heartbeatThread) {
            this.worker = worker;
            this.coordinator = coordinator;
            this.pool = pool;
            this.health = health;
            this.heartbeatThread = heartbeatThread;
        }

        @Override
        public void run() {
            if (!closing.compareAndSet(false, true)) {
                return;
            }
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(3800);
            if (heartbeatThread != Thread.currentThread()) {
                heartbeatThread.interrupt();
                joinUntil(heartbeatThread, Math.min(deadline,
                        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200)));
            }
            // Clear a main-loop interrupt so cleanup can wait for the current sink transaction.
            boolean interrupted = Thread.interrupted();
            try {
                worker.shutdown();
                Thread closePool = Thread.ofVirtual().name("worker-close-pool").start(() -> {
                    try {
                        pool.close();
                    } catch (Exception e) {
                        log.warn("Closing worker DB pool failed: {}", e.toString());
                    }
                });
                Thread reportIdle = Thread.ofVirtual().name("worker-idle-report").start(() -> {
                    try {
                        coordinator.heartbeat(worker.workerId(), worker.status());
                    } catch (Exception e) {
                        log.warn("Final IDLE heartbeat failed: {}", e.toString());
                    }
                });
                joinUntil(closePool, deadline);
                joinUntil(reportIdle, deadline);
            } finally {
                health.stop(0);
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private static void joinUntil(Thread thread, long deadline) {
            try {
                long remaining = deadline - System.nanoTime();
                if (remaining > 0) {
                    TimeUnit.NANOSECONDS.timedJoin(thread, remaining);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (thread.isAlive()) {
                thread.interrupt();
            }
        }
    }

    private static String defaultHost() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return "unknown";
        }
    }

    private static String env(String name, String def) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? def : v;
    }
}
