package io.aetherstream.engine.coordinator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aetherstream.common.Json;
import io.aetherstream.common.api.ExecutionInfo;
import io.aetherstream.common.pipeline.ExecutionPlan;
import io.aetherstream.common.pipeline.PlannedOperator;
import io.aetherstream.common.protocol.CheckpointAck;
import io.aetherstream.common.protocol.CheckpointManifest;
import io.aetherstream.common.protocol.Command;
import io.aetherstream.common.protocol.QueueStatus;
import io.aetherstream.common.protocol.RegisterRequest;
import io.aetherstream.common.protocol.StartExecutionRequest;
import io.aetherstream.common.protocol.WorkerStatus;
import io.aetherstream.engine.storage.InMemoryCheckpointStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SavepointTest {
    private static final String EXEC = "source-exec";
    private static final Map<Integer, Long> SAVED_OFFSETS = Map.of(0, 125L, 1, 250L);

    private static final class FakeClock implements Clock {
        long now = 1_795_251_600_000L;

        @Override
        public long nowMs() {
            return now;
        }
    }

    private static final class SignallingRepository extends InMemoryExecutionRepository {
        final BlockingQueue<Long> triggered = new LinkedBlockingQueue<>();

        @Override
        public synchronized void insertCheckpoint(CheckpointRecord checkpoint) {
            super.insertCheckpoint(checkpoint);
            if (CheckpointRecord.PENDING.equals(checkpoint.state())) {
                triggered.add(checkpoint.checkpointId());
            }
        }
    }

    private FakeClock clock;
    private SignallingRepository repo;
    private InMemoryCheckpointStore store;
    private Coordinator coordinator;
    private ExecutorService executor;
    private CoordinatorHttpServer server;
    private HttpClient client;
    private String base;
    private int offsetLookups;

    @BeforeEach
    void setUp() throws Exception {
        clock = new FakeClock();
        repo = new SignallingRepository();
        store = new InMemoryCheckpointStore();
        coordinator = new Coordinator(repo, store, (topic, partitions, startFrom) -> {
            offsetLookups++;
            return Map.of(0, 1L, 1, 2L);
        }, clock, 10_000, false);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server = new CoordinatorHttpServer(coordinator, 0);
        server.start();
        client = HttpClient.newHttpClient();
        base = "http://localhost:" + server.port();
        coordinator.register(new RegisterRequest(1, "w-a", "host"));
        coordinator.startExecution(new StartExecutionRequest(EXEC, plan("pipeline", 1, 2, 60_000, true)));
        coordinator.heartbeat("w-a", null);
    }

    @AfterEach
    void tearDown() {
        server.stop();
        executor.shutdownNow();
        client.close();
    }

    private static ExecutionPlan plan(String pipelineId, int version, int partitions, long windowSize, boolean rule) {
        PlannedOperator source = new PlannedOperator("src", "source", Json.MAPPER.createObjectNode(), List.of(), "EVENT");
        PlannedOperator window = new PlannedOperator("window", "window", Json.MAPPER.createObjectNode()
                .put("kind", "tumbling").put("sizeMs", windowSize), List.of("src"), "WINDOW_RESULT");
        PlannedOperator streak = new PlannedOperator("rule", "rule", Json.MAPPER.createObjectNode()
                .put("ruleId", "stuck").put("forWindows", 10), List.of("window"), "ALERT");
        return new ExecutionPlan(pipelineId, version, "events", partitions, "earliest",
                rule ? List.of(source, window, streak) : List.of(source, window));
    }

    private long awaitCheckpoint() throws Exception {
        Long id = repo.triggered.poll(5, TimeUnit.SECONDS);
        assertThat(id).as("savepoint triggers a checkpoint without waiting for the periodic scheduler").isNotNull();
        return id;
    }

    private void complete(long id) {
        assertThat(coordinator.ack(new CheckpointAck(1, "w-a", EXEC, 1, id, SAVED_OFFSETS,
                "executions/" + EXEC + "/checkpoints/" + id + "/worker-w-a.json")).accepted()).isTrue();
    }

    private SavepointInfo savepoint() throws Exception {
        Future<SavepointInfo> pending = executor.submit(() -> coordinator.createSavepoint(EXEC));
        complete(awaitCheckpoint());
        return pending.get(5, TimeUnit.SECONDS);
    }

    private HttpRequest request(String path, Object body) throws Exception {
        return HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.MAPPER.writeValueAsString(body))).build();
    }

    private HttpResponse<String> post(String path, Object body) throws Exception {
        return client.send(request(path, body), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void httpSavepointWaitsForAllPartitionsAndListsDurableAuditRowsNewestFirst() throws Exception {
        CompletableFuture<HttpResponse<String>> pending = client.sendAsync(
                request("/coord/executions/" + EXEC + "/savepoint", Map.of()), HttpResponse.BodyHandlers.ofString());
        long id = awaitCheckpoint();
        assertThat(coordinator.ack(new CheckpointAck(1, "w-a", EXEC, 1, id, Map.of(0, 125L), "worker.json"))
                .accepted()).isTrue();
        assertThat(pending.isDone()).isFalse();
        assertThat(repo.listSavepoints()).isEmpty();
        assertThat(store.exists("savepoints/sp-" + EXEC + "-" + id + "/manifest.json")).isFalse();
        complete(id);
        HttpResponse<String> response = pending.get(5, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = Json.MAPPER.readTree(response.body());
        assertThat(body.get("savepointId").asText()).isEqualTo("sp-" + EXEC + "-" + id);
        assertThat(body.get("checkpointId").asLong()).isEqualTo(id);
        assertThat(body.get("createdAt").asText()).isEqualTo(Instant.ofEpochMilli(clock.now).toString());
        SavepointInfo first = repo.listSavepoints().getFirst();
        assertThat(repo.findCheckpoint(EXEC, id).orElseThrow().state()).isEqualTo(CheckpointRecord.COMPLETED);
        assertThat(repo.auditRows().getLast().action()).isEqualTo("savepoint.created");
        assertThat(first.operatorIds()).containsExactly("src", "window", "rule");
        assertThat(first.pipelineId()).isEqualTo("pipeline");
        assertThat(first.pipelineVersion()).isEqualTo(1);
        assertThat(first.protocolVersion()).isEqualTo(1);
        assertThat(store.getJson(first.manifestKey(), CheckpointManifest.class))
                .isEqualTo(store.getJson(Coordinator.manifestKey(EXEC, id), CheckpointManifest.class));
        SavepointInfo second = savepoint(); // Same fake timestamp: insertion order breaks ties.
        HttpResponse<String> listed = client.send(HttpRequest.newBuilder(URI.create(base + "/coord/savepoints")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(Json.MAPPER.readValue(listed.body(), SavepointInfo[].class)).containsExactly(second, first);
        Coordinator restarted = new Coordinator(repo, store, (t, p, s) -> Map.of(), clock, 10_000, false);
        assertThat(restarted.listSavepoints()).containsExactly(second, first);
    }

    @Test
    void compatibleUpgradeStartsAtGenerationOneWithSavedOffsetsAndManifestEvenAfterCoordinatorRestart() throws Exception {
        SavepointInfo saved = savepoint();
        HttpResponse<String> response = post("/coord/executions", new StartFromSavepointRequest("restored-exec",
                plan("pipeline", 2, 2, 60_000, true), saved.savepointId()));
        assertThat(response.statusCode()).isEqualTo(200);
        ExecutionInfo info = Json.MAPPER.readValue(response.body(), ExecutionInfo.class);
        assertThat(info.generation()).isEqualTo(1);
        assertThat(info.restoredFromCheckpoint()).isEqualTo(saved.checkpointId());
        assertThat(offsetLookups).isEqualTo(1);
        Command.Assign assign = coordinator.heartbeat("w-a", null).commands().stream()
                .filter(Command.Assign.class::isInstance).map(Command.Assign.class::cast).findFirst().orElseThrow();
        assertThat(assign.executionId()).isEqualTo("restored-exec");
        assertThat(assign.generation()).isEqualTo(1);
        assertThat(assign.restoreCheckpointId()).isEqualTo(saved.checkpointId());
        assertThat(assign.manifestKey()).isEqualTo(saved.manifestKey());
        assertThat(assign.startOffsets()).isEqualTo(SAVED_OFFSETS);
        assertThat(repo.findCheckpoint("restored-exec", 0).orElseThrow().sourceOffsets()).isEqualTo(SAVED_OFFSETS);

        Coordinator restarted = new Coordinator(repo, store, (t, p, s) -> {
            throw new AssertionError("Recovery must not look up Kafka starting offsets");
        }, clock, 10_000, false);
        restarted.loadActiveExecutions();
        clock.now += Coordinator.HEARTBEAT_TIMEOUT_MS;
        restarted.register(new RegisterRequest(1, "w-b", "host"));
        Command.Assign recovered = (Command.Assign) restarted.heartbeat("w-b", null).commands().stream()
                .filter(Command.Assign.class::isInstance).findFirst().orElseThrow();
        assertThat(recovered.generation()).isEqualTo(2);
        assertThat(recovered.restoreCheckpointId()).isEqualTo(saved.checkpointId()).isPositive();
        assertThat(recovered.manifestKey()).isEqualTo(saved.manifestKey());
        assertThat(recovered.startOffsets()).isEqualTo(SAVED_OFFSETS);
    }

    private void rejects(SavepointInfo saved, ExecutionPlan target, String... differences) throws Exception {
        HttpResponse<String> response = post("/coord/executions",
                new StartFromSavepointRequest("rejected-exec", target, saved.savepointId()));
        assertThat(response.statusCode()).isEqualTo(409);
        JsonNode error = Json.MAPPER.readTree(response.body());
        assertThat(error.get("error").asText()).isEqualTo("INCOMPATIBLE_SAVEPOINT");
        assertThat(error.get("message").asText()).contains(differences);
        assertThat(repo.findExecution("rejected-exec")).isEmpty();
        assertThat(coordinator.executionInfo(EXEC).state()).isEqualTo("RUNNING");
        assertThat(offsetLookups).isEqualTo(1);
    }

    @Test
    void changedWindowSizeIsRejectedBeforeStoppingTheRunningExecution() throws Exception {
        rejects(savepoint(), plan("pipeline", 2, 2, 30_000, true), "window", "sizeMs", "60000", "30000");
    }

    @Test
    void removedOperatorIsRejectedWithItsId() throws Exception {
        rejects(savepoint(), plan("pipeline", 2, 2, 60_000, false), "rule", "removed");
    }

    @Test
    void differentPipelineIsRejectedWithBothPipelineIds() throws Exception {
        rejects(savepoint(), plan("another-pipeline", 2, 2, 60_000, true), "pipelineId", "pipeline", "another-pipeline");
    }

    @Test
    void changedPartitionCountIsRejected() throws Exception {
        rejects(savepoint(), plan("pipeline", 2, 3, 60_000, true), "partition count", "2", "3");
    }

    @Test
    void changedStatefulTypeAndRuleConfigurationAreRejected() throws Exception {
        SavepointInfo saved = savepoint();
        ExecutionPlan original = plan("pipeline", 2, 2, 60_000, true);
        List<PlannedOperator> ops = original.operators();
        ExecutionPlan changedType = new ExecutionPlan("pipeline", 2, "events", 2, "earliest", List.of(ops.get(0),
                new PlannedOperator("window", "rule", ops.get(1).config(), List.of("src"), "ALERT"), ops.get(2)));
        rejects(saved, changedType, "window", "type changed", "window to rule");
        ((ObjectNode) ops.get(2).config()).put("forWindows", 5);
        rejects(saved, original, "rule", "forWindows", "configuration changed");
    }

    @Test
    void changedProtocolVersionIsRejected() throws Exception {
        SavepointInfo saved = savepoint();
        ObjectNode manifest = store.getJson(saved.manifestKey(), ObjectNode.class);
        manifest.put("protocolVersion", 2);
        store.putJson(saved.manifestKey(), manifest);
        rejects(saved, plan("pipeline", 2, 2, 60_000, true), "protocolVersion", "2", "1");
    }

    @Test
    void stoppedExecutionCannotCreateSavepoint() throws Exception {
        coordinator.stopExecution(EXEC);
        HttpResponse<String> response = post("/coord/executions/" + EXEC + "/savepoint", Map.of());
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(Json.MAPPER.readTree(response.body()).get("message").asText()).contains("STOPPED");
        assertThat(repo.listSavepoints()).isEmpty();
    }

    @Test
    void timeoutDoesNotPublishAnIncompleteSavepoint() throws Exception {
        Future<SavepointInfo> pending = executor.submit(() -> coordinator.createSavepoint(EXEC));
        awaitCheckpoint();
        synchronized (coordinator) {
            clock.now += Coordinator.SAVEPOINT_TIMEOUT_MS;
            coordinator.heartbeat("w-a", null);
            coordinator.tick();
        }
        assertThatThrownBy(() -> pending.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IllegalStateException.class).hasRootCauseMessage(
                        "savepoint checkpoint 1 did not complete within 20 seconds");
        assertThat(repo.listSavepoints()).isEmpty();
        assertThat(store.keys()).noneMatch(k -> k.startsWith("savepoints/"));
    }

    @Test
    void stoppingWhileWaitingRejectsTheAbortedCheckpoint() throws Exception {
        Future<SavepointInfo> pending = executor.submit(() -> coordinator.createSavepoint(EXEC));
        long id = awaitCheckpoint();
        coordinator.stopExecution(EXEC);
        assertThatThrownBy(() -> pending.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .hasRootCauseMessage("savepoint checkpoint " + id + " was ABORTED");
        assertThat(repo.listSavepoints()).isEmpty();
    }

    @Test
    void directStartAcceptsEquivalentNumericConfigurationsAfterJsonRoundTrip() throws Exception {
        SavepointInfo saved = savepoint();
        ExecutionPlan target = plan("pipeline", 2, 2, 60_000, true);
        ((ObjectNode) target.operators().get(1).config()).put("sizeMs", 60_000.0);
        ExecutionInfo started = coordinator.startExecutionFromSavepoint(
                new StartFromSavepointRequest("numeric-config", target, saved.savepointId()));
        assertThat(started.state()).isEqualTo("RUNNING");
        Command.Assign assign = coordinator.heartbeat("w-a", null).commands().stream()
                .filter(Command.Assign.class::isInstance).map(Command.Assign.class::cast).findFirst().orElseThrow();
        assertThat(assign.manifestKey()).isEqualTo(saved.manifestKey());
        assertThat(assign.startOffsets()).isEqualTo(SAVED_OFFSETS);
    }

    @Test
    void executionRestoredFromSavepointCompletesItsOwnCheckpointsOneAndTwo() throws Exception {
        // Savepoint taken at checkpoint 3 of the source execution: its id must not leak into the new numbering.
        for (int i = 0; i < 2; i++) {
            complete(coordinator.triggerCheckpointNow(EXEC));
        }
        repo.triggered.clear();
        SavepointInfo saved = savepoint();
        assertThat(saved.checkpointId()).isEqualTo(3);

        String restoredExec = "restored-exec";
        coordinator.startExecutionFromSavepoint(new StartFromSavepointRequest(restoredExec,
                plan("pipeline", 1, 2, 60_000, true), saved.savepointId()));
        Command.Assign assign = coordinator.heartbeat("w-a", null).commands().stream()
                .filter(Command.Assign.class::isInstance).map(Command.Assign.class::cast).findFirst().orElseThrow();
        assertThat(assign.executionId()).isEqualTo(restoredExec);
        assertThat(assign.restoreCheckpointId()).isEqualTo(3);
        assertThat(repo.findCheckpoint(restoredExec, 0).orElseThrow().state()).isEqualTo(CheckpointRecord.COMPLETED);

        for (long expected = 1; expected <= 2; expected++) {
            WorkerStatus running = new WorkerStatus(1, "w-a", restoredExec, assign.generation(), "RUNNING",
                    List.of(0, 1), new QueueStatus(0, 1000), new QueueStatus(0, 1000), false, 0, 0.0,
                    expected - 1, Map.of());
            clock.now += 10_000;
            coordinator.heartbeat("w-a", running);
            coordinator.tick();
            Command.TriggerCheckpoint trigger = coordinator.heartbeat("w-a", running).commands().stream()
                    .filter(Command.TriggerCheckpoint.class::isInstance).map(Command.TriggerCheckpoint.class::cast)
                    .findFirst().orElseThrow();
            assertThat(trigger.executionId()).isEqualTo(restoredExec);
            assertThat(trigger.generation()).isEqualTo(assign.generation());
            assertThat(trigger.checkpointId()).isEqualTo(expected);
            assertThat(coordinator.ack(new CheckpointAck(1, "w-a", restoredExec, assign.generation(), expected,
                    SAVED_OFFSETS, "executions/" + restoredExec + "/checkpoints/" + expected + "/worker-w-a.json"))
                    .accepted()).isTrue();
            assertThat(repo.findCheckpoint(restoredExec, expected).orElseThrow().state())
                    .isEqualTo(CheckpointRecord.COMPLETED);
        }
        assertThat(coordinator.clusterState().execution().lastCompletedCheckpoint()).isEqualTo(2L);
        assertThat(coordinator.clusterState().execution().restoredFromCheckpoint()).isEqualTo(3L);
    }
}
