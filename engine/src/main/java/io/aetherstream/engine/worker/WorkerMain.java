package io.aetherstream.engine.worker;

import com.sun.net.httpserver.HttpServer;
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
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Worker process: register, heartbeat loop executing returned commands, health server on 8090. */
public final class WorkerMain {
    public static final int HEALTH_PORT = 8090;
    private static final Logger log = LoggerFactory.getLogger(WorkerMain.class);

    private WorkerMain() {}

    public static void main(String[] args) throws Exception {
        String bootstrap = env("KAFKA_BOOTSTRAP", "kafka:9092");
        String coordinatorUrl = env("COORDINATOR_URL", "http://coordinator:8081");
        String host = env("WORKER_HOST", defaultHost());
        String requestedId = env("WORKER_ID", null);

        startHealthServer(HEALTH_PORT);

        DataSource dataSource = DataSources.fromEnv("worker-sink");
        CheckpointStore store = connectStore();
        CoordinatorClient coordinator = new HttpCoordinatorClient(coordinatorUrl);

        RegisterResponse reg = register(coordinator, requestedId, host);
        String workerId = reg.workerId();
        long interval = reg.heartbeatIntervalMs() > 0 ? reg.heartbeatIntervalMs() : 1000;
        log.info("Registered as {} (heartbeat every {} ms)", workerId, interval);

        RecordSourceFactory sources = topic -> new KafkaRecordSource(bootstrap, topic, "worker-" + workerId);
        Worker worker = new Worker(workerId, sources, new PostgresResultSink(dataSource), store, coordinator,
                () -> Runtime.getRuntime().halt(137));
        Runtime.getRuntime().addShutdownHook(new Thread(worker::shutdown, "worker-shutdown"));

        while (true) {
            long started = System.currentTimeMillis();
            try {
                HeartbeatResponse resp = coordinator.heartbeat(workerId, worker.status());
                if (resp != null) {
                    worker.handleAll(resp.commands());
                }
            } catch (Exception e) {
                log.warn("Heartbeat failed: {}", e.toString());
            }
            long sleep = interval - (System.currentTimeMillis() - started);
            if (sleep > 0) {
                Thread.sleep(sleep);
            }
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

    static HttpServer startHealthServer(int port) throws IOException {
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
        server.start();
        return server;
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
