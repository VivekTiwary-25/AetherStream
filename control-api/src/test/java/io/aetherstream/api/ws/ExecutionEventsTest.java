package io.aetherstream.api.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ExecutionEventsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong clockNanos = new AtomicLong();
    private final ExecutionEvents tracker = new ExecutionEvents(mapper, clockNanos::get);

    @Test void initialRunningAndCreatedEmitStartedWithoutHistoricalCheckpointEvents() {
        ObjectNode execution = execution("RUNNING", 1, null, 7L);
        List<ObjectNode> events = tracker.observe(execution, workers());

        assertEvents(events, "STARTED");
        assertThat(events.getFirst().path("pipelineId").asText()).isEqualTo("pipeline-1");
        assertThat(events.getFirst().path("lastCompletedCheckpoint").asLong()).isEqualTo(7);
        assertThat(events.getFirst().has("checkpointId")).isFalse();
        assertThat(tracker.observe(execution.deepCopy(), workers())).isEmpty();

        tracker.reset();
        assertEvents(tracker.observe(execution("CREATED", 0, null, null), workers()), "STARTED");
    }

    @Test void createdToRunningIsStarted() {
        tracker.observe(execution("CREATED", 0, null, null), workers());
        assertEvents(tracker.observe(execution("RUNNING", 0, null, null), workers()), "STARTED");
    }

    @Test void newExecutionStartsWithANewCheckpointBaseline() {
        tracker.observe(execution("RUNNING", 1, null, 7L), workers());
        ObjectNode next = execution("RUNNING", 1, null, 20L).put("id", "exec-2");

        assertEvents(tracker.observe(next, workers()), "STARTED");
        assertThat(tracker.observe(next, workers())).isEmpty();
        next.put("lastCompletedCheckpoint", 21);
        assertEvents(tracker.observe(next, workers()), "CHECKPOINT_COMPLETED");
    }

    @Test void checkpointChangesAreIndependentOfExecutionStateChanges() {
        tracker.observe(execution("RUNNING", 1, null, null), workers());
        List<ObjectNode> events = tracker.observe(execution("RUNNING", 1, null, 0L), workers());

        assertEvents(events, "CHECKPOINT_COMPLETED");
        assertThat(events.getFirst().path("checkpointId").asLong()).isZero();
        assertThat(tracker.observe(execution("RUNNING", 1, null, 0L), workers())).isEmpty();
        assertEvents(tracker.observe(execution("RUNNING", 1, null, 1L), workers()),
                "CHECKPOINT_COMPLETED");
    }

    @Test void checkpointBecomingNullIsStillAChange() {
        tracker.observe(execution("RUNNING", 1, null, 7L), workers());
        List<ObjectNode> events = tracker.observe(execution("RUNNING", 1, null, null), workers());

        assertEvents(events, "CHECKPOINT_COMPLETED");
        assertThat(events.getFirst().path("checkpointId").isNull()).isTrue();
    }

    @Test void recoveryEntryMetadataChangesAndCompletionHaveDistinctEvents() {
        tracker.observe(execution("RUNNING", 1, null, 7L), workers(worker("w-1", true)));
        List<ObjectNode> recovering = tracker.observe(execution("RECOVERING", 2, 7L, 7L), workers());
        assertEvents(recovering, "WORKER_DEAD", "RECOVERING");
        assertThat(recovering.get(1).path("checkpointId").asLong()).isEqualTo(7);
        assertThat(tracker.observe(execution("RECOVERING", 2, 7L, 7L), workers())).isEmpty();

        assertEvents(tracker.observe(execution("RECOVERING", 3, 7L, 7L), workers()), "RECOVERING");
        assertEvents(tracker.observe(execution("RECOVERING", 3, 8L, 7L), workers()), "RECOVERING");
        List<ObjectNode> recovered = tracker.observe(execution("RUNNING", 3, 8L, 7L), workers());
        assertEvents(recovered, "RECOVERED");
        assertThat(recovered.getFirst().path("checkpointId").asLong()).isEqualTo(8);
    }

    @Test void runningGenerationChangesAreRebalancesButRestoreMetadataAloneIsNot() {
        tracker.observe(execution("RUNNING", 1, null, 7L), workers());
        assertEvents(tracker.observe(execution("RUNNING", 2, 7L, 7L), workers()), "REBALANCED");
        assertThat(tracker.observe(execution("RUNNING", 2, 8L, 7L), workers())).isEmpty();
        assertEvents(tracker.observe(execution("RUNNING", 3, 8L, 7L), workers()), "REBALANCED");
    }

    @Test void killRecoveryThenRejoinEmitsOneRebalanceWithoutReplacingTheIncidentDuration() {
        tracker.observe(execution("RUNNING", 1, null, 7L),
                workers(worker("w-1", true), worker("w-2", true)));
        clockNanos.set(TimeUnit.SECONDS.toNanos(1));
        assertEvents(tracker.observe(execution("RECOVERING", 2, 7L, 7L),
                workers(worker("w-2", true))), "WORKER_DEAD", "RECOVERING");
        clockNanos.set(TimeUnit.MILLISECONDS.toNanos(2_993));
        List<ObjectNode> recovered = tracker.observe(execution("RUNNING", 2, 7L, 7L),
                workers(worker("w-2", true)));
        assertEvents(recovered, "RECOVERED");
        assertThat(recovered.getFirst().path("durationMs").asLong()).isEqualTo(1_993);

        clockNanos.set(TimeUnit.MILLISECONDS.toNanos(8_200));
        // Registration can be observed before the coordinator starts reassignment.
        ArrayNode rejoined = workers(worker("w-1", true), worker("w-2", true));
        assertThat(tracker.observe(execution("RUNNING", 2, 7L, 7L), rejoined)).isEmpty();
        List<ObjectNode> rebalanced = tracker.observe(execution("RECOVERING", 3, 8L, 8L), rejoined);
        assertEvents(rebalanced, "REBALANCED", "CHECKPOINT_COMPLETED");
        ObjectNode event = rebalanced.getFirst();
        assertThat(event.path("workerId").asText()).isEqualTo("w-1");
        assertThat(event.path("oldGeneration").asLong()).isEqualTo(2);
        assertThat(event.path("newGeneration").asLong()).isEqualTo(3);
        assertThat(event.path("generation").asLong()).isEqualTo(3);
        assertThat(event.has("durationMs")).isFalse();
        assertThat(tracker.observe(execution("RECOVERING", 3, 8L, 8L), rejoined)).isEmpty();
        clockNanos.set(TimeUnit.SECONDS.toNanos(10));
        assertThat(tracker.observe(execution("RUNNING", 3, 8L, 8L), rejoined)).isEmpty();
        assertThat(recovered.getFirst().path("durationMs").asLong()).isEqualTo(1_993);
    }

    @Test void rescaleBetweenPollsIdentifiesAnExistingWorkerGainingPartitions() {
        ObjectNode first = worker("w-1", true);
        ObjectNode second = worker("w-2", true);
        first.putArray("partitions").add(0).add(1);
        second.putArray("partitions");
        tracker.observe(execution("RUNNING", 1, null, 7L), workers(first, second));
        clockNanos.set(TimeUnit.SECONDS.toNanos(5));
        first.putArray("partitions").add(0);
        second.putArray("partitions").add(1);
        List<ObjectNode> events = tracker.observe(execution("RUNNING", 2, 7L, 7L),
                workers(first, second));
        assertEvents(events, "REBALANCED");
        assertThat(events.getFirst().path("workerId").asText()).isEqualTo("w-2");
        assertThat(events.getFirst().path("oldGeneration").asLong()).isEqualTo(1);
        assertThat(events.getFirst().path("newGeneration").asLong()).isEqualTo(2);
        assertThat(events.getFirst().has("durationMs")).isFalse();
        assertThat(tracker.observe(execution("RUNNING", 2, 7L, 7L), workers(first, second))).isEmpty();
    }

    @Test void recoveredDurationStartsAtFirstWorkerDeathNotRecoveryEntryOrLaterDeaths() {
        tracker.observe(execution("RUNNING", 1, null, 7L),
                workers(worker("w-1", true), worker("w-2", true)));
        clockNanos.set(TimeUnit.MILLISECONDS.toNanos(1_000));
        assertEvents(tracker.observe(execution("RUNNING", 1, null, 7L),
                workers(worker("w-2", true))), "WORKER_DEAD");

        clockNanos.set(TimeUnit.MILLISECONDS.toNanos(2_000));
        tracker.observe(execution("RECOVERING", 2, 7L, 7L), workers(worker("w-2", true)));
        clockNanos.set(TimeUnit.MILLISECONDS.toNanos(3_000));
        tracker.observe(execution("RECOVERING", 3, 7L, 7L), workers());
        clockNanos.set(TimeUnit.MILLISECONDS.toNanos(7_000));
        List<ObjectNode> recovered = tracker.observe(execution("RUNNING", 3, 7L, 7L), workers());

        assertEvents(recovered, "RECOVERED");
        assertThat(recovered.getFirst().path("durationMs").asLong()).isEqualTo(6_000);
        assertThat(tracker.observe(execution("RUNNING", 4, 7L, 7L), workers()).getFirst()
                .has("durationMs")).isFalse();

        tracker.observe(execution("RUNNING", 4, 7L, 7L), workers(worker("w-3", true)));
        clockNanos.set(TimeUnit.MILLISECONDS.toNanos(8_000));
        tracker.observe(execution("RECOVERING", 5, 7L, 7L), workers());
        clockNanos.set(TimeUnit.MILLISECONDS.toNanos(10_000));
        assertThat(tracker.observe(execution("RUNNING", 5, 7L, 7L), workers()).getFirst()
                .path("durationMs").asLong()).isEqualTo(2_000);
    }

    @Test void recoveryAlreadyInProgressWithoutAnObservedDeathDoesNotInventAnIncident() {
        tracker.observe(execution("RECOVERING", 2, 7L, 7L), workers());
        assertThat(tracker.observe(execution("RUNNING", 2, 7L, 7L), workers())).isEmpty();

        tracker.observe(execution("RUNNING", 2, 7L, 7L), workers(worker("w-1", true)));
        tracker.observe(execution("RECOVERING", 3, 7L, 7L), workers());
        tracker.reset();
        clockNanos.set(TimeUnit.SECONDS.toNanos(6));
        assertThat(tracker.observe(execution("RECOVERING", 3, 7L, 7L), workers())).isEmpty();
        assertThat(tracker.observe(execution("RUNNING", 3, 7L, 7L), workers())).isEmpty();
    }

    @Test void recoveryCompletedInDeathObservationHasZeroDuration() {
        tracker.observe(execution("RUNNING", 1, null, 7L), workers(worker("w-1", true)));
        List<ObjectNode> events = tracker.observe(execution("RUNNING", 2, 7L, 7L), workers());

        assertEvents(events, "WORKER_DEAD", "RECOVERED");
        assertThat(events.get(0).has("durationMs")).isFalse();
        assertThat(events.get(1).path("durationMs").asLong(-1)).isZero();
    }

    @Test void replacingExecutionDiscardsPreviousRecoveryTiming() {
        tracker.observe(execution("RUNNING", 1, null, 7L), workers(worker("w-1", true)));
        tracker.observe(execution("RECOVERING", 2, 7L, 7L), workers());
        clockNanos.set(TimeUnit.SECONDS.toNanos(6));
        tracker.observe(execution("RECOVERING", 2, 7L, 7L).put("id", "exec-2"), workers());
        assertThat(tracker.observe(execution("RUNNING", 2, 7L, 7L).put("id", "exec-2"),
                workers())).isEmpty();
    }

    @Test void stoppingOrFailingDiscardsRecoveryTiming() {
        for (String state : List.of("STOPPED", "FAILED")) {
            tracker.reset();
            tracker.observe(execution("RUNNING", 1, null, 7L), workers(worker("w-1", true)));
            tracker.observe(execution("RECOVERING", 2, 7L, 7L), workers());
            tracker.observe(execution(state, 2, 7L, 7L), workers());
            clockNanos.addAndGet(TimeUnit.SECONDS.toNanos(6));
            assertEvents(tracker.observe(execution("RECOVERING", 3, 7L, 7L), workers()), "REBALANCED");
            assertThat(tracker.observe(execution("RUNNING", 3, 7L, 7L), workers())).isEmpty();
        }
    }

    @Test void initialRecoveringDoesNotInventADeathAndTerminalStatesAreReported() {
        assertThat(tracker.observe(execution("RECOVERING", 2, 7L, 7L), workers())).isEmpty();
        tracker.reset();
        assertEvents(tracker.observe(execution("STOPPED", 1, null, 7L), workers()), "STOPPED");
        tracker.reset();
        assertEvents(tracker.observe(execution("FAILED", 1, null, 7L), workers()), "STOPPED");
    }

    @Test void stoppedAndFailedTransitionsAreNotSilentlyIgnored() {
        tracker.observe(execution("RUNNING", 1, null, 7L), workers());
        assertEvents(tracker.observe(execution("STOPPED", 1, null, 7L), workers()), "STOPPED");
        assertThat(tracker.observe(execution("STOPPED", 1, null, 7L), workers())).isEmpty();
        assertEvents(tracker.observe(execution("FAILED", 1, null, 7L), workers()), "STOPPED");
        assertEvents(tracker.observe(execution("FAILED", 2, 7L, 7L), workers()), "STOPPED");
    }

    @Test void disappearanceAndExplicitWorkerDeathAreReportedWithoutExecutionChanges() {
        ObjectNode execution = execution("RUNNING", 1, null, 7L);
        tracker.observe(execution, workers(worker("w-1", true), worker("w-2", true), worker("w-3", false)));
        List<ObjectNode> events = tracker.observe(execution, workers(worker("w-2", false), worker("w-3", false)));

        assertEvents(events, "WORKER_DEAD", "WORKER_DEAD");
        assertThat(events).extracting(event -> event.path("workerId").asText())
                .containsExactly("w-1", "w-2");
        assertThat(tracker.observe(execution, workers(worker("w-2", false)))).isEmpty();
        assertThat(tracker.observe(execution, workers(worker("w-2", true)))).isEmpty();
        assertEvents(tracker.observe(execution, workers()), "WORKER_DEAD");
    }

    @Test void independentWorkerRecoveryAndCheckpointTransitionsAreAllEmitted() {
        tracker.observe(execution("RUNNING", 1, null, 7L), workers(worker("w-1", true)));
        ObjectNode current = execution("RECOVERING", 2, 7L, 8L);
        List<ObjectNode> events = tracker.observe(current, workers());

        assertEvents(events, "WORKER_DEAD", "RECOVERING", "CHECKPOINT_COMPLETED");
        assertThat(events.get(0).path("workerId").asText()).isEqualTo("w-1");
        assertThat(events.get(1).path("checkpointId").asLong()).isEqualTo(7);
        assertThat(events.get(2).path("checkpointId").asLong()).isEqualTo(8);
        assertThat(events).allSatisfy(event -> {
            assertThat(event.path("state").asText()).isEqualTo("RECOVERING");
            assertThat(event.path("generation").asLong()).isEqualTo(2);
        });
    }

    @Test void nullUpstreamObservationsPreserveExecutionAndWorkerBaselines() {
        ObjectNode execution = execution("RUNNING", 1, null, 7L);
        tracker.observe(execution, workers(worker("w-1", true)));

        assertThat(tracker.observe(null, null)).isEmpty();
        assertThat(tracker.observe(NullNode.instance, workers())).isEmpty();
        assertThat(tracker.observe(null, workers())).isEmpty();
        assertThat(tracker.observe(execution, null)).isEmpty();
        assertThat(tracker.observe(execution, NullNode.instance)).isEmpty();
        assertThat(tracker.observe(execution, workers(worker("w-1", true)))).isEmpty();
        assertEvents(tracker.observe(execution, workers()), "WORKER_DEAD");
    }

    @Test void executionChangesStillEmitWhenWorkersAreUnavailable() {
        tracker.observe(execution("RUNNING", 1, null, 7L), workers(worker("w-1", true)));
        assertEvents(tracker.observe(execution("RECOVERING", 2, 7L, 7L), null), "REBALANCED");
        assertEvents(tracker.observe(execution("RUNNING", 2, 7L, 7L), workers()),
                "WORKER_DEAD", "RECOVERED");
    }

    @Test void unrelatedExecutionAndWorkerFieldsDoNotEmitEvents() {
        ObjectNode execution = execution("RUNNING", 1, null, 7L);
        tracker.observe(execution, workers(worker("w-1", true)));
        ObjectNode changed = execution.deepCopy().put("createdAt", "later").put("pipelineVersion", 2);
        ObjectNode worker = worker("w-1", true).put("lastHeartbeatMs", 123).put("backpressured", true);

        assertThat(tracker.observe(changed, workers(worker))).isEmpty();
    }

    @Test void inputAndOutputMutationCannotRewriteCachedStateOrOtherEventPayloads() {
        ObjectNode execution = execution("RUNNING", 1, null, 7L);
        execution.set("details", mapper.createObjectNode().put("value", "original"));
        ObjectNode worker = worker("w-1", true);
        List<ObjectNode> initial = tracker.observe(execution, workers(worker));
        initial.getFirst().put("generation", 99);
        ((ObjectNode) initial.getFirst().get("details")).put("value", "changed");
        assertThat(execution.path("details").path("value").asText()).isEqualTo("original");
        assertThat(tracker.observe(execution, workers(worker))).isEmpty();

        execution.put("generation", 2).put("lastCompletedCheckpoint", 8);
        worker.put("alive", false);
        List<ObjectNode> events = tracker.observe(execution, workers(worker));
        assertEvents(events, "WORKER_DEAD", "RECOVERED", "CHECKPOINT_COMPLETED");
        ((ObjectNode) events.getFirst().get("details")).put("value", "one-event-only");
        assertThat(events.get(1).path("details").path("value").asText()).isEqualTo("original");
        assertThat(events.get(2).path("details").path("value").asText()).isEqualTo("original");
    }

    @Test void resetClearsBothExecutionAndWorkers() {
        tracker.observe(execution("RUNNING", 1, null, 7L), workers(worker("w-1", true)));
        tracker.reset();

        assertEvents(tracker.observe(execution("RUNNING", 1, null, 9L), workers()), "STARTED");
        assertThat(tracker.observe(execution("RUNNING", 1, null, 9L), workers())).isEmpty();
    }

    private ObjectNode execution(String state, long generation, Long restored, Long checkpoint) {
        ObjectNode execution = mapper.createObjectNode().put("id", "exec-1")
                .put("pipelineId", "pipeline-1").put("pipelineVersion", 1)
                .put("state", state).put("generation", generation).put("createdAt", "2026-11-19T00:00:00Z");
        if (restored == null) {
            execution.putNull("restoredFromCheckpoint");
        } else {
            execution.put("restoredFromCheckpoint", restored);
        }
        if (checkpoint == null) {
            execution.putNull("lastCompletedCheckpoint");
        } else {
            execution.put("lastCompletedCheckpoint", checkpoint);
        }
        return execution;
    }

    private ObjectNode worker(String id, boolean alive) {
        return mapper.createObjectNode().put("workerId", id).put("alive", alive);
    }

    private ArrayNode workers(ObjectNode... workers) {
        ArrayNode array = mapper.createArrayNode();
        for (ObjectNode worker : workers) {
            array.add(worker);
        }
        return array;
    }

    private static void assertEvents(List<ObjectNode> events, String... expected) {
        assertThat(events).extracting(event -> event.path("event").asText()).containsExactly(expected);
    }
}
