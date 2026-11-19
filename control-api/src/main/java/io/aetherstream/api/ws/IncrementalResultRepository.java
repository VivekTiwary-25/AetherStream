package io.aetherstream.api.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Inclusive, unbounded polls; callers overlap written_at to cover transactions committing late. */
@Repository
public class IncrementalResultRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public IncrementalResultRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<Row> windows(String executionId, Instant since) {
        return jdbc.query("""
                SELECT execution_id, sink_id, key, window_start, window_end,
                       values::text AS values, event_count, written_at
                FROM window_results
                WHERE execution_id = ? AND written_at >= ?
                ORDER BY written_at ASC
                """, this::windowRow, executionId, Timestamp.from(since));
    }

    public List<Row> alerts(String executionId, Instant since) {
        return jdbc.query("""
                SELECT execution_id, sink_id, alert_id, key, rule_id, message, event_time, written_at
                FROM alerts
                WHERE execution_id = ? AND written_at >= ?
                ORDER BY written_at ASC
                """, (rs, row) -> new Row(rs.getTimestamp("written_at").toInstant(),
                List.of(rs.getString("execution_id"), rs.getString("sink_id"), rs.getString("alert_id")),
                Map.of("alertId", rs.getString("alert_id"), "key", rs.getString("key"),
                        "ruleId", rs.getString("rule_id"), "message", rs.getString("message"),
                        "eventTime", rs.getLong("event_time"))), executionId, Timestamp.from(since));
    }

    public List<Row> deadLetters(String executionId, Instant since) {
        return jdbc.query("""
                SELECT execution_id, event_id, kind, reason, partition, "offset", event_time, written_at
                FROM dead_letters
                WHERE execution_id = ? AND written_at >= ?
                ORDER BY written_at ASC
                """, (rs, row) -> {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("eventId", rs.getString("event_id"));
                    data.put("kind", rs.getString("kind"));
                    data.put("reason", rs.getString("reason"));
                    data.put("partition", rs.getInt("partition"));
                    data.put("offset", rs.getLong("offset"));
                    long eventTime = rs.getLong("event_time");
                    data.put("eventTime", rs.wasNull() ? null : eventTime);
                    return new Row(rs.getTimestamp("written_at").toInstant(),
                            List.of(rs.getString("execution_id"), rs.getString("event_id"), rs.getString("kind")),
                            data);
                }, executionId, Timestamp.from(since));
    }

    public Counts counts(String executionId) {
        return jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE kind = 'REJECTED') AS rejected,
                       count(*) FILTER (WHERE kind = 'LATE') AS late
                FROM dead_letters WHERE execution_id = ?
                """, (rs, row) -> new Counts(rs.getLong("rejected"), rs.getLong("late")), executionId);
    }

    private Row windowRow(ResultSet rs, int row) throws SQLException {
        return new Row(rs.getTimestamp("written_at").toInstant(),
                List.of(rs.getString("execution_id"), rs.getString("sink_id"), rs.getString("key"),
                        rs.getLong("window_start")),
                Map.of("sinkId", rs.getString("sink_id"), "key", rs.getString("key"),
                        "windowStart", rs.getLong("window_start"), "windowEnd", rs.getLong("window_end"),
                        "values", json(rs.getString("values")), "eventCount", rs.getLong("event_count")));
    }

    private JsonNode json(String text) throws SQLException {
        try {
            JsonNode value = mapper.readTree(text);
            if (value == null) {
                throw new SQLException("Window values contain no JSON value");
            }
            return value;
        } catch (JsonProcessingException exception) {
            throw new SQLException("Cannot parse window values JSON", exception);
        }
    }

    /** The key contains the complete database primary key, not merely the payload's key. */
    public record Row(Instant writtenAt, List<Object> key, Map<String, Object> data) {}

    public record Counts(long rejected, long late) {}
}
