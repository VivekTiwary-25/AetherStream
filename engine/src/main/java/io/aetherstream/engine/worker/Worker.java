package io.aetherstream.engine.worker;

import io.aetherstream.common.protocol.Command;
import io.aetherstream.common.protocol.Protocol;
import io.aetherstream.common.protocol.QueueStatus;
import io.aetherstream.common.protocol.WorkerStatus;
import io.aetherstream.engine.storage.CheckpointStore;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Executes coordinator commands (contract 03 section 2) and reports WorkerStatus. Thread-safe. */
public final class Worker {
    private static final Logger log = LoggerFactory.getLogger(Worker.class);

    private final String workerId;
    private final RecordSourceFactory sourceFactory;
    private final ResultSink sink;
    private final CheckpointStore store;
    private final CoordinatorClient coordinator;
    private final Runnable halt;
    private final AtomicLong sinkDelayMs = new AtomicLong();

    private TaskRuntime runtime;          // null when idle
    private String executionId;           // execution of the latest accepted command
    private long generation;              // generation of the latest accepted command for executionId
    private long lastCheckpointId;

    public Worker(String workerId, RecordSourceFactory sourceFactory, ResultSink sink, CheckpointStore store,
                  CoordinatorClient coordinator, Runnable halt) {
        this.workerId = workerId;
        this.sourceFactory = sourceFactory;
        this.sink = sink;
        this.store = store;
        this.coordinator = coordinator;
        this.halt = halt;
    }

    public String workerId() { return workerId; }

    public long sinkDelayMs() { return sinkDelayMs.get(); }

    public synchronized TaskRuntime runtime() { return runtime; }

    public void handleAll(List<Command> commands) {
        if (commands == null) {
            return;
        }
        for (Command c : commands) {
            handle(c);
        }
    }

    public void handle(Command command) {
        if (command instanceof Command.Kill) {
            log.warn("KILL received: halting");
            halt.run();
            return;
        }
        if (command instanceof Command.SetSinkDelay d) {
            sinkDelayMs.set(Math.max(0, d.delayMs()));
            log.info("Sink delay set to {} ms", d.delayMs());
            return;
        }
        synchronized (this) {
            if (command instanceof Command.Assign a) {
                assign(a);
            } else if (command instanceof Command.Cancel c) {
                cancel(c);
            } else if (command instanceof Command.TriggerCheckpoint t) {
                if (runtime != null && !runtime.isStopped() && runtime.executionId().equals(t.executionId())
                        && runtime.generation() == t.generation()) {
                    runtime.triggerCheckpoint(t.checkpointId());
                }
            }
        }
    }

    /**
     * Generations are compared within one execution (a new execution starts again at generation 1, so comparing
     * across executions would ignore every new execution after the first).
     */
    private boolean stale(String execId, long gen) {
        return Objects.equals(execId, executionId) && gen < generation;
    }

    private void assign(Command.Assign a) {
        if (stale(a.executionId(), a.generation())) {
            log.info("Ignoring stale ASSIGN {} g{} (current g{})", a.executionId(), a.generation(), generation);
            return;
        }
        if (runtime != null && !runtime.isStopped() && runtime.executionId().equals(a.executionId())
                && runtime.generation() == a.generation()) {
            return; // idempotent
        }
        stopRuntime();
        executionId = a.executionId();
        generation = a.generation();
        lastCheckpointId = a.restoreCheckpointId();
        runtime = new TaskRuntime(workerId, a, sourceFactory, sink, store, coordinator, sinkDelayMs::get,
                this::onStoppedByItself);
        runtime.start();
    }

    private void cancel(Command.Cancel c) {
        if (stale(c.executionId(), c.generation())) {
            return;
        }
        if (runtime != null && runtime.executionId().equals(c.executionId())) {
            stopRuntime();
        }
        if (Objects.equals(executionId, c.executionId())) {
            generation = Math.max(generation, c.generation());
        }
    }

    private void stopRuntime() {
        if (runtime != null) {
            lastCheckpointId = Math.max(lastCheckpointId, runtime.lastCheckpointId());
            runtime.stop();
            runtime = null;
        }
    }

    private void onStoppedByItself(TaskRuntime r, String reason) {
        // Runs on a runtime thread: stop() the rest asynchronously so it never joins its caller.
        Thread t = new Thread(() -> {
            synchronized (Worker.this) {
                if (runtime == r) {
                    stopRuntime();
                }
            }
        }, "runtime-cleanup");
        t.setDaemon(true);
        t.start();
    }

    public synchronized WorkerStatus status() {
        TaskRuntime r = runtime;
        int cap = TaskRuntime.QUEUE_CAPACITY;
        if (r == null || r.isStopped()) {
            long cp = r == null ? lastCheckpointId : Math.max(lastCheckpointId, r.lastCheckpointId());
            return new WorkerStatus(Protocol.VERSION, workerId, executionId, generation, "IDLE", List.of(),
                    new QueueStatus(0, cap), new QueueStatus(0, cap), false, 0, 0.0, cp, Map.of());
        }
        String state = r.state() == TaskRuntime.State.RUNNING ? "RUNNING" : "RESTORING";
        return new WorkerStatus(Protocol.VERSION, workerId, r.executionId(), r.generation(), state, r.partitions(),
                new QueueStatus(r.sourceQueueSize(), cap), new QueueStatus(r.sinkQueueSize(), cap),
                r.backpressured(), r.processedRecords(), r.recordsPerSec(), r.lastCheckpointId(), r.lag());
    }

    public synchronized void shutdown() {
        stopRuntime();
    }
}
