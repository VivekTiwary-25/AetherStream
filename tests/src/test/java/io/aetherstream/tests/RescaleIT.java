package io.aetherstream.tests;

import static io.aetherstream.tests.WorkerCrashRecoveryIT.await;
import static io.aetherstream.tests.WorkerCrashRecoveryIT.earliestDemoPlan;
import static io.aetherstream.tests.WorkerCrashRecoveryIT.post;
import static io.aetherstream.tests.WorkerCrashRecoveryIT.repository;
import static org.assertj.core.api.Assertions.assertThat;

import io.aetherstream.common.Json;
import io.aetherstream.common.protocol.ClusterState;
import io.aetherstream.common.protocol.StartExecutionRequest;
import io.aetherstream.common.protocol.WorkerStatus;
import io.aetherstream.engine.coordinator.KafkaOffsetsLookup;
import io.aetherstream.engine.worker.Worker;
import io.aetherstream.reference.KafkaRangeReader;
import io.aetherstream.reference.ReferenceExecutor;
import io.aetherstream.tests.support.EngineCluster;
import io.aetherstream.tests.support.IntegrationInfrastructure;
import io.aetherstream.tests.support.WorkloadGenerator;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class RescaleIT {
    private static final int PARTITIONS = 6;

    @Test
    void addingWorkerPreservesPartitionOwnershipAndMatchesReference() throws Exception {
        IntegrationInfrastructure.truncateAll();
        var workload = WorkloadGenerator.generate();
        IntegrationInfrastructure.produce(workload);
        var offsets = new KafkaOffsetsLookup(IntegrationInfrastructure.bootstrap());
        var beginning = offsets.offsets(IntegrationInfrastructure.TOPIC, PARTITIONS, "earliest");
        var end = offsets.offsets(IntegrationInfrastructure.TOPIC, PARTITIONS, "latest");
        var repository = repository();
        var plan = earliestDemoPlan();
        String executionId = "rescale-" + UUID.randomUUID();
        try (var cluster = new EngineCluster(2);
             var monitor = new OwnershipMonitor(cluster, executionId)) {
            // A slow real sink keeps queued and unread work present when ownership changes.
            post(cluster, "/coord/chaos/sink-delay", Map.of("delayMs", 2));
            await(cluster, Duration.ofSeconds(15), "sink delay on both original workers", () -> {
                monitor.latest();
                return cluster.worker(0).sinkDelayMs() == 2 && cluster.worker(1).sinkDelayMs() == 2;
            });
            post(cluster, "/coord/executions", new StartExecutionRequest(executionId, plan));
            await(cluster, Duration.ofSeconds(30), "3/3 ownership with active progress and backlog", () -> {
                var sample = monitor.latest();
                return sample != null && sample.balanced(2, 3)
                        && sample.statuses().stream().allMatch(s -> s.processedRecords() > 0
                                && s.sinkQueue().size() > 0 && s.lag().values().stream().anyMatch(lag -> lag > 0));
            });
            long generation = monitor.latest().state().execution().generation();
            assertThat(repository.latestCompleted(executionId).orElseThrow().sourceOffsets())
                    .as("rescale starts before full consumption").isNotEqualTo(end);

            var added = cluster.addWorker();
            monitor.workers.add(added);
            post(cluster, "/coord/executions/" + executionId + "/rebalance", null);
            await(cluster, Duration.ofSeconds(120), "new generation RUNNING with 2/2/2 ownership", () -> {
                var sample = monitor.latest();
                return sample != null && sample.balanced(3, 2)
                        && sample.state().execution().generation() > generation;
            });
            var rescaled = monitor.latest();
            long newGeneration = rescaled.state().execution().generation();
            assertThat(rescaled.statuses()).anySatisfy(status -> {
                assertThat(status.workerId()).isEqualTo(added.workerId());
                assertThat(status.generation()).isEqualTo(newGeneration);
                assertThat(status.partitions()).hasSize(2);
            });
            var execution = repository.findExecution(executionId).orElseThrow();
            assertThat(execution.state()).isEqualTo("RUNNING");
            assertThat(execution.generation()).isEqualTo(newGeneration);
            assertThat(execution.restoredFromCheckpoint()).isPositive();
            var restored = repository.findCheckpoint(executionId, execution.restoredFromCheckpoint()).orElseThrow();
            assertThat(restored.state()).isEqualTo("COMPLETED");
            assertThat(restored.sourceOffsets()).as("rebalance transfers nonempty in-flight state")
                    .isNotEqualTo(end);

            post(cluster, "/coord/chaos/sink-delay", Map.of("delayMs", 0));
            await(cluster, Duration.ofSeconds(120), "full consumption checkpoint in rescaled generation", () -> {
                monitor.latest();
                var checkpoint = repository.latestCompleted(executionId).orElseThrow();
                return checkpoint.generation() == newGeneration && end.equals(checkpoint.sourceOffsets());
            });
            // Keep polling after consumption, spanning multiple worker heartbeats rather than one lucky sample.
            long consumedAt = System.nanoTime();
            await(cluster, Duration.ofSeconds(10), "stable ownership after full consumption", () -> {
                var sample = monitor.latest();
                assertThat(sample.balanced(3, 2)).as("final 2/2/2 ownership: %s", sample).isTrue();
                assertThat(sample.state().execution().generation()).isEqualTo(newGeneration);
                return System.nanoTime() - consumedAt >= Duration.ofSeconds(3).toNanos();
            });
            var completed = repository.latestCompleted(executionId).orElseThrow();
            assertThat(completed.state()).isEqualTo("COMPLETED");
            assertThat(completed.generation()).isEqualTo(newGeneration);
            assertThat(completed.sourceOffsets()).isEqualTo(end);
            var initial = repository.findCheckpoint(executionId, 0).orElseThrow();
            assertThat(initial.sourceOffsets()).isEqualTo(beginning);
            var records = new KafkaRangeReader().read(IntegrationInfrastructure.bootstrap(), plan.topic(),
                    initial.sourceOffsets(), completed.sourceOffsets());
            var reference = new ReferenceExecutor().run(plan, records);
            assertThat(reference.recordsReplayed()).isEqualTo(workload.size());
            CoordinatorRestartIT.assertMatchesReference(executionId, completed, reference);
            monitor.latest();
            cluster.checkHealthy();
        }
    }

    private record Sample(ClusterState state, List<WorkerStatus> statuses) {
        boolean balanced(int workerCount, int partitionsPerWorker) {
            var execution = state.execution();
            if (execution == null || !execution.state().equals("RUNNING") || statuses.size() != workerCount
                    || state.workers().size() != workerCount) return false;
            var assigned = new ArrayList<Integer>();
            for (var worker : state.workers()) {
                if (!worker.alive() || !worker.state().equals("RUNNING")
                        || worker.partitions().size() != partitionsPerWorker) return false;
                var actual = statuses.stream().filter(s -> s.workerId().equals(worker.workerId()))
                        .findFirst().orElseThrow();
                if (!actual.state().equals("RUNNING") || !execution.id().equals(actual.executionId())
                        || actual.generation() != execution.generation()
                        || !actual.partitions().equals(worker.partitions())) return false;
                assigned.addAll(actual.partitions());
            }
            assertThat(assigned).doesNotHaveDuplicates().containsExactlyInAnyOrder(0, 1, 2, 3, 4, 5);
            return true;
        }
    }

    /** Samples HTTP membership and real consumer-side generations throughout all blocking control calls. */
    private static final class OwnershipMonitor implements AutoCloseable {
        private final CopyOnWriteArrayList<Worker> workers = new CopyOnWriteArrayList<>();
        private final AtomicReference<Sample> sample = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final ScheduledExecutorService polling = Executors.newSingleThreadScheduledExecutor();
        private final HttpClient http = HttpClient.newHttpClient();

        OwnershipMonitor(EngineCluster cluster, String executionId) {
            workers.add(cluster.worker(0));
            workers.add(cluster.worker(1));
            var request = HttpRequest.newBuilder(URI.create(cluster.coordinatorUrl() + "/coord/state"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            polling.scheduleWithFixedDelay(() -> {
                try {
                    cluster.checkHealthy();
                    var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
                    var state = Json.MAPPER.readValue(response.body(), ClusterState.class);
                    var statuses = workers.stream().map(Worker::status).toList();
                    var coordinatorOwners = new HashMap<Integer, String>();
                    var consumerOwners = new HashMap<Long, Map<Integer, String>>();
                    for (var view : state.workers()) {
                        if (!view.alive()) continue;
                        for (int partition : view.partitions()) {
                            assertThat(coordinatorOwners.putIfAbsent(partition, view.workerId()))
                                    .as("coordinator duplicate ownership of partition %s: %s", partition, state)
                                    .isNull();
                        }
                        // WorkerView has no generation and its partitions are desired, not actual ownership.
                        // Check the actual WorkerStatus independently, grouping by its reported generation.
                        var actual = statuses.stream().filter(s -> s.workerId().equals(view.workerId()))
                                .findFirst().orElse(null);
                        if (actual == null) {
                            // Registration precedes addWorker() returning the new idle Worker to this monitor.
                            assertThat(view.partitions()).as("unobserved registering worker owns no partitions")
                                    .isEmpty();
                            continue;
                        }
                        if (!executionId.equals(actual.executionId())) continue;
                        var owners = consumerOwners.computeIfAbsent(actual.generation(), ignored -> new HashMap<>());
                        for (int partition : actual.partitions()) {
                            assertThat(partition).isBetween(0, PARTITIONS - 1);
                            assertThat(owners.putIfAbsent(partition, actual.workerId()))
                                    .as("consumer duplicate ownership of partition %s in generation %s: %s",
                                            partition, actual.generation(), statuses)
                                    .isNull();
                        }
                    }
                    sample.set(new Sample(state, statuses));
                } catch (Throwable error) {
                    if (error instanceof InterruptedException && polling.isShutdown()) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    failure.compareAndSet(null, error);
                }
            }, 0, 25, TimeUnit.MILLISECONDS);
        }

        Sample latest() {
            var error = failure.get();
            if (error != null) throw new AssertionError("Continuous partition ownership monitoring failed", error);
            return sample.get();
        }

        @Override
        public void close() throws Exception {
            polling.shutdownNow();
            assertThat(polling.awaitTermination(10, TimeUnit.SECONDS)).as("ownership poller stopped").isTrue();
            http.close();
            latest();
        }
    }
}
