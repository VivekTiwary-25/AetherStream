package io.aetherstream.api.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aetherstream.api.org.OrgFilter;
import io.aetherstream.common.Json;
import io.aetherstream.common.pipeline.ExecutionPlan;
import io.aetherstream.common.protocol.CheckpointAck;
import io.aetherstream.common.protocol.Command;
import io.aetherstream.common.protocol.HeartbeatResponse;
import io.aetherstream.common.protocol.QueueStatus;
import io.aetherstream.common.protocol.RegisterRequest;
import io.aetherstream.common.protocol.WorkerStatus;
import io.aetherstream.engine.coordinator.Coordinator;
import io.aetherstream.engine.coordinator.CoordinatorHttpServer;
import io.aetherstream.engine.coordinator.PostgresExecutionRepository;
import io.aetherstream.engine.coordinator.StartFromSavepointRequest;
import io.aetherstream.engine.storage.InMemoryCheckpointStore;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** No worker processes: drives the actual worker protocol, coordinator HTTP, audit JDBC and API handler. */
class RecoveryHistoryCoordinatorTest {
    private static final String EXECUTION = "exec-fast-restart";
    private static final List<String> WORKERS = List.of("worker-1", "worker-2", "worker-3");
    private static final Map<String, List<Integer>> OWNERS = Map.of(
            "worker-1", List.of(0, 3), "worker-2", List.of(1, 4), "worker-3", List.of(2, 5));
    private final AtomicLong now = new AtomicLong(10_000);
    private final List<Executable> results = new ArrayList<>();
    private final long started = System.nanoTime();
    private String base;

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Transactions {}

    @Test
    @Timeout(120)
    void fastSameIdDockerStyleRestartPreservesTheCompleteRecoveryStory() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.builder().setPort(0).start();
             HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            DataSource dataSource = postgres.getPostgresDatabase();
            new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            var repository = new PostgresExecutionRepository(dataSource);
            var coordinator = new Coordinator(repository, new InMemoryCheckpointStore(),
                    (topic, partitions, startFrom) -> Map.of(0, 0L, 1, 0L, 2, 0L, 3, 0L, 4, 0L, 5, 0L),
                    now::get, 60_000);
            var server = new CoordinatorHttpServer(coordinator, 0);
            server.start();
            base = "http://127.0.0.1:" + server.port();
            RecoveryHistory history = new RecoveryHistory();
            RecoveryObserver observer = new RecoveryObserver(jdbc, Json.MAPPER, history);
            try (GenericApplicationContext context = apiContext(dataSource, jdbc, history)) {
                Object controller = context.getBean(Class.forName("io.aetherstream.api.ControlApiController"));
                MockMvc api = MockMvcBuilders.standaloneSetup(controller).addFilters(new OrgFilter()).build();
                for (String worker : WORKERS) {
                    send(client, "POST", "/internal/workers/register", new RegisterRequest(1, worker, worker));
                }
                send(client, "POST", "/coord/executions", new StartFromSavepointRequest(EXECUTION,
                        new ExecutionPlan("pipeline", 1, "events", 6, "latest", List.of()), null));
                for (String worker : WORKERS) {
                    var response = heartbeat(client, worker, 1);
                    assertThat(response.commands()).anySatisfy(command -> {
                        assertThat(command).isInstanceOf(Command.Assign.class);
                        assertThat(((Command.Assign) command).partitions()).isEqualTo(OWNERS.get(worker));
                    });
                }
                check("three live workers, six assigned partitions, RUNNING at 10000", () -> {
                    JsonNode state = send(client, "GET", "/coord/state", null);
                    assertThat(state.path("execution").path("state").asText()).isEqualTo("RUNNING");
                    assertThat(state.path("workers")).hasSize(3).allSatisfy(worker ->
                            assertThat(worker.path("alive").asBoolean()).isTrue());
                    assertThat(state.path("workers")).allSatisfy(worker ->
                            assertThat(worker.path("partitions"))
                                    .isEqualTo(Json.MAPPER.valueToTree(OWNERS.get(worker.path("workerId").asText()))));
                });

                long checkpoint = send(client, "POST", "/coord/executions/" + EXECUTION + "/checkpoint", null)
                        .path("checkpointId").asLong();
                assertThat(checkpoint).isPositive();
                for (String worker : WORKERS) {
                    now.addAndGet(20);
                    Map<Integer, Long> offsets = OWNERS.get(worker).stream()
                            .collect(Collectors.toMap(partition -> partition, partition -> 100L + partition));
                    JsonNode ack = send(client, "POST", "/internal/checkpoints/ack",
                            new CheckpointAck(1, worker, EXECUTION, 1, checkpoint, offsets, worker + ".json"));
                    assertThat(ack.path("accepted").asBoolean()).isTrue();
                }
                observer.poll();
                check("checkpoint completed at 10060 in 60 ms", () -> {
                    assertThat(repository.latestCompleted(EXECUTION).orElseThrow().checkpointId()).isEqualTo(checkpoint);
                    assertThat(history.history("demo")).anySatisfy(entry -> assertThat(entry)
                            .containsEntry("event", "CHECKPOINT_COMPLETED").containsEntry("checkpointId", checkpoint)
                            .containsEntry("at", 10_060L).containsEntry("durationMs", 60L));
                });

                now.set(10_100);
                long killBefore = System.currentTimeMillis();
                String killResponse = api.perform(post("/api/chaos/kill-worker").header("X-Org", "demo")
                                .contentType("application/json").content("{\"workerId\":\"worker-2\"}"))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                long killAfter = System.currentTimeMillis();
                assertThat(Json.MAPPER.readTree(killResponse).path("workerId").asText()).isEqualTo("worker-2");
                var killedHeartbeat = heartbeat(client, "worker-2", 1);
                check("chaos path delivers a real KILL command at 10100", () ->
                        assertThat(killedHeartbeat.commands()).anyMatch(Command.Kill.class::isInstance));
                now.set(11_100);
                heartbeat(client, "worker-1", 1);
                heartbeat(client, "worker-3", 1);
                coordinator.tick();
                now.set(11_600);
                send(client, "POST", "/internal/workers/register", new RegisterRequest(1, "worker-2", "worker-2"));
                check("same-id restart in 1500 ms, before the 4000 ms death timeout", () -> {
                    assertThat(now.get() - 10_100).isLessThan(Coordinator.HEARTBEAT_TIMEOUT_MS);
                    JsonNode state = send(client, "GET", "/coord/state", null);
                    assertThat(state.path("workers")).allSatisfy(worker ->
                            assertThat(worker.path("alive").asBoolean()).isTrue());
                    assertThat(state.path("execution").path("state").asText()).isEqualTo("RECOVERING");
                });
                now.set(11_900);
                for (String worker : WORKERS) {
                    var response = heartbeat(client, worker, 2);
                    check(worker + " receives generation-2 ASSIGN restoring checkpoint " + checkpoint + " at 11900", () ->
                            assertThat(response.commands()).anySatisfy(command -> {
                                assertThat(command).isInstanceOf(Command.Assign.class);
                                Command.Assign assign = (Command.Assign) command;
                                assertThat(assign.generation()).isEqualTo(2);
                                assertThat(assign.restoreCheckpointId()).isEqualTo(checkpoint);
                                assertThat(assign.partitions()).isEqualTo(OWNERS.get(worker));
                            }));
                }
                // Poll only after recovery: exercise a death and recovery entirely between observer samples.
                observer.poll();
                List<Map<String, Object>> entries = history.history("demo");
                System.out.println("RECOVERY_HISTORY " + Json.MAPPER.writeValueAsString(entries));
                check("history order survives recovery: KILL, DEAD, RESTARTED, RECOVERING, RECOVERED", () ->
                        assertThat(entries).extracting(entry -> entry.get("event")).containsSubsequence(
                                "KILL_REQUESTED", "WORKER_DEAD", "WORKER_RESTARTED", "RECOVERING", "RECOVERED"));
                check("death/restart attributed to worker-2 at 11600; no other worker dies", () -> {
                    assertThat(entries.stream().filter(entry -> List.of("WORKER_DEAD", "WORKER_RESTARTED")
                            .contains(entry.get("event"))).toList()).hasSize(2).allSatisfy(entry ->
                            assertThat(entry).containsEntry("workerId", "worker-2").containsEntry("at", 11_600L));
                });
                check("RECOVERING/RECOVERED attributed to worker-2", () ->
                        assertThat(entries.stream().filter(entry -> List.of("RECOVERING", "RECOVERED")
                                .contains(entry.get("event"))).toList()).hasSize(2).allSatisfy(entry ->
                                assertThat(entry).containsEntry("workerId", "worker-2")));
                check("RECOVERING/RECOVERED restore the completed checkpoint", () ->
                        assertThat(entries.stream().filter(entry -> List.of("RECOVERING", "RECOVERED")
                                .contains(entry.get("event"))).toList()).hasSize(2).allSatisfy(entry ->
                                assertThat(entry).containsEntry("restoredFromCheckpoint", checkpoint)));
                // assign() audits only changed ownership. A same-id restart restores the same six owners,
                // so the coordinator emits no new assignment and history must not invent a partition move.
                check("same-id recovery keeps the same partitions without a partition-change entry", () -> {
                    assertThat(entries).noneSatisfy(entry -> assertThat(entry).containsEntry("event", "REBALANCED"));
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action='execution.assignment'",
                            Long.class)).isEqualTo(1L); // Initial assignment only.
                });
                check("audit-derived at values are coordinator event times, not observer/database time", () -> {
                    Map<String, String> actions = Map.of("CHECKPOINT_COMPLETED", "checkpoint.completed",
                            "WORKER_DEAD", "worker.dead", "WORKER_RESTARTED", "worker.restarted",
                            "RECOVERING", "execution.recoveryStarted", "RECOVERED", "execution.recovered");
                    for (var entry : entries) {
                        String action = actions.get(entry.get("event"));
                        if (action == null) continue;
                        Long eventAt = jdbc.queryForObject("SELECT (detail->>'atMs')::bigint FROM audit_log WHERE action=?",
                                Long.class, action);
                        assertThat(entry.get("at")).isEqualTo(eventAt);
                    }
                    assertThat(entries).anySatisfy(entry -> assertThat(entry).containsEntry("event", "RECOVERED")
                            .containsEntry("at", 11_900L).containsEntry("durationMs", 300L)
                            .containsEntry("restoredFromCheckpoint", checkpoint));
                });
                // KILL_REQUESTED records acceptance by the API, not the injected coordinator clock.
                // audit_log.at is the kill audit's database wall time; detail.atMs deliberately uses 10100.
                check("KILL_REQUESTED uses API acceptance time within 2 s of the coordinator kill audit", () -> {
                    Long killAuditAt = jdbc.queryForObject(
                            "SELECT (extract(epoch FROM at)*1000)::bigint FROM audit_log WHERE action='chaos.kill'",
                            Long.class);
                    assertThat(entries).anySatisfy(entry -> {
                        assertThat(entry).containsEntry("event", "KILL_REQUESTED").containsEntry("workerId", "worker-2");
                        assertThat((Long) entry.get("at")).isBetween(killBefore, killAfter)
                                .isBetween(killAuditAt - 2_000, killAuditAt + 2_000);
                    });
                });
                now.set(12_900);
                for (String worker : WORKERS) heartbeat(client, worker, 2);
                coordinator.tick();
                observer.poll();
                check("entries retained without duplicates after recovery and another poll at 12900", () -> {
                    assertThat(history.history("demo")).isEqualTo(entries);
                    JsonNode endpoint = Json.MAPPER.readTree(api.perform(get("/api/recovery/history"))
                            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
                    // Parse both wire representations: valueToTree retains LongNode versus the reader's IntNode.
                    assertThat(endpoint).isEqualTo(Json.MAPPER.readTree(Json.MAPPER.writeValueAsString(entries)));
                    assertThat(history.history("other-org")).isEmpty();
                    JsonNode state = send(client, "GET", "/coord/state", null);
                    assertThat(state.path("execution").path("state").asText()).isEqualTo("RUNNING");
                    assertThat(state.path("execution").path("restoredFromCheckpoint").asLong()).isEqualTo(checkpoint);
                });
                assertAll("Step-7 real-coordinator recovery requirements (see timed PASS/FAIL output)", results);
            } finally {
                observer.close();
                server.stop();
            }
        }
    }

    private GenericApplicationContext apiContext(DataSource dataSource, JdbcTemplate jdbc, RecoveryHistory history)
            throws ClassNotFoundException {
        GenericApplicationContext context = new GenericApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("recovery-test", Map.of(
                "aetherstream.coordinator-url", base, "aetherstream.simulator-url", base,
                "aetherstream.kafka-bootstrap", "unused:9092", "aetherstream.git-sha", "test", "aetherstream.built-at", "test")));
        context.registerBean(DataSource.class, () -> dataSource);
        context.registerBean(JdbcTemplate.class, () -> jdbc);
        context.registerBean(ObjectMapper.class, () -> Json.MAPPER);
        context.registerBean(RecoveryHistory.class, () -> history);
        context.registerBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
        var reader = new AnnotatedBeanDefinitionReader(context);
        reader.register(Transactions.class, Class.forName("io.aetherstream.api.CoordinatorClient"),
                Class.forName("io.aetherstream.api.SimulatorClient"), Class.forName("io.aetherstream.api.ResultRepository"),
                Class.forName("io.aetherstream.api.ControlApiController"));
        context.refresh();
        return context;
    }

    private HeartbeatResponse heartbeat(HttpClient client, String worker, long generation) throws Exception {
        return Json.MAPPER.treeToValue(send(client, "POST", "/internal/workers/" + worker + "/heartbeat",
                new WorkerStatus(1, worker, EXECUTION, generation, "RUNNING", OWNERS.get(worker),
                        new QueueStatus(0, 10), new QueueStatus(0, 10), false, 0, 0, 0, Map.of())), HeartbeatResponse.class);
    }

    private JsonNode send(HttpClient client, String method, String path, Object body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json").method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(Json.MAPPER.writeValueAsString(body))).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s %s: %s", method, path, response.body()).isEqualTo(200);
        return Json.MAPPER.readTree(response.body());
    }

    private void check(String assertion, Executable proof) {
        try {
            proof.execute();
            System.out.printf("PASS clock=%d wall=%.1fms %s%n", now.get(), (System.nanoTime() - started) / 1_000_000.0, assertion);
        } catch (Throwable failure) {
            System.out.printf("FAIL clock=%d wall=%.1fms %s: %s%n", now.get(),
                    (System.nanoTime() - started) / 1_000_000.0, assertion, failure.getMessage());
            results.add(() -> { throw new AssertionError(assertion, failure); });
        }
    }
}
