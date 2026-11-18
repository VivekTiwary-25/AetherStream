package io.aetherstream.api.compare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.aetherstream.api.org.OrgFilter;
import io.aetherstream.common.Json;
import io.aetherstream.common.api.CompareResult;
import io.aetherstream.common.pipeline.ExecutionPlan;
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
    void onlyCheckpointZeroReturnsContractConflictWithoutReadingKafka() throws Exception {
        Fixture fixture = new Fixture();
        fixture.hasCompletedCheckpoint = false;
        AtomicInteger reads = new AtomicInteger();
        CompareService service = fixture.service((bootstrap, topic, from, to) -> {
            reads.incrementAndGet();
            return fixture.readRange(bootstrap, topic, from, to);
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
        CompareService service = fixture.service((bootstrap, topic, from, to) -> {
            reads.incrementAndGet();
            return fixture.readRange(bootstrap, topic, from, to);
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
        CompareService service = fixture.service((bootstrap, topic, from, to) -> {
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
            return fixture.readRange(bootstrap, topic, from, to);
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
        private boolean hasCompletedCheckpoint = true;
        private String expectedOrg = "demo";
        private String executionOrg = "demo";
        private int resultQueries;

        Fixture() throws Exception {
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
                }
                records.put(partition, input);
                end.put(partition, initial.get(partition) + input.size());
                PartitionPipeline pipeline = new PartitionPipeline(PLAN, partition);
                ListCollector collector = new ListCollector();
                for (RawRecord record : input) pipeline.process(record.offset(), record.value(), collector);
                for (ListCollector.Output output : collector.outputs()) {
                    if (output instanceof ListCollector.WindowOutput window) {
                        var result = window.result();
                        windows.add(Map.of("sink_id", window.sinkNodeId(), "key", result.key(),
                                "window_start", result.windowStart(), "window_end", result.windowEnd(),
                                "values", Json.MAPPER.writeValueAsString(result.values()), "event_count", result.eventCount()));
                    } else if (output instanceof ListCollector.AlertOutput alert) {
                        alerts.add(Map.of("sink_id", alert.sinkNodeId(), "alert_id", alert.alert().alertId()));
                    }
                }
            }
        }

        CompareService service(CompareService.RangeReader reader) throws Exception {
            String plan = Json.MAPPER.writeValueAsString(PLAN);
            String from = Json.MAPPER.writeValueAsString(initial);
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
                } else {
                    resultQueries++;
                    assertThat((Object[]) args[2]).containsExactly("exec-current");
                    if (sql.contains("FROM checkpoints")) {
                        if (sql.contains("checkpoint_id=0")) rows = List.of(Map.of("source_offsets", from));
                        else rows = hasCompletedCheckpoint
                                ? List.of(Map.of("checkpoint_id", 7L, "source_offsets", to)) : List.of();
                    } else if (sql.contains("FROM window_results")) rows = windows;
                    else if (sql.contains("FROM alerts")) rows = alerts;
                    else throw new AssertionError("Unexpected query: " + sql);
                }
                List<Object> mapped = new ArrayList<>();
                for (Map<String, Object> row : rows) {
                    ResultSet rs = mock(ResultSet.class, call -> {
                        Object value = row.get((String) call.getArgument(0));
                        return switch (call.getMethod().getName()) {
                            case "getString" -> value.toString();
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

        Map<Integer, List<RawRecord>> readRange(String bootstrap, String topic,
                                               Map<Integer, Long> from, Map<Integer, Long> to) {
            assertThat(bootstrap).isEqualTo("test-kafka:9092");
            assertThat(topic).isEqualTo("truck-events");
            assertThat(from).isEqualTo(initial);
            assertThat(to).isEqualTo(end);
            Map<Integer, List<RawRecord>> range = new LinkedHashMap<>();
            records.forEach((partition, input) -> range.put(partition, input.stream()
                    .filter(record -> record.offset() >= from.get(partition) && record.offset() < to.get(partition))
                    .toList()));
            return range;
        }
    }
}
