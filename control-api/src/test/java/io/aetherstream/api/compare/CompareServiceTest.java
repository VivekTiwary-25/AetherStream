package io.aetherstream.api.compare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aetherstream.api.org.OrgFilter;
import io.aetherstream.common.Json;
import io.aetherstream.common.api.CompareResult;
import io.aetherstream.common.pipeline.ExecutionPlan;
import io.aetherstream.common.pipeline.PlannedOperator;
import io.aetherstream.compiler.PipelineCompiler;
import io.aetherstream.compiler.ops.ListCollector;
import io.aetherstream.compiler.ops.PartitionPipeline;
import io.aetherstream.reference.RawRecord;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CompareServiceTest {
    private static final long START = 1_795_251_600_000L;
    private static final ExecutionPlan PLAN = new PipelineCompiler().plan(PipelineCompiler.demoPipeline(), 2);

    @Test
    void matchesEnginePartitionPipelinesAcrossCheckpointRangeIncludingAlerts() throws Exception {
        Fixture fixture = new Fixture();
        CompareService service = fixture.service(fixture::readRange);

        mvc(service).perform(post("/api/compare").contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkpointId").value(7))
                .andExpect(jsonPath("$.referenceResults").value(21))
                .andExpect(jsonPath("$.engineResults").value(21))
                .andExpect(jsonPath("$.matched").value(21))
                .andExpect(jsonPath("$.recordsReplayed").value(24))
                .andExpect(jsonPath("$.mismatches").isEmpty())
                .andExpect(jsonPath("$.durationMs").isNumber())
                .andExpect(jsonPath("$.match").value(true));
    }

    @Test
    void comparesRestoredLineageWithPartialWindowsDedupAndInheritedRuleStreaks() throws Exception {
        Fixture fixture = new Fixture(true);
        CompareService service = fixture.service(fixture::readRange);

        mvc(service).perform(post("/api/compare").contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkpointId").value(7))
                .andExpect(jsonPath("$.referenceResults").value(3))
                .andExpect(jsonPath("$.engineResults").value(3))
                .andExpect(jsonPath("$.matched").value(3))
                .andExpect(jsonPath("$.recordsReplayed").value(26))
                .andExpect(jsonPath("$.mismatches").isEmpty())
                .andExpect(jsonPath("$.match").value(true));
    }

    @Test
    void restoredComparisonStillReportsMissingAndDifferentCurrentWindows() throws Exception {
        Fixture fixture = new Fixture(true);
        fixture.windows.removeFirst();
        var corrupted = new LinkedHashMap<>(fixture.windows.getFirst());
        corrupted.put("event_count", 99L);
        fixture.windows.set(0, corrupted);

        CompareResult result = fixture.service(fixture::readRange).compare();
        assertThat(result.match()).isFalse();
        assertThat(result.mismatches()).extracting(CompareResult.Mismatch::type)
                .containsExactlyInAnyOrder("MISSING", "DIFFERENT");
    }

    @Test
    void unavailableOrForeignSavepointSourceDoesNotReplayKafka() throws Exception {
        Fixture fixture = new Fixture(true);
        fixture.lineageVisible = false;
        AtomicInteger reads = new AtomicInteger();
        CompareService service = fixture.service((bootstrap, topic, from, to, sink) -> {
            reads.incrementAndGet();
            fixture.readRange(bootstrap, topic, from, to, sink);
        });

        mvc(service).perform(post("/api/compare").contentType("application/json").content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("NO_LINEAGE"));
        assertThat(reads.get()).isZero();
    }

    @Test
    void onlyCheckpointZeroReturnsContractConflictWithoutReadingKafka() throws Exception {
        Fixture fixture = new Fixture();
        fixture.hasCompletedCheckpoint = false;
        AtomicInteger reads = new AtomicInteger();
        CompareService service = fixture.service((bootstrap, topic, from, to, sink) -> {
            reads.incrementAndGet();
            fixture.readRange(bootstrap, topic, from, to, sink);
        });

        mvc(service).perform(post("/api/compare").contentType("application/json").content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("NO_CHECKPOINT"))
                .andExpect(jsonPath("$.message").value("No completed checkpoint yet - wait a few seconds"));
        assertThat(reads.get()).isZero();
    }

    @Test
    void comparesOnlyTheCallersCurrentExecution() throws Exception {
        Fixture fixture = new Fixture();
        fixture.expectedOrg = "acme";
        fixture.executionOrg = "acme";
        mvc(fixture.service(fixture::readRange))
                .perform(post("/api/compare").header("X-Org", "acme").contentType("application/json").content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.match").value(true));
    }

    @Test
    void anotherOrgsCurrentExecutionDoesNotReadKafkaOrResults() throws Exception {
        Fixture fixture = new Fixture();
        fixture.expectedOrg = "acme";
        AtomicInteger reads = new AtomicInteger();
        CompareService service = fixture.service((bootstrap, topic, from, to, sink) -> {
            reads.incrementAndGet();
            fixture.readRange(bootstrap, topic, from, to, sink);
        });
        mvc(service).perform(post("/api/compare").header("X-Org", "acme")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("NO_EXECUTION"));
        assertThat(reads.get()).isZero();
        assertThat(fixture.resultQueries).isZero();
    }

    @Test
    void concurrentCompareReturns429AndReaderFailureReleasesLock() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        CompareService service = fixture.service((bootstrap, topic, from, to, sink) -> {
            if (attempts.getAndIncrement() == 0) {
                entered.countDown();
                try {
                    if (!release.await(60, TimeUnit.SECONDS)) throw new IllegalStateException("Reader timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                throw new IllegalStateException("Kafka unavailable");
            }
            fixture.readRange(bootstrap, topic, from, to, sink);
        });
        MockMvc mvc = mvc(service);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var running = executor.submit(service::compare);
            assertThat(entered.await(60, TimeUnit.SECONDS)).isTrue();
            mvc.perform(post("/api/compare").contentType("application/json").content("{}"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.error").value("COMPARE_BUSY"))
                    .andExpect(jsonPath("$.message").value("A comparison is already running"));
            release.countDown();
            assertThatThrownBy(() -> running.get(60, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);
            CompareResult retry = service.compare();
            assertThat(retry.match()).isTrue();
            assertThat(retry.matched()).isEqualTo(21);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private static MockMvc mvc(CompareService service) {
        return MockMvcBuilders.standaloneSetup(new CompareController(service)).addFilter(new OrgFilter()).build();
    }

    private static final class Fixture {
        private final Map<Integer, Long> initial = Map.of(0, 100L, 1, 200L);
        private final Map<Integer, Long> end = new LinkedHashMap<>();
        private final Map<Integer, List<RawRecord>> records = new LinkedHashMap<>();
        private final List<Map<String, Object>> windows = new ArrayList<>();
        private final List<Map<String, Object>> alerts = new ArrayList<>();
        private final Map<String, Map<Integer, Long>> starts = new LinkedHashMap<>();
        private final Map<String, String> manifests = new LinkedHashMap<>();
        private final List<Map<Integer, Long>> boundaries = new ArrayList<>();
        private final Map<String, ExecutionPlan> plans = new LinkedHashMap<>();
        private boolean lineageVisible = true;
        private boolean hasCompletedCheckpoint = true;
        private String expectedOrg = "demo";
        private String executionOrg = "demo";
        private int resultQueries;

        Fixture() throws Exception {
            this(false);
        }

        Fixture(boolean restored) throws Exception {
            if (restored) {
                starts.put("exec-root", initial);
                starts.put("exec-middle", Map.of(0, 101L, 1, 201L));
                starts.put("exec-current", Map.of(0, 111L, 1, 211L));
                manifests.put("exec-middle", "savepoints/sp-root/manifest.json");
                manifests.put("exec-current", "savepoints/sp-middle/manifest.json");
                plans.put("exec-root", PLAN);
                plans.put("exec-middle", PLAN);
                // Compatible stateless upgrade: replaying all ancestry with this filter loses rule history.
                var operators = PLAN.operators().stream().map(operator -> {
                    if (!operator.id().equals("pos")) return operator;
                    ObjectNode config = operator.config().deepCopy();
                    config.withArray("conditions").addObject().put("field", "eventTime")
                            .put("op", ">=").put("value", START + 9 * 60_000L);
                    return new PlannedOperator(operator.id(), operator.type(), config,
                            operator.inputs(), operator.outputType());
                }).toList();
                plans.put("exec-current", new ExecutionPlan(PLAN.pipelineId(), 2, PLAN.topic(),
                        PLAN.partitions(), PLAN.startFrom(), operators));
            } else {
                starts.put("exec-current", initial);
                plans.put("exec-current", PLAN);
            }
            boundaries.addAll(starts.values());
            for (int partition = 0; partition < 2; partition++) {
                String key = partition == 0 ? "truck-007" : "truck-008";
                List<RawRecord> input = new ArrayList<>();
                for (int minute = 0; minute <= 10; minute++) {
                    var event = Json.MAPPER.createObjectNode();
                    event.put("eventId", key + "-" + minute).put("schemaVersion", 1)
                            .put("type", "truck.position").put("eventTime", START + minute * 60_000L + 5_000L).put("key", key);
                    event.putObject("payload").put("truckId", key).put("x", 100).put("y", 100)
                            .put("speedKmh", partition == 0 ? 0 : 30).put("heading", 0)
                            .put("routeId", "r-1").put("status", partition == 0 ? "STOPPED" : "DRIVING");
                    input.add(new RawRecord(partition, initial.get(partition) + input.size(), event.toString()));
                    if (minute == 0) {
                        input.add(new RawRecord(partition, initial.get(partition) + input.size(), event.toString()));
                    }
                    if (restored && minute == 9) {
                        event.put("eventId", key + "-9-extra");
                        input.add(new RawRecord(partition, initial.get(partition) + input.size(), event.toString()));
                    }
                }
                records.put(partition, input);
                end.put(partition, initial.get(partition) + input.size());
                PartitionPipeline pipeline = null;
                ListCollector collector = null;
                var executions = new ArrayList<>(starts.keySet());
                for (int stage = 0; stage < executions.size(); stage++) {
                    String id = executions.get(stage);
                    pipeline = pipeline == null ? new PartitionPipeline(plans.get(id), partition)
                            : PartitionPipeline.restore(plans.get(id), partition, pipeline.snapshot());
                    collector = new ListCollector();
                    long from = starts.get(id).get(partition);
                    long to = stage + 1 == executions.size() ? end.get(partition)
                            : starts.get(executions.get(stage + 1)).get(partition);
                    for (RawRecord record : input) {
                        if (record.offset() >= from && record.offset() < to) {
                            pipeline.process(record.offset(), record.value(), collector);
                        }
                    }
                }
                for (ListCollector.Output output : collector.outputs()) {
                    if (output instanceof ListCollector.WindowOutput window) {
                        var result = window.result();
                        windows.add(Map.of("sink_id", window.sinkNodeId(), "key", result.key(),
                                "window_start", result.windowStart(), "window_end", result.windowEnd(),
                                "values", Json.MAPPER.writeValueAsString(result.values()), "event_count", result.eventCount()));
                    } else if (output instanceof ListCollector.AlertOutput alert) {
                        alerts.add(Map.of("sink_id", alert.sinkNodeId(), "alert_id", alert.alert().alertId(),
                                "key", alert.alert().key(), "event_time", alert.alert().eventTime()));
                    }
                }
            }
            boundaries.add(end);
        }

        CompareService service(CompareService.RangeReader reader) throws Exception {
            String plan = Json.MAPPER.writeValueAsString(plans.get("exec-current"));
            String to = Json.MAPPER.writeValueAsString(end);
            // Execute the real service row mappers against JDBC result-set mocks.
            JdbcTemplate jdbc = mock(JdbcTemplate.class, invocation -> {
                Object[] args = invocation.getRawArguments();
                String sql = (String) args[0];
                RowMapper<?> rowMapper = (RowMapper<?>) args[1];
                List<Map<String, Object>> rows;
                if (sql.contains("FROM executions")) {
                    assertThat(sql).contains("WHERE org_id=?");
                    assertThat((Object[]) args[2]).containsExactly(expectedOrg);
                    rows = executionOrg.equals(expectedOrg)
                            ? List.of(Map.of("id", "exec-current", "plan", plan)) : List.of();
                } else if (sql.contains("FROM audit_log saved")) {
                    assertThat(sql).contains("source.org_id=?");
                    Object[] parameters = (Object[]) args[2];
                    assertThat(parameters[1]).isEqualTo(expectedOrg);
                    String source = parameters[0].equals("savepoints/sp-middle/manifest.json")
                            ? "exec-middle" : "exec-root";
                    rows = lineageVisible ? List.of(Map.of("id", source,
                            "plan", Json.MAPPER.writeValueAsString(plans.get(source)))) : List.of();
                } else {
                    resultQueries++;
                    Object[] parameters = (Object[]) args[2];
                    if (sql.contains("FROM checkpoints") && sql.contains("checkpoint_id=0")) {
                        String id = (String) parameters[0];
                        assertThat(starts).containsKey(id);
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("source_offsets", Json.MAPPER.writeValueAsString(starts.get(id)));
                        row.put("manifest_key", manifests.get(id));
                        rows = List.of(row);
                    } else if (sql.contains("FROM checkpoints")) {
                        assertThat(parameters).containsExactly("exec-current");
                        rows = hasCompletedCheckpoint
                                ? List.of(Map.of("checkpoint_id", 7L, "source_offsets", to)) : List.of();
                    } else {
                        // Result reads are bounded by the replay horizon: (execution, end-time floor).
                        assertThat(parameters).hasSize(2);
                        assertThat(parameters[0]).isEqualTo("exec-current");
                        long floor = ((Number) parameters[1]).longValue();
                        if (sql.contains("FROM window_results")) {
                            assertThat(sql).contains("window_end>?");
                            rows = windows.stream()
                                    .filter(row -> ((Number) row.get("window_end")).longValue() > floor).toList();
                        } else if (sql.contains("FROM alerts")) {
                            assertThat(sql).contains("event_time>?");
                            rows = alerts.stream()
                                    .filter(row -> ((Number) row.get("event_time")).longValue() > floor).toList();
                        } else throw new AssertionError("Unexpected query: " + sql);
                    }
                }
                List<Object> mapped = new ArrayList<>();
                for (Map<String, Object> row : rows) {
                    ResultSet rs = mock(ResultSet.class, call -> {
                        Object value = row.get((String) call.getArgument(0));
                        return switch (call.getMethod().getName()) {
                            case "getString" -> value == null ? null : value.toString();
                            case "getLong" -> ((Number) value).longValue();
                            default -> throw new AssertionError("Unexpected result access: " + call.getMethod());
                        };
                    });
                    mapped.add(rowMapper.mapRow(rs, mapped.size()));
                }
                return invocation.getMethod().getName().equals("queryForObject") ? mapped.getFirst() : mapped;
            });
            return new CompareService(jdbc, Json.MAPPER, "test-kafka:9092", "truck-events", reader);
        }

        void readRange(String bootstrap, String topic, Map<Integer, Long> from, Map<Integer, Long> to,
                       Consumer<RawRecord> sink) {
            assertThat(bootstrap).isEqualTo("test-kafka:9092");
            assertThat(topic).isEqualTo("truck-events");
            // The service streams one partition at a time, so offsets are never materialised together.
            assertThat(from).hasSize(1);
            assertThat(to.keySet()).isEqualTo(from.keySet());
            int partition = from.keySet().iterator().next();
            int stage = -1;
            for (int i = 0; i < boundaries.size() - 1; i++) {
                if (boundaries.get(i).get(partition).equals(from.get(partition))) stage = i;
            }
            assertThat(stage).isBetween(0, boundaries.size() - 2);
            assertThat(to.get(partition)).isEqualTo(boundaries.get(stage + 1).get(partition));
            records.get(partition).stream()
                    .filter(record -> record.offset() >= from.get(partition) && record.offset() < to.get(partition))
                    .forEach(sink);
        }
    }
}
