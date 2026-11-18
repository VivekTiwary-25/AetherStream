package io.aetherstream.engine.worker;

import io.aetherstream.common.protocol.WorkerStatus;
import java.util.TreeMap;

/** Dependency-free Prometheus 0.0.4 exposition. All samples carry the registered worker identity. */
final class WorkerMetrics {
    private WorkerMetrics() {}

    static String render(WorkerStatus status, long processed, long sinkBatches, long fenced) {
        StringBuilder out = new StringBuilder(2048);
        String worker = "worker=\"" + escape(status.workerId()) + "\"";
        metric(out, "processed_records_total", "counter", worker, processed);
        metric(out, "records_per_sec", "gauge", worker, status.recordsPerSec());
        type(out, "queue_size", "gauge");
        sample(out, "queue_size", worker + ",queue=\"source\"", status.sourceQueue().size());
        sample(out, "queue_size", worker + ",queue=\"sink\"", status.sinkQueue().size());
        type(out, "queue_capacity", "gauge");
        sample(out, "queue_capacity", worker + ",queue=\"source\"", status.sourceQueue().capacity());
        sample(out, "queue_capacity", worker + ",queue=\"sink\"", status.sinkQueue().capacity());
        metric(out, "backpressured", "gauge", worker, status.backpressured() ? 1 : 0);
        type(out, "lag", "gauge");
        new TreeMap<>(status.lag()).forEach((partition, lag) ->
                sample(out, "lag", worker + ",partition=\"" + partition + "\"", lag));
        metric(out, "generation", "gauge", worker, status.generation());
        metric(out, "last_checkpoint_id", "gauge", worker, status.lastCheckpointId());
        metric(out, "sink_batches_total", "counter", worker, sinkBatches);
        metric(out, "fenced_total", "counter", worker, fenced);
        return out.toString();
    }

    private static void metric(StringBuilder out, String name, String type, String labels, long value) {
        type(out, name, type);
        sample(out, name, labels, value);
    }

    private static void metric(StringBuilder out, String name, String type, String labels, double value) {
        type(out, name, type);
        out.append("aether_worker_").append(name).append('{').append(labels).append("} ").append(value).append('\n');
    }

    private static void type(StringBuilder out, String name, String type) {
        out.append("# TYPE aether_worker_").append(name).append(' ').append(type).append('\n');
    }

    private static void sample(StringBuilder out, String name, String labels, long value) {
        out.append("aether_worker_").append(name).append('{').append(labels).append("} ").append(value).append('\n');
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
