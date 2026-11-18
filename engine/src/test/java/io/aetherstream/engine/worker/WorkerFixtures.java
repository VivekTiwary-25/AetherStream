package io.aetherstream.engine.worker;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aetherstream.common.Json;
import io.aetherstream.common.pipeline.ExecutionPlan;
import io.aetherstream.common.protocol.AckResponse;
import io.aetherstream.common.protocol.CheckpointAck;
import io.aetherstream.common.protocol.HeartbeatResponse;
import io.aetherstream.common.protocol.RegisterRequest;
import io.aetherstream.common.protocol.RegisterResponse;
import io.aetherstream.common.protocol.WorkerStatus;
import io.aetherstream.compiler.PipelineCompiler;
import io.aetherstream.compiler.ops.ListCollector;
import io.aetherstream.compiler.ops.PartitionPipeline;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

final class WorkerFixtures {
    static final long T0 = 1795251600000L;

    private WorkerFixtures() {}

    static ExecutionPlan plan(int partitions) {
        return new PipelineCompiler().plan(PipelineCompiler.demoPipeline(), partitions);
    }

    /** Deterministic truck workload: positions (one stopped truck per partition), deliveries, bad JSON, dups, late. */
    static Map<Integer, List<String>> workload(int partitions, int eventsPerTruck) {
        Map<Integer, List<String>> data = new TreeMap<>();
        for (int p = 0; p < partitions; p++) {
            List<String> records = new ArrayList<>();
            String moving = "truck-" + p + "a";
            String stopped = "truck-" + p + "b";
            for (int i = 0; i < eventsPerTruck; i++) {
                long t = T0 + i * 2000L;
                records.add(position(moving, i, t, 20 + (i % 7)));
                records.add(position(stopped, i, t + 500, 0));
                if (i % 40 == 39) {
                    records.add(delivery(moving, i, t + 1000, 1 + i % 3));
                }
                if (i % 97 == 50) {
                    records.add("{not json " + i);
                }
                if (i % 53 == 20) {
                    records.add(position(moving, i, t, 20 + (i % 7))); // duplicate eventId
                }
                if (i % 131 == 100) {
                    records.add(position(moving, 100_000 + i, t - 300_000, 5)); // late
                }
            }
            data.put(p, records);
        }
        return data;
    }

    private static String position(String truck, int seq, long t, double speed) {
        ObjectNode e = envelope(truck, seq, "truck.position", t);
        e.putObject("payload").put("truckId", truck).put("x", 10.0).put("y", 20.0).put("speedKmh", speed)
                .put("heading", 90.0).put("routeId", "r-1").put("status", speed == 0 ? "STOPPED" : "DRIVING");
        return e.toString();
    }

    private static String delivery(String truck, int seq, long t, int parcels) {
        ObjectNode e = envelope(truck, 500_000 + seq, "truck.delivery", t);
        e.putObject("payload").put("truckId", truck).put("stopId", "stop-1").put("x", 10.0).put("y", 20.0)
                .put("parcels", parcels);
        return e.toString();
    }

    private static ObjectNode envelope(String truck, int seq, String type, long t) {
        ObjectNode e = Json.MAPPER.createObjectNode();
        e.put("eventId", "s1-" + truck + "-" + String.format("%06d", seq)).put("schemaVersion", 1).put("type", type)
                .put("eventTime", t).put("key", truck);
        return e;
    }

    /** Runs PartitionPipeline directly over every record of each partition. */
    static Map<Integer, List<SinkRow>> direct(ExecutionPlan plan, Map<Integer, List<String>> data) {
        Map<Integer, List<SinkRow>> out = new TreeMap<>();
        for (Map.Entry<Integer, List<String>> e : data.entrySet()) {
            int p = e.getKey();
            PartitionPipeline pipeline = new PartitionPipeline(plan, p);
            List<SinkRow> rows = new ArrayList<>();
            for (int o = 0; o < e.getValue().size(); o++) {
                ListCollector c = new ListCollector();
                pipeline.process(o, e.getValue().get(o), c);
                for (ListCollector.Output output : c.outputs()) {
                    rows.add(new SinkRow(p, o, output));
                }
            }
            out.put(p, rows);
        }
        return out;
    }

    static void await(String what, long timeoutMs, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(5);
        }
    }

    /** In-memory RecordSource over fixed lists; emits round-robin over partitions, gated by a total limit. */
    static final class FakeSource implements RecordSource {
        final Map<Integer, List<String>> data;
        final AtomicLong limit = new AtomicLong(Long.MAX_VALUE);
        final AtomicLong emitted = new AtomicLong();
        final AtomicBoolean closed = new AtomicBoolean();
        final List<Long> lagRefreshNanos = new CopyOnWriteArrayList<>();
        volatile Thread owner;
        volatile Thread lagThread;
        volatile Thread closeThread;
        private final Map<Integer, Long> next = new TreeMap<>();
        private final Map<Integer, Long> emittedPerPartition = new TreeMap<>();

        FakeSource(Map<Integer, List<String>> data) {
            this.data = data;
        }

        @Override
        public synchronized void assign(Collection<Integer> partitions, Map<Integer, Long> startOffsets) {
            owner = Thread.currentThread();
            for (int p : partitions) {
                next.put(p, startOffsets.getOrDefault(p, 0L));
                emittedPerPartition.put(p, 0L);
            }
        }

        @Override
        public List<SourceRecord> poll(Duration timeout) {
            List<SourceRecord> out = new ArrayList<>();
            synchronized (this) {
                boolean progress = true;
                while (progress && out.size() < 500 && emitted.get() < limit.get()) {
                    progress = false;
                    for (Map.Entry<Integer, Long> e : next.entrySet()) {
                        if (out.size() >= 500 || emitted.get() >= limit.get()) {
                            break;
                        }
                        List<String> records = data.get(e.getKey());
                        long o = e.getValue();
                        if (records != null && o < records.size()) {
                            out.add(new SourceRecord(e.getKey(), o, records.get((int) o)));
                            e.setValue(o + 1);
                            emittedPerPartition.merge(e.getKey(), 1L, Long::sum);
                            emitted.incrementAndGet();
                            progress = true;
                        }
                    }
                }
            }
            if (out.isEmpty()) {
                try {
                    Thread.sleep(Math.min(10, timeout.toMillis()));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted", ex);
                }
            }
            return out;
        }

        @Override
        public synchronized Map<Integer, Long> nextOffsets() {
            return new TreeMap<>(next);
        }

        @Override
        public synchronized Map<Integer, Long> endOffsets() {
            lagThread = Thread.currentThread();
            lagRefreshNanos.add(System.nanoTime());
            Map<Integer, Long> end = new TreeMap<>();
            for (int p : next.keySet()) {
                end.put(p, (long) data.getOrDefault(p, List.of()).size());
            }
            return end;
        }

        @Override
        public void wakeup() {
            Thread thread = owner;
            if (thread != null) {
                thread.interrupt();
            }
        }

        @Override
        public void close() {
            closed.set(true);
            closeThread = Thread.currentThread();
        }
    }

    /** In-memory sink; when fenced, writes nothing and returns false. */
    static final class MemorySink implements ResultSink {
        final List<SinkRow> rows = new CopyOnWriteArrayList<>();
        final AtomicBoolean fenced = new AtomicBoolean();
        final AtomicLong rejectedWrites = new AtomicLong();

        @Override
        public boolean write(String executionId, long generation, List<SinkRow> batch) {
            if (fenced.get()) {
                rejectedWrites.incrementAndGet();
                return false;
            }
            rows.addAll(batch);
            return true;
        }

        List<SinkRow> partition(int p) {
            return rows.stream().filter(r -> r.partition() == p).toList();
        }
    }

    static final class FakeCoordinator implements CoordinatorClient {
        final List<CheckpointAck> acks = new CopyOnWriteArrayList<>();

        @Override
        public RegisterResponse register(RegisterRequest request) {
            return new RegisterResponse("w-test", 1000, 4000);
        }

        @Override
        public HeartbeatResponse heartbeat(String workerId, WorkerStatus status) {
            return new HeartbeatResponse(List.of());
        }

        @Override
        public AckResponse ack(CheckpointAck ack) {
            acks.add(ack);
            return new AckResponse(true, null);
        }
    }
}
