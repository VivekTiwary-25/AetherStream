package io.aetherstream.tests.bench;

import io.aetherstream.common.protocol.Protocol;
import io.aetherstream.common.protocol.RegisterRequest;
import io.aetherstream.engine.storage.S3CheckpointStore;
import io.aetherstream.engine.worker.HttpCoordinatorClient;
import io.aetherstream.engine.worker.KafkaRecordSource;
import io.aetherstream.engine.worker.PostgresResultSink;
import io.aetherstream.engine.worker.Worker;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.postgresql.ds.PGSimpleDataSource;

/** Benchmark-only process entry point; no fixed health port, so workers can share a runner. */
public final class BenchmarkWorkerMain {
    private static final Logger log = LoggerFactory.getLogger(BenchmarkWorkerMain.class);
    private BenchmarkWorkerMain() {}

    public static void main(String[] args) throws Exception {
        String bootstrap = args[0];
        var source = new PGSimpleDataSource();
        source.setURL(args[1]);
        source.setUser("aether");
        source.setPassword("aether");
        var store = new S3CheckpointStore(args[2], "aether", "aether-secret", "aether-checkpoints");
        var client = new HttpCoordinatorClient(args[3]);
        var registration = client.register(new RegisterRequest(Protocol.VERSION, args[4], "localhost"));
        String id = registration.workerId();
        var worker = new Worker(id, topic -> new KafkaRecordSource(bootstrap, topic, id),
                new PostgresResultSink(source), store, client, () -> Runtime.getRuntime().halt(137));
        try {
            while (true) {
                long started = System.nanoTime();
                try {
                    worker.handleAll(client.heartbeat(id, worker.status()).commands());
                } catch (IOException e) {
                    // Match WorkerMain: a transient transport failure must not become a second worker crash.
                    log.warn("Heartbeat failed: {}", e.toString());
                }
                long sleepMs = registration.heartbeatIntervalMs() - (System.nanoTime() - started) / 1_000_000;
                if (sleepMs > 0) Thread.sleep(sleepMs);
            }
        } finally {
            worker.shutdown();
        }
    }
}
