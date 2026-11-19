package io.aetherstream.api.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecoveryHistoryTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final RecoveryHistory history = new RecoveryHistory();

    @Test void requestIsNotEvidenceOfDeathAndUsesResolvedTarget() {
        history.killRequested("alpha", "exec-1", "worker-2", "terminal-operator", 100);
        assertThat(history.history("alpha")).singleElement().satisfies(entry -> {
            assertThat(entry).containsEntry("event", "KILL_REQUESTED").containsEntry("workerId", "worker-2")
                    .containsEntry("actor", "terminal-operator").containsEntry("at", 100L)
                    .containsEntry("executionId", "exec-1");
            assertThat(entry).containsKeys("generation", "restoredFromCheckpoint");
            assertThat(entry).doesNotContainKeys("durationMs");
        });
        assertThat(history.history("other")).isEmpty();
    }

    @Test void auditRetainsFastSameIdDeathAndRestartMissedByAllWorkerSamples() throws Exception {
        history.killRequested("alpha", "exec-1", "worker-2", "api:alpha", 10);
        List<RecoveryHistory.AuditFact> batch = List.of(
                fact(1, "worker.dead", "{\"workerId\":\"worker-2\",\"reason\":\"re-registered\",\"atMs\":100}"),
                fact(2, "worker.restarted", "{\"workerId\":\"worker-2\",\"atMs\":100}"),
                fact(3, "execution.recoveryStarted", "{\"generation\":1,\"atMs\":100}"),
                fact(4, "execution.recovered", "{\"generation\":2,\"checkpointId\":7,\"atMs\":450,\"recoveryDurationMs\":350}"));
        batch.forEach(history::observe);
        batch.forEach(history::observe);
        List<Map<String, Object>> entries = history.history("alpha");
        assertThat(entries).extracting(entry -> entry.get("event")).containsExactly(
                "KILL_REQUESTED", "WORKER_DEAD", "WORKER_RESTARTED", "RECOVERING", "RECOVERED");
        assertThat(entries.get(2)).containsEntry("workerId", "worker-2").doesNotContainKey("durationMs");
        assertThat(entries.get(4)).containsEntry("durationMs", 350L).containsEntry("generation", 2L)
                .containsEntry("restoredFromCheckpoint", 7L).containsEntry("at", 450L);
    }

    @Test void assignmentAndCompletedCheckpointComeFromCoordinatorFacts() throws Exception {
        history.observe(fact(1, "execution.assignment", "{\"oldAssignment\":{},\"assignment\":{\"worker-1\":[0,1]}}"));
        history.observe(fact(2, "execution.assignment", "{\"generation\":3,\"checkpointId\":8,\"oldAssignment\":{\"worker-1\":[0,1]},\"assignment\":{\"worker-1\":[0],\"worker-2\":[1]}}"));
        history.observe(fact(3, "checkpoint.completed", "{\"generation\":3,\"checkpointId\":9,\"durationMs\":27}"));
        assertThat(history.history("alpha")).hasSize(2);
        assertThat(history.history("alpha").get(0)).containsEntry("event", "REBALANCED")
                .containsEntry("partitionChanges", List.of(Map.of("partition", 1, "oldOwner", "worker-1", "newOwner", "worker-2")));
        assertThat(history.history("alpha").get(1)).containsEntry("event", "CHECKPOINT_COMPLETED")
                .containsEntry("checkpointId", 9L).containsEntry("durationMs", 27L)
                .containsEntry("restoredFromCheckpoint", null);
    }

    @Test void observedRestartDurationIsIncludedOnlyWhenReported() throws Exception {
        history.observe(fact(1, "worker.restarted", "{\"workerId\":\"worker-2\",\"durationMs\":800}"));
        assertThat(history.history("alpha").getFirst()).containsEntry("durationMs", 800L);
    }

    @Test void retainsLast200AcrossReadersAndNotifiesEachNewEntryOnce() throws Exception {
        List<RecoveryHistory.Recorded> published = new ArrayList<>();
        history.subscribe(published::add);
        for (int i = 1; i <= 205; i++) history.observe(fact(i, "checkpoint.completed", "{\"checkpointId\":" + i + "}"));
        history.observe(fact(1, "checkpoint.completed", "{\"checkpointId\":1}"));
        List<Map<String, Object>> firstBrowser = history.history("alpha");
        List<Map<String, Object>> reloadedBrowser = history.history("alpha");
        assertThat(firstBrowser).hasSize(200).isEqualTo(reloadedBrowser);
        assertThat(firstBrowser.getFirst()).containsEntry("id", 6L);
        assertThat(firstBrowser.getLast()).containsEntry("id", 205L);
        assertThat(published).hasSize(205);
        assertThat(history.history("other")).isEmpty();
    }

    private RecoveryHistory.AuditFact fact(long id, String action, String detail) throws Exception {
        var json = mapper.readTree(detail);
        ((com.fasterxml.jackson.databind.node.ObjectNode) json).put("executionId", "exec-1");
        return new RecoveryHistory.AuditFact(id, 1_000 + id, "coordinator", action, json, "alpha");
    }
}
