package io.aetherstream.engine.worker;

import static io.aetherstream.engine.worker.WorkerFixtures.await;
import static org.assertj.core.api.Assertions.assertThat;

import io.aetherstream.common.protocol.Command;
import io.aetherstream.engine.storage.InMemoryCheckpointStore;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class StageErrorRecoveryTest {
    enum Stage { SOURCE, OPERATOR, SINK }

    @ParameterizedTest
    @EnumSource(Stage.class)
    void stageErrorMakesWorkerIdleAndReleasesRuntime(Stage stage) throws Exception {
        var entered = new CountDownLatch(1);
        var fail = new CountDownLatch(1);
        Runnable crash = () -> {
            entered.countDown();
            try {
                if (!fail.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test did not release failing stage");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            throw new AssertionError("injected " + stage + " failure");
        };
        var source = new WorkerFixtures.FakeSource(Map.of(0, List.of("not json")));
        var store = new InMemoryCheckpointStore() {
            @Override
            public <T> T getJson(String key, Class<T> type) {
                if (stage == Stage.OPERATOR) crash.run();
                return super.getJson(key, type);
            }
        };
        var worker = new Worker("stage-error-test", topic -> {
            if (stage == Stage.SOURCE) crash.run();
            return source;
        }, (execution, generation, rows) -> {
            if (stage == Stage.SINK) crash.run();
            return true;
        }, store, new WorkerFixtures.FakeCoordinator(), () -> { });
        try {
            worker.handle(new Command.Assign("stage-error", 1, WorkerFixtures.plan(1), List.of(0),
                    stage == Stage.OPERATOR ? 1 : 0, null, Map.of(0, 0L)));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            if (stage == Stage.OPERATOR) {
                assertThat(worker.status().state()).isEqualTo("RESTORING");
            } else {
                await("running before failure", 5000, () -> worker.status().state().equals("RUNNING"));
            }
            fail.countDown();
            await("idle after stage Error", 5000, () -> worker.status().state().equals("IDLE"));
            await("runtime cleanup", 5000, () -> worker.runtime() == null);
            assertThat(worker.status().partitions()).isEmpty();
            assertThat(worker.status().executionId()).isEqualTo("stage-error");
            assertThat(worker.status().generation()).isEqualTo(1);
        } finally {
            fail.countDown();
            worker.shutdown();
        }
    }
}
