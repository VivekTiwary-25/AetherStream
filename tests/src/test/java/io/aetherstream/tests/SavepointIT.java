package io.aetherstream.tests;

import static io.aetherstream.tests.WorkerCrashRecoveryIT.await;
import static io.aetherstream.tests.WorkerCrashRecoveryIT.post;
import static io.aetherstream.tests.WorkerCrashRecoveryIT.repository;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aetherstream.common.Json;
import io.aetherstream.common.pipeline.ExecutionPlan;
import io.aetherstream.common.pipeline.PlannedOperator;
import io.aetherstream.common.protocol.StartExecutionRequest;
import io.aetherstream.compiler.PipelineCompiler;
import io.aetherstream.engine.coordinator.CheckpointRecord;
import io.aetherstream.engine.coordinator.Coordinator;
import io.aetherstream.engine.coordinator.KafkaOffsetsLookup;
import io.aetherstream.engine.coordinator.StartFromSavepointRequest;
import io.aetherstream.reference.KafkaRangeReader;
import io.aetherstream.reference.RawRecord;
import io.aetherstream.reference.ReferenceExecutor;
import io.aetherstream.reference.ReferenceResult;
import io.aetherstream.simulator.Simulation;
import io.aetherstream.tests.support.EngineCluster;
import io.aetherstream.tests.support.IntegrationInfrastructure;
import io.aetherstream.tests.support.WorkloadGenerator;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class SavepointIT {
    private static final int PARTITIONS = 6;

    @Test
    void savepointRestoresOpenWindowsAndRuleStreaksAndRejectsChangedWindowSize() throws Exception {
        IntegrationInfrastructure.truncateAll();
        var workload = WorkloadGenerator.generate();
        // Split inside a window, after the stopped-truck streak has begun but before its alert.
        long splitTime = Simulation.START_EPOCH_MS + Duration.ofMinutes(12).plusSeconds(30).toMillis();
        int split = 0;
        while (split < workload.size()
                && Json.MAPPER.readTree(workload.get(split).value()).path("eventTime").asLong() < splitTime) {
            split++;
        }
        assertThat(split).isBetween(1, workload.size() - 1);
        var offsets = new KafkaOffsetsLookup(IntegrationInfrastructure.bootstrap());
        var repository = repository();
        // Start before publishing: Kafka is shared across ITs, so old records are not part of this execution.
        ExecutionPlan plan = new PipelineCompiler().plan(PipelineCompiler.demoPipeline(), PARTITIONS);
        String sourceId = "savepoint-source-" + UUID.randomUUID();
        String restoredId = "savepoint-restored-" + UUID.randomUUID();
        try (var cluster = new EngineCluster(3)) {
            post(cluster, "/coord/executions", new StartExecutionRequest(sourceId, plan));
            CheckpointRecord initial = repository.findCheckpoint(sourceId, 0).orElseThrow();
            IntegrationInfrastructure.produce(workload.subList(0, split));
            var prefixEnd = offsets.offsets(plan.topic(), PARTITIONS, "latest");
            await(cluster, Duration.ofSeconds(120), "prefix committed before savepoint", () ->
                    prefixEnd.equals(repository.latestCompleted(sourceId).orElseThrow().sourceOffsets()));

            var savedResponse = request(cluster, "/coord/executions/" + sourceId + "/savepoint", Map.of());
            assertThat(savedResponse.statusCode()).as("savepoint: %s", savedResponse.body()).isEqualTo(200);
            JsonNode saved = Json.MAPPER.readTree(savedResponse.body());
            String savepointId = saved.required("savepointId").asText();
            long savedCheckpointId = saved.required("checkpointId").asLong();
            assertThat(savepointId).isNotBlank();
            assertThat(savedCheckpointId).isPositive();
            CheckpointRecord savedCheckpoint = repository.findCheckpoint(sourceId, savedCheckpointId).orElseThrow();
            assertThat(savedCheckpoint.state()).isEqualTo(CheckpointRecord.COMPLETED);
            assertThat(savedCheckpoint.sourceOffsets()).isEqualTo(prefixEnd);

            JsonNode started = post(cluster, "/coord/executions",
                    new StartFromSavepointRequest(restoredId, plan, savepointId));
            assertThat(started.path("id").asText()).isEqualTo(restoredId);
            assertThat(started.path("state").asText()).isEqualTo("RUNNING");
            assertThat(started.path("generation").asLong()).isEqualTo(1);
            assertThat(started.path("restoredFromCheckpoint").asLong()).isEqualTo(savedCheckpointId);
            assertThat(repository.findExecution(sourceId).orElseThrow().state()).isEqualTo("STOPPED");
            CheckpointRecord restoredInitial = repository.findCheckpoint(restoredId, 0).orElseThrow();
            assertThat(restoredInitial.sourceOffsets()).isEqualTo(savedCheckpoint.sourceOffsets());
            assertThat(restoredInitial.manifestKey()).isEqualTo("savepoints/" + savepointId + "/manifest.json");
            await(cluster, Duration.ofSeconds(30), "all workers running the restored execution", () ->
                    IntStream.range(0, 3).allMatch(i -> {
                        var status = cluster.worker(i).status();
                        return restoredId.equals(status.executionId()) && status.state().equals("RUNNING")
                                && status.generation() == 1 && status.partitions().size() == 2;
                    }));

            String rejectedId = "savepoint-incompatible-" + UUID.randomUUID();
            var rejected = request(cluster, "/coord/executions",
                    new StartFromSavepointRequest(rejectedId, changedWindowSize(plan), savepointId));
            assertThat(rejected.statusCode()).as("incompatible restore: %s", rejected.body()).isEqualTo(409);
            JsonNode error = Json.MAPPER.readTree(rejected.body());
            assertThat(error.path("error").asText()).isEqualTo("INCOMPATIBLE_SAVEPOINT");
            assertThat(error.path("message").asText()).contains("speed1m", "sizeMs", "60000", "30000");
            assertThat(repository.findExecution(rejectedId)).isEmpty();
            assertThat(repository.findExecution(restoredId).orElseThrow().state()).isEqualTo("RUNNING");

            IntegrationInfrastructure.produce(workload.subList(split, workload.size()));
            var end = offsets.offsets(plan.topic(), PARTITIONS, "latest");
            await(cluster, Duration.ofSeconds(120), "continuation committed after restore", () -> {
                var checkpoint = repository.latestCompleted(restoredId).orElseThrow();
                return checkpoint.checkpointId() > 0 && end.equals(checkpoint.sourceOffsets());
            });
            CheckpointRecord completed = repository.latestCompleted(restoredId).orElseThrow();
            // T86: continuation must publish checkpoints owned by the new execution, not the savepoint source.
            assertThat(completed.executionId()).isEqualTo(restoredId);
            assertThat(completed.state()).isEqualTo(CheckpointRecord.COMPLETED);
            assertThat(completed.generation()).isEqualTo(1);
            assertThat(completed.manifestKey()).isEqualTo(Coordinator.manifestKey(restoredId, completed.checkpointId()));
            assertThat(repository.findExecution(restoredId).orElseThrow().state()).isEqualTo("RUNNING");
            var records = new KafkaRangeReader().read(IntegrationInfrastructure.bootstrap(), plan.topic(),
                    initial.sourceOffsets(), completed.sourceOffsets());
            Map<Integer, List<RawRecord>> prefix = new TreeMap<>();
            Map<Integer, List<RawRecord>> suffix = new TreeMap<>();
            records.forEach((partition, values) -> {
                long frontier = savedCheckpoint.sourceOffsets().get(partition);
                prefix.put(partition, values.stream().filter(record -> record.offset() < frontier).toList());
                suffix.put(partition, values.stream().filter(record -> record.offset() >= frontier).toList());
            });
            var executor = new ReferenceExecutor();
            ReferenceResult before = executor.run(plan, prefix);
            ReferenceResult full = executor.run(plan, records);
            assertThat(before.recordsReplayed()).isEqualTo(split);
            assertThat(full.recordsReplayed()).isEqualTo(workload.size());
            // Replay from the original empty cp0, then exclude outputs already emitted before the savepoint.
            // Replaying only restored cp0..completed with empty state loses open windows and rule streaks.
            ReferenceResult continuation = new ReferenceResult(plan,
                    withoutPrefix(full.windowsBySink(), before.windowsBySink()),
                    withoutPrefix(full.alertsBySink(), before.alertsBySink()),
                    full.deadLetters().stream().filter(value -> !before.deadLetters().contains(value)).toList(),
                    full.watermarksByPartition(), full.keysByPartition(), full.recordsReplayed() - before.recordsReplayed());
            assertThat(continuation.recordsReplayed()).isEqualTo(workload.size() - split);

            // Prove this comparison would detect an engine that silently starts with empty state.
            ReferenceResult emptyContinuation = executor.run(plan, suffix);
            long boundaryStart = splitTime - Duration.ofSeconds(30).toMillis();
            var restoredWindow = continuation.windowsBySink().get("speedSink").stream()
                    .filter(window -> window.key().equals("truck-007") && window.windowStart() == boundaryStart)
                    .findFirst().orElseThrow();
            var emptyWindow = emptyContinuation.windowsBySink().get("speedSink").stream()
                    .filter(window -> window.key().equals("truck-007") && window.windowStart() == boundaryStart)
                    .findFirst().orElseThrow();
            assertThat(restoredWindow.eventCount()).as("boundary window includes pre-savepoint events")
                    .isGreaterThan(emptyWindow.eventCount());
            assertThat(continuation.alertsBySink().get("alertSink")).anySatisfy(alert -> {
                assertThat(alert.key()).isEqualTo("truck-007");
                assertThat(alert.message()).isEqualTo("truck-007 has not moved for 10 min");
                assertThat(alert.eventTime())
                        .isBetween(splitTime, Simulation.START_EPOCH_MS + Duration.ofMinutes(20).toMillis());
                assertThat(emptyContinuation.alertsBySink().get("alertSink"))
                        .noneMatch(empty -> empty.alertId().equals(alert.alertId()));
            });
            CoordinatorRestartIT.assertMatchesReference(restoredId, completed, continuation);
            cluster.checkHealthy();
        }
    }

    private static ExecutionPlan changedWindowSize(ExecutionPlan plan) {
        List<PlannedOperator> operators = plan.operators().stream().map(operator -> {
            if (!operator.id().equals("speed1m")) return operator;
            ObjectNode config = ((ObjectNode) operator.config()).deepCopy();
            config.put("sizeMs", 30_000);
            return new PlannedOperator(operator.id(), operator.type(), config, operator.inputs(), operator.outputType());
        }).toList();
        return new ExecutionPlan(plan.pipelineId(), plan.pipelineVersion(), plan.topic(), plan.partitions(),
                plan.startFrom(), operators);
    }

    private static <T> Map<String, List<T>> withoutPrefix(Map<String, List<T>> full, Map<String, List<T>> prefix) {
        Map<String, List<T>> result = new TreeMap<>();
        full.forEach((sink, values) -> {
            var emitted = new HashSet<>(prefix.getOrDefault(sink, List.of()));
            assertThat(values).containsAll(emitted);
            result.put(sink, values.stream().filter(value -> !emitted.contains(value)).toList());
        });
        return result;
    }

    private static HttpResponse<String> request(EngineCluster cluster, String path, Object body) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            // The coordinator itself may wait 20 seconds for the savepoint's aligned checkpoint.
            var request = HttpRequest.newBuilder(URI.create(cluster.coordinatorUrl() + path))
                    .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(Json.MAPPER.writeValueAsBytes(body))).build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
