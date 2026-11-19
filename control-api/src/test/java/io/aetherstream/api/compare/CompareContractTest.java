package io.aetherstream.api.compare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aetherstream.compiler.PipelineCompiler;
import io.aetherstream.reference.RawRecord;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CompareController.class)
@Import(CompareContractTest.Configuration.class)
class CompareContractTest {
    private static final long TIME = 1_795_251_600_000L;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean JdbcTemplate jdbc;
    @MockitoBean CompareService.RangeReader reader;
    private String mode;

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean CompareService comparison(JdbcTemplate jdbc, ObjectMapper mapper, CompareService.RangeReader reader) {
            return new CompareService(jdbc, mapper, "unused:9092", "truck-events", reader);
        }
    }

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void checkpointRangeAndEngineRows() throws Exception {
        mode = "MATCH";
        String plan = mapper.writeValueAsString(new PipelineCompiler().plan(PipelineCompiler.demoPipeline(), 1));
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            List<Map<String, Object>> rows;
            if (sql.contains("FROM executions")) {
                rows = "alpha".equals(invocation.getArgument(2))
                        ? List.of(Map.of("id", "exec-compare", "plan", plan)) : List.of();
            } else if (sql.contains("FROM checkpoints")) {
                rows = mode.equals("NO_CHECKPOINT") ? List.of()
                        : List.of(Map.of("checkpoint_id", 7L, "source_offsets", "{\"0\":2}"));
            } else if (sql.contains("FROM window_results")) {
                rows = mode.equals("MISSING") ? List.of() : mode.equals("EXTRA")
                        ? List.of(window(TIME, 1), window(TIME - 60_000, 1))
                        : List.of(window(TIME, mode.equals("DIFFERENT") ? 2 : 1));
            } else if (sql.contains("FROM alerts")) {
                rows = List.of();
            } else {
                throw new AssertionError("Unexpected query: " + sql);
            }
            RowMapper rowMapper = invocation.getArgument(1);
            List<Object> result = new ArrayList<>();
            for (int index = 0; index < rows.size(); index++) result.add(rowMapper.mapRow(row(rows.get(index)), index));
            return result;
        });
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            RowMapper rowMapper = invocation.getArgument(1);
            return rowMapper.mapRow(row(Map.of("source_offsets", "{\"0\":0}")), 0);
        });
        doAnswer(invocation -> {
            Consumer<RawRecord> sink = invocation.getArgument(4);
            sink.accept(new RawRecord(0, 0, event("first", TIME, 10)));
            sink.accept(new RawRecord(0, 1, event("watermark", TIME + 65_000, 20)));
            return null;
        }).when(reader).read(eq("unused:9092"), eq("truck-events"), eq(Map.of(0, 0L)), eq(Map.of(0, 2L)), any());
    }

    @Test void compareReplaysCompletedRangeAndSerializesMatchedResult() throws Exception {
        JsonNode body = compare("alpha", 200);
        fields(body, "checkpointId", "referenceResults", "engineResults", "matched", "mismatches", "recordsReplayed", "durationMs", "match");
        for (String name : List.of("checkpointId", "referenceResults", "engineResults", "matched", "recordsReplayed", "durationMs"))
            assertThat(body.path(name).isIntegralNumber()).as(name).isTrue();
        assertThat(body.path("checkpointId").longValue()).isEqualTo(7);
        assertThat(body.path("referenceResults").longValue()).isEqualTo(1);
        assertThat(body.path("engineResults").longValue()).isEqualTo(1);
        assertThat(body.path("matched").longValue()).isEqualTo(1);
        assertThat(body.path("recordsReplayed").longValue()).isEqualTo(2);
        assertThat(body.path("durationMs").longValue()).isNotNegative();
        assertThat(body.path("match").isBoolean()).isTrue();
        assertThat(body.path("match").booleanValue()).isTrue();
        assertThat(body.path("mismatches").isArray()).isTrue();
        assertThat(body.path("mismatches").isEmpty()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING", "DIFFERENT", "EXTRA"})
    void mismatchesExposeConsumerFieldsAndMakeMatchFalse(String type) throws Exception {
        mode = type;
        JsonNode body = compare("alpha", 200);
        assertThat(body.path("match").booleanValue()).isFalse();
        JsonNode mismatches = body.path("mismatches");
        assertThat(mismatches.isArray()).isTrue();
        assertThat(mismatches.size()).isEqualTo(1);
        JsonNode mismatch = mismatches.get(0);
        fields(mismatch, "type", "sinkId", "key", "windowStart", "detail");
        for (String name : List.of("type", "sinkId", "key", "detail"))
            assertThat(mismatch.path(name).isTextual()).as(name).isTrue();
        assertThat(mismatch.path("type").asText()).isEqualTo(type);
        assertThat(mismatch.path("sinkId").asText()).isEqualTo("speedSink");
        assertThat(mismatch.path("key").asText()).isEqualTo("truck-001");
        assertThat(mismatch.path("windowStart").isIntegralNumber()).isTrue();
        assertThat(mismatch.path("detail").asText()).isNotBlank();
    }

    @Test void anotherOrgCannotReplayOrCompareForeignResults() throws Exception {
        error(compare("beta", 409), "NO_EXECUTION");
        verifyNoInteractions(reader);
    }

    @Test void noCompletedCheckpointReturnsErrorWithoutReadingKafka() throws Exception {
        mode = "NO_CHECKPOINT";
        error(compare("alpha", 409), "NO_CHECKPOINT");
        verifyNoInteractions(reader);
    }

    private JsonNode compare(String org, int expectedStatus) throws Exception {
        var response = mvc.perform(post("/api/compare").header("X-Org", org).contentType("application/json").content("{}"))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse();
        assertThat(response.getContentType()).startsWith("application/json");
        return mapper.readTree(response.getContentAsString());
    }
    private static void error(JsonNode body, String code) {
        fields(body, "error", "message");
        assertThat(body.path("error").asText()).isEqualTo(code);
        assertThat(body.path("message").isTextual()).isTrue();
        assertThat(body.path("message").asText()).isNotBlank();
    }
    private static void fields(JsonNode body, String... expected) {
        List<String> names = new ArrayList<>();
        body.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactlyInAnyOrder(expected);
    }
    private static Map<String, Object> window(long start, long count) {
        return Map.of("sink_id", "speedSink", "key", "truck-001", "window_start", start, "window_end", start + 60_000,
                "values", "{\"avgSpeedKmh\":10.0,\"maxSpeedKmh\":10.0,\"count\":1.0}", "event_count", count);
    }
    private static ResultSet row(Map<String, Object> values) {
        return mock(ResultSet.class, invocation -> {
            String method = invocation.getMethod().getName();
            if (!method.startsWith("get")) return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
            Object value = values.get(invocation.getArgument(0).toString());
            return switch (method) {
                case "getString" -> value == null ? null : value.toString();
                case "getLong" -> value == null ? 0L : ((Number) value).longValue();
                default -> value;
            };
        });
    }
    private static String event(String id, long time, int speed) {
        return """
                {"eventId":"%s","schemaVersion":1,"type":"truck.position","eventTime":%d,"key":"truck-001",
                 "payload":{"truckId":"truck-001","x":500,"y":700,"speedKmh":%d,"heading":90,"routeId":"r-1","status":"DRIVING"}}
                """.formatted(id, time, speed);
    }
}
