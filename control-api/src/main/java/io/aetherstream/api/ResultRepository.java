package io.aetherstream.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aetherstream.api.org.OrgContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class ResultRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    ResultRepository(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    String currentExecutionId() {
        List<String> ids = jdbc.query("SELECT id FROM executions WHERE org_id=? ORDER BY (state <> 'STOPPED') DESC, created_at DESC LIMIT 1",
                (rs, row) -> rs.getString(1), OrgContext.current());
        return ids.isEmpty() ? null : ids.get(0);
    }

    List<Map<String, Object>> windows(String executionId, String sinkId, int limit) {
        return jdbc.query("SELECT sink_id,key,window_start,window_end,values::text,event_count FROM window_results " +
                        "WHERE execution_id=? AND (CAST(? AS TEXT) IS NULL OR sink_id=?) ORDER BY window_end DESC LIMIT ?",
                this::windowRow, executionId, sinkId, sinkId, limit);
    }

    private Map<String, Object> windowRow(ResultSet rs, int row) throws SQLException {
        return Map.of("sinkId", rs.getString("sink_id"), "key", rs.getString("key"),
                "windowStart", rs.getLong("window_start"), "windowEnd", rs.getLong("window_end"),
                "values", json(rs.getString("values")), "eventCount", rs.getLong("event_count"));
    }

    List<Map<String, Object>> alerts(String executionId, int limit) {
        return jdbc.query("SELECT alert_id,key,rule_id,message,event_time FROM alerts WHERE execution_id=? ORDER BY event_time DESC LIMIT ?",
                (rs, row) -> Map.of("alertId", rs.getString("alert_id"), "key", rs.getString("key"),
                        "ruleId", rs.getString("rule_id"), "message", rs.getString("message"),
                        "eventTime", rs.getLong("event_time")), executionId, limit);
    }

    List<Map<String, Object>> deadLetters(String executionId, String kind, int limit) {
        return jdbc.query("SELECT event_id,kind,reason,partition,\"offset\",event_time FROM dead_letters " +
                        "WHERE execution_id=? AND (CAST(? AS TEXT) IS NULL OR kind=?) ORDER BY written_at DESC LIMIT ?",
                (rs, row) -> {
                    Map<String, Object> value = new java.util.LinkedHashMap<>();
                    value.put("eventId", rs.getString("event_id")); value.put("kind", rs.getString("kind"));
                    value.put("reason", rs.getString("reason")); value.put("partition", rs.getInt("partition"));
                    value.put("offset", rs.getLong("offset"));
                    long eventTime = rs.getLong("event_time");
                    value.put("eventTime", rs.wasNull() ? null : eventTime);
                    return value;
                }, executionId, kind, kind, limit);
    }

    List<Map<String, Object>> checkpoints(String executionId, int limit) {
        return jdbc.query("SELECT checkpoint_id,state,generation,duration_ms,completed_at,source_offsets::text FROM checkpoints " +
                        "WHERE execution_id=? ORDER BY checkpoint_id DESC LIMIT ?",
                (rs, row) -> Map.of("checkpointId", rs.getLong("checkpoint_id"), "state", rs.getString("state"),
                        "generation", rs.getLong("generation"), "durationMs", rs.getObject("duration_ms") == null ? 0L : rs.getLong("duration_ms"),
                        "completedAt", rs.getTimestamp("completed_at") == null ? "" : rs.getTimestamp("completed_at").toInstant().toString(),
                        "sourceOffsets", json(rs.getString("source_offsets"))), executionId, limit);
    }

    Map<String, Object> summary(String executionId, double eventsPerSec) {
        Long windows = count("SELECT count(*) FROM window_results WHERE execution_id=?", executionId);
        Long alerts = count("SELECT count(*) FROM alerts WHERE execution_id=?", executionId);
        Long rejected = count("SELECT count(*) FROM dead_letters WHERE execution_id=? AND kind='REJECTED'", executionId);
        Long late = count("SELECT count(*) FROM dead_letters WHERE execution_id=? AND kind='LATE'", executionId);
        List<Map<String, Object>> aggregates = jdbc.query(
                "SELECT COALESCE(sum((values->>'deliveries')::double precision) FILTER (WHERE sink_id='delSink'),0) AS deliveries, " +
                        "avg((values->>'avgSpeedKmh')::double precision) FILTER (WHERE sink_id='speedSink') AS speed " +
                        "FROM window_results WHERE execution_id=? AND window_end=(SELECT max(window_end) FROM window_results WHERE execution_id=?)",
                (rs, row) -> Map.of("deliveries", rs.getDouble("deliveries"),
                        "speed", rs.getObject("speed") == null ? 0.0 : rs.getDouble("speed")), executionId, executionId);
        double deliveries = 0, speed = 0;
        if (!aggregates.isEmpty()) {
            deliveries = (double) aggregates.get(0).get("deliveries");
            speed = (double) aggregates.get(0).get("speed");
        }
        return Map.of("executionId", executionId == null ? "" : executionId, "windowsWritten", windows,
                "alerts", alerts, "rejected", rejected, "late", late, "deliveriesLastMinute", deliveries,
                "avgSpeedLastMinute", speed, "eventsPerSec", eventsPerSec);
    }

    private Long count(String sql, String executionId) {
        return executionId == null ? 0L : jdbc.queryForObject(sql, Long.class, executionId);
    }
    private JsonNode json(String text) {
        try { return text == null ? mapper.createObjectNode() : mapper.readTree(text); }
        catch (Exception e) { return mapper.createObjectNode(); }
    }
}
