package io.aetherstream.engine.worker;

import static io.aetherstream.engine.worker.WorkerFixtures.await;
import static org.assertj.core.api.Assertions.assertThat;

import io.aetherstream.common.protocol.Command;
import io.aetherstream.engine.storage.InMemoryCheckpointStore;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BackpressureTest {
    private final WorkerFixtures.FakeSource source = new WorkerFixtures.FakeSource(
            WorkerFixtures.workload(1, 200));
    private final SinkClock clock = new SinkClock();
    private TaskRuntime runtime;

    @AfterEach
    void stop() {
        if (runtime != null) {
            runtime.stop();
        }
    }

    private void start(long recordLimit) {
        source.limit.set(recordLimit);
        var assign = new Command.Assign("pressure-demo", 1, WorkerFixtures.plan(1), List.of(0),
                0, null, Map.of(0, 0L));
        runtime = new TaskRuntime("w-demo", assign, topic -> source, new WorkerFixtures.MemorySink(),
                new InMemoryCheckpointStore(), new WorkerFixtures.FakeCoordinator(), clock::delayMs,
                null, 32, clock::nanoTime);
        runtime.start();
    }

    @Test
    void fractionalNinetyPercentThresholdRoundsUpAndClearsBelowIt() throws Exception {
        start(29);
        await("first delayed sink item", 5_000, () -> clock.delaysStarted.get() == 1);
        await("28 queued items", 5_000, () -> runtime.sinkQueueSize() == 28);
        assertThat(runtime.backpressured()).isFalse(); // 28/32 is only 87.5%.

        source.limit.set(30);
        await("29 queued items", 5_000, () -> runtime.sinkQueueSize() == 29);
        assertThat(runtime.backpressured()).isTrue();

        clock.advance();
        await("next delayed sink item", 5_000, () -> clock.delaysStarted.get() == 2);
        assertThat(runtime.sinkQueueSize()).isEqualTo(28);
        assertThat(runtime.backpressured()).isFalse();
    }

    @Test
    void demoRateSignalsAndClearsWithinTenSecondsWithoutFillingThousandItemQueues() throws Exception {
        start(0);
        // 15 inputs/s on a busy demo worker, versus the chaos sink's 5 items/s.
        for (int tick = 0; tick < 35; tick++) {
            long records = source.limit.addAndGet(3);
            await("paced input processed", 5_000, () -> runtime.processedRecords() == records);
            long expectedDelays = tick + 1L;
            await("sink delay scheduled", 5_000, () -> clock.delaysStarted.get() == expectedDelays);
            if (runtime.backpressured()) {
                break;
            }
            clock.advance();
        }
        assertThat(runtime.backpressured()).isTrue();
        // Budget one second each for command delivery, the next heartbeat and the WS poll.
        assertThat(clock.millis() + 3_000).isLessThanOrEqualTo(10_000);
        assertThat(runtime.sinkQueueSize()).isBetween(29, 32);

        long clearedAt = clock.millis();
        clock.delay.set(0);
        clock.advance(); // Finish the item whose 200 ms delay was already scheduled.
        long records = source.limit.addAndGet(3); // Intake continues when the slowdown ends.
        await("continued input processed", 5_000, () -> runtime.processedRecords() == records);
        await("sink drained", 5_000, () -> runtime.sinkQueueSize() == 0);
        assertThat(runtime.backpressured()).isFalse();
        assertThat(clock.millis() - clearedAt + 3_000).isLessThanOrEqualTo(10_000);
    }

    /** Only the sink reads this monotonic clock; tests advance it after a deadline is scheduled. */
    private static final class SinkClock {
        private final AtomicLong now = new AtomicLong();
        private final AtomicLong delay = new AtomicLong(200);
        private final AtomicLong delaysStarted = new AtomicLong();
        private boolean schedulingDelay; // sink-thread only

        long delayMs() {
            long value = delay.get();
            schedulingDelay = value > 0;
            return value;
        }

        long nanoTime() {
            long sampled = now.get();
            if (schedulingDelay) {
                schedulingDelay = false;
                // Publish only after sampling the deadline's base, so advance cannot extend this delay.
                delaysStarted.incrementAndGet();
            }
            return sampled;
        }

        void advance() {
            now.addAndGet(TimeUnit.MILLISECONDS.toNanos(200));
        }

        long millis() {
            return TimeUnit.NANOSECONDS.toMillis(now.get());
        }
    }
}
