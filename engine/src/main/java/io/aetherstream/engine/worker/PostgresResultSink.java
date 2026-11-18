package io.aetherstream.engine.worker;

import io.aetherstream.common.Alert;
import io.aetherstream.common.DeadLetter;
import io.aetherstream.common.Json;
import io.aetherstream.common.WindowResult;
import io.aetherstream.compiler.ops.ListCollector;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import javax.sql.DataSource;

/** Fenced, batched, idempotent Postgres sink (contract 03 section 5). */
public final class PostgresResultSink implements ResultSink {
    static final String FENCE_SQL = "SELECT generation FROM executions WHERE id = ? FOR SHARE";
    static final String WINDOW_SQL =
            "INSERT INTO window_results (execution_id, sink_id, key, window_start, window_end, \"values\","
            + " event_count, generation) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)"
            + " ON CONFLICT (execution_id, sink_id, key, window_start) DO UPDATE SET"
            + " window_end = EXCLUDED.window_end, \"values\" = EXCLUDED.\"values\","
            + " event_count = EXCLUDED.event_count, generation = EXCLUDED.generation, written_at = now()";
    static final String ALERT_SQL =
            "INSERT INTO alerts (execution_id, sink_id, alert_id, key, rule_id, message, event_time, generation)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
            + " ON CONFLICT (execution_id, sink_id, alert_id) DO UPDATE SET"
            + " key = EXCLUDED.key, rule_id = EXCLUDED.rule_id, message = EXCLUDED.message,"
            + " event_time = EXCLUDED.event_time, generation = EXCLUDED.generation, written_at = now()";
    static final String DEAD_LETTER_SQL =
            "INSERT INTO dead_letters (execution_id, event_id, kind, reason, partition, \"offset\", event_time, raw,"
            + " generation) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
            + " ON CONFLICT (execution_id, event_id, kind) DO UPDATE SET"
            + " reason = EXCLUDED.reason, partition = EXCLUDED.partition, \"offset\" = EXCLUDED.\"offset\","
            + " event_time = EXCLUDED.event_time, raw = EXCLUDED.raw, generation = EXCLUDED.generation,"
            + " written_at = now()";

    private final DataSource dataSource;

    public PostgresResultSink(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public boolean write(String executionId, long generation, List<SinkRow> rows) throws Exception {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                if (!fenceOk(c, executionId, generation)) {
                    c.rollback();
                    return false;
                }
                try (PreparedStatement w = c.prepareStatement(WINDOW_SQL);
                     PreparedStatement a = c.prepareStatement(ALERT_SQL);
                     PreparedStatement d = c.prepareStatement(DEAD_LETTER_SQL)) {
                    int nw = 0;
                    int na = 0;
                    int nd = 0;
                    for (SinkRow row : rows) {
                        ListCollector.Output o = row.output();
                        if (o instanceof ListCollector.WindowOutput wo) {
                            bindWindow(w, executionId, generation, wo.sinkNodeId(), wo.result());
                            w.addBatch();
                            nw++;
                        } else if (o instanceof ListCollector.AlertOutput ao) {
                            bindAlert(a, executionId, generation, ao.sinkNodeId(), ao.alert());
                            a.addBatch();
                            na++;
                        } else if (o instanceof ListCollector.DeadLetterOutput dl) {
                            bindDeadLetter(d, executionId, generation, dl.deadLetter());
                            d.addBatch();
                            nd++;
                        }
                    }
                    if (nw > 0) {
                        w.executeBatch();
                    }
                    if (na > 0) {
                        a.executeBatch();
                    }
                    if (nd > 0) {
                        d.executeBatch();
                    }
                }
                c.commit();
                return true;
            } catch (Exception e) {
                try {
                    c.rollback();
                } catch (SQLException ignored) {
                    // connection is broken; the pool discards it
                }
                throw e;
            }
        }
    }

    private static boolean fenceOk(Connection c, String executionId, long generation) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(FENCE_SQL)) {
            ps.setString(1, executionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getLong(1) == generation;
            }
        }
    }

    private static void bindWindow(PreparedStatement ps, String executionId, long generation, String sinkId,
                                   WindowResult r) throws Exception {
        ps.setString(1, executionId);
        ps.setString(2, sinkId);
        ps.setString(3, r.key());
        ps.setLong(4, r.windowStart());
        ps.setLong(5, r.windowEnd());
        ps.setString(6, Json.MAPPER.writeValueAsString(r.values()));
        ps.setLong(7, r.eventCount());
        ps.setLong(8, generation);
    }

    private static void bindAlert(PreparedStatement ps, String executionId, long generation, String sinkId, Alert a)
            throws SQLException {
        ps.setString(1, executionId);
        ps.setString(2, sinkId);
        ps.setString(3, a.alertId());
        ps.setString(4, a.key());
        ps.setString(5, a.ruleId());
        ps.setString(6, a.message());
        ps.setLong(7, a.eventTime());
        ps.setLong(8, generation);
    }

    private static void bindDeadLetter(PreparedStatement ps, String executionId, long generation, DeadLetter d)
            throws SQLException {
        ps.setString(1, executionId);
        ps.setString(2, d.eventId());
        ps.setString(3, d.kind());
        ps.setString(4, d.reason());
        ps.setInt(5, d.partition());
        ps.setLong(6, d.offset());
        if (d.eventTime() == null) {
            ps.setNull(7, Types.BIGINT);
        } else {
            ps.setLong(7, d.eventTime());
        }
        ps.setString(8, d.raw());
        ps.setLong(9, generation);
    }
}
