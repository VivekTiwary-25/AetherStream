package io.aetherstream.api.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class StaleRecoveryPendingTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void staleWorkerDeathDoesNotLabelLaterRebalancesOrContaminateTheNextRecovery() {
        record Rebalance(String state, long delaySeconds) {}
        for (Rebalance scenario : List.of(new Rebalance("RUNNING", 1),
                new Rebalance("RUNNING", 300), new Rebalance("RECOVERING", 300))) {
            AtomicLong clock = new AtomicLong();
            ExecutionEvents tracker = new ExecutionEvents(mapper, clock::get);
            ArrayNode alive = mapper.createArrayNode().add(
                    mapper.createObjectNode().put("workerId", "idle-worker").put("alive", true));
            ArrayNode empty = mapper.createArrayNode();
            tracker.observe(execution("RUNNING", 1), alive);
            clock.set(TimeUnit.SECONDS.toNanos(1));
            assertThat(tracker.observe(execution("RUNNING", 1), empty))
                    .extracting(event -> event.path("event").asText()).containsExactly("WORKER_DEAD");

            // The death did not trigger recovery; a later rebalance is unrelated.
            clock.addAndGet(TimeUnit.SECONDS.toNanos(scenario.delaySeconds()));
            if (scenario.delaySeconds() > 10) {
                assertThat(tracker.observe(execution("RUNNING", 1), empty)).isEmpty();
            }
            List<ObjectNode> rebalanced = tracker.observe(execution(scenario.state(), 2), empty);
            assertThat(rebalanced).extracting(event -> event.path("event").asText())
                    .containsExactly("REBALANCED");
            assertThat(rebalanced.getFirst().has("durationMs")).isFalse();
            assertThat(tracker.observe(execution("RUNNING", 2), empty)).isEmpty();

            tracker.observe(execution("RUNNING", 2), alive);
            clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
            assertThat(tracker.observe(execution("RECOVERING", 3), empty))
                    .extracting(event -> event.path("event").asText())
                    .containsExactly("WORKER_DEAD", "RECOVERING");
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(250));
            List<ObjectNode> recovered = tracker.observe(execution("RUNNING", 3), empty);
            assertThat(recovered).extracting(event -> event.path("event").asText())
                    .containsExactly("RECOVERED");
            assertThat(recovered.getFirst().path("durationMs").asLong(-1)).isEqualTo(250);
        }
    }

    private ObjectNode execution(String state, long generation) {
        return mapper.createObjectNode().put("id", "exec-1").put("state", state)
                .put("generation", generation).put("restoredFromCheckpoint", 7)
                .put("lastCompletedCheckpoint", 7);
    }
}
