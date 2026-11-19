package io.aetherstream.engine.worker;

import static io.aetherstream.engine.worker.WorkerFixtures.await;
import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.aetherstream.common.protocol.Command;
import io.aetherstream.common.protocol.QueueStatus;
import io.aetherstream.common.protocol.WorkerStatus;
import io.aetherstream.engine.storage.InMemoryCheckpointStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkerMetricsTest {
    @Test
    void rendersEveryMetricWithEscapedIdentityAndExactIntegerValues() {
        WorkerStatus status = new WorkerStatus(1, "w\\\"\n1", "e1", 7, "RUNNING", List.of(0, 2),
                new QueueStatus(900, 1000), new QueueStatus(12, 1000), true, 3, 12.5, 42,
                Map.of(2, 9007199254740993L, 0, 17L));
        String labels = "worker=\"w\\\\\\\"\\n1\"";
        String text = WorkerMetrics.render(status, 9007199254740993L, 19, 2);
        assertThat(text).contains(
                "# TYPE aether_worker_processed_records_total counter\n",
                "aether_worker_processed_records_total{" + labels + "} 9007199254740993\n",
                "aether_worker_records_per_sec{" + labels + "} 12.5\n",
                "aether_worker_queue_size{" + labels + ",queue=\"source\"} 900\n",
                "aether_worker_queue_size{" + labels + ",queue=\"sink\"} 12\n",
                "aether_worker_queue_capacity{" + labels + ",queue=\"source\"} 1000\n",
                "aether_worker_queue_capacity{" + labels + ",queue=\"sink\"} 1000\n",
                "aether_worker_backpressured{" + labels + "} 1\n",
                "# TYPE aether_worker_consumer_lag gauge\n",
                "aether_worker_consumer_lag{" + labels + ",partition=\"0\"} 17\n",
                "aether_worker_consumer_lag{" + labels + ",partition=\"2\"} 9007199254740993\n",
                "aether_worker_generation{" + labels + "} 7\n",
                "aether_worker_last_checkpoint_id{" + labels + "} 42\n",
                "aether_worker_sink_batches_total{" + labels + "} 19\n",
                "aether_worker_fenced_total{" + labels + "} 2\n");
        assertThat(text.lines().filter(line -> !line.startsWith("#")))
                .allSatisfy(line -> assertThat(line).contains(labels));
    }

    @Test
    void servesLiveMetricsAndPreservesCountersAfterCancellationAndFencing() throws Exception {
        var plan = WorkerFixtures.plan(1);
        var source = new WorkerFixtures.FakeSource(Map.of(0, List.of("bad-json", "also-bad")));
        var sink = new WorkerFixtures.MemorySink();
        var worker = new Worker("w-http", topic -> source, sink, new InMemoryCheckpointStore(),
                new WorkerFixtures.FakeCoordinator(), () -> { });
        HttpServer server = WorkerMain.startHealthServer(0, worker::metrics);
        try (HttpClient http = HttpClient.newHttpClient()) {
            URI base = URI.create("http://localhost:" + server.getAddress().getPort());
            assertThat(get(http, base.resolve("/health")).body()).isEqualTo("{\"status\":\"UP\"}");
            worker.handle(new Command.Assign("e1", 1, plan, List.of(0), 0, null, Map.of(0, 0L)));
            await("two records processed", 5_000, () -> worker.status().processedRecords() == 2
                    && sink.rows.size() == 2);
            worker.handle(new Command.TriggerCheckpoint("e1", 1, 1));
            await("checkpoint completed", 5_000, () -> worker.status().lastCheckpointId() == 1);
            HttpResponse<String> running = get(http, base.resolve("/metrics"));
            assertThat(running.statusCode()).isEqualTo(200);
            assertThat(running.headers().firstValue("Content-Type")).hasValue("text/plain; version=0.0.4; charset=utf-8");
            assertThat(running.body()).contains("aether_worker_processed_records_total{worker=\"w-http\"} 2\n",
                    "aether_worker_generation{worker=\"w-http\"} 1\n",
                    "aether_worker_last_checkpoint_id{worker=\"w-http\"} 1\n");
            assertThat(running.body()).contains("aether_worker_consumer_lag{worker=\"w-http\",partition=\"0\"}");
            worker.handle(new Command.Cancel("e1", 1));
            String idle = get(http, base.resolve("/metrics")).body();
            assertThat(idle).contains("aether_worker_processed_records_total{worker=\"w-http\"} 2\n");
            assertThat(idle).contains("aether_worker_consumer_lag{worker=\"w-http\",partition=\"\"} NaN\n")
                    .doesNotContain("partition=\"0\"");
            assertThat(sample(idle, "sink_batches_total")).isEqualTo(sample(running.body(), "sink_batches_total"));
            sink.fenced.set(true);
            worker.handle(new Command.Assign("e1", 2, plan, List.of(0), 0, null, Map.of(0, 0L)));
            await("fenced assignment idle", 5_000, () -> worker.runtime() == null);
            String fenced = get(http, base.resolve("/metrics")).body();
            assertThat(fenced).contains("aether_worker_processed_records_total{worker=\"w-http\"} 4\n",
                    "aether_worker_fenced_total{worker=\"w-http\"} 1\n");
            assertThat(sample(fenced, "sink_batches_total")).isEqualTo(sample(idle, "sink_batches_total"));
            HttpResponse<String> method = http.send(HttpRequest.newBuilder(base.resolve("/metrics"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(method.statusCode()).isEqualTo(405);
            assertThat(get(http, base.resolve("/metrics/not-a-route")).statusCode()).isEqualTo(404);
        } finally {
            server.stop(0);
            worker.shutdown();
        }
    }

    private static HttpResponse<String> get(HttpClient http, URI uri) throws Exception {
        return http.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static long sample(String text, String metric) {
        String prefix = "aether_worker_" + metric + "{";
        String line = text.lines().filter(value -> value.startsWith(prefix)).findFirst().orElseThrow();
        return Long.parseLong(line.substring(line.lastIndexOf(' ') + 1));
    }
}
