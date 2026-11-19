package io.aetherstream.engine.worker;

import static io.aetherstream.engine.worker.WorkerFixtures.await;
import static org.assertj.core.api.Assertions.assertThat;

import io.aetherstream.common.pipeline.ExecutionPlan;
import io.aetherstream.common.protocol.Command;
import io.aetherstream.engine.storage.InMemoryCheckpointStore;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Many CANCEL/ASSIGN cycles (a presenter killing workers for an hour) must not leak threads or sources. */
class WorkerCycleLeakTest {
    private static final String EXEC = "e-leak";
    private final ExecutionPlan plan = WorkerFixtures.plan(2);
    private final Map<Integer, List<String>> data = WorkerFixtures.workload(2, 50);
    private final List<WorkerFixtures.FakeSource> sources = new CopyOnWriteArrayList<>();
    private final Worker worker = new Worker("w-leak", topic -> {
        WorkerFixtures.FakeSource s = new WorkerFixtures.FakeSource(data);
        sources.add(s);
        return s;
    }, new WorkerFixtures.MemorySink(), new InMemoryCheckpointStore(), new WorkerFixtures.FakeCoordinator(), () -> { });

    @AfterEach
    void tearDown() {
        worker.shutdown();
    }

    private static long runtimeThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.isAlive() && t.getName().contains(EXEC + "-g"))
                .count();
    }

    @Test
    void assignCancelCyclesReleaseEveryThreadAndSource() throws Exception {
        for (int g = 1; g <= 150; g++) {
            worker.handle(new Command.Assign(EXEC, g, plan, List.of(0, 1), 0, null, Map.of(0, 0L, 1, 0L)));
            if (g % 3 == 0) {
                worker.handle(new Command.TriggerCheckpoint(EXEC, g, g));
            }
            if (g % 2 == 0) {
                worker.handle(new Command.Cancel(EXEC, g)); // CANCEL then the next ASSIGN
            }
            // odd generations are replaced directly by the next ASSIGN (recovery without a separate CANCEL)
        }
        worker.handle(new Command.Cancel(EXEC, 150));
        assertThat(worker.runtime()).isNull();
        await("runtime threads exit", 10_000, () -> runtimeThreads() == 0);
        await("every source closed", 10_000, () -> sources.stream().allMatch(s -> s.closed.get()));
        assertThat(sources).hasSizeLessThanOrEqualTo(150);
        assertThat(worker.status().state()).isEqualTo("IDLE");
        assertThat(worker.status().generation()).isEqualTo(150);
    }
}
