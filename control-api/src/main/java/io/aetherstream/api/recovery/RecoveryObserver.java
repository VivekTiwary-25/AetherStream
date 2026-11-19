package io.aetherstream.api.recovery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

/** Independent of LiveHub: terminal-triggered failures are observed with zero connected browsers. */
@Component
public class RecoveryObserver {
    private static final Logger log = LoggerFactory.getLogger(RecoveryObserver.class);
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final RecoveryHistory history;
    private final ScheduledExecutorService timer;
    private long cursor;

    @Autowired
    public RecoveryObserver(JdbcTemplate jdbc, ObjectMapper mapper, RecoveryHistory history) {
        this(jdbc, mapper, history, Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "recovery-audit-observer");
            thread.setDaemon(true);
            return thread;
        }));
    }

    RecoveryObserver(JdbcTemplate jdbc, ObjectMapper mapper, RecoveryHistory history, ScheduledExecutorService timer) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.history = history;
        this.timer = timer;
    }

    @PostConstruct
    public void start() {
        timer.scheduleWithFixedDelay(this::observeSafely, 0, 250, TimeUnit.MILLISECONDS);
    }

    private void observeSafely() {
        try { poll(); }
        catch (RuntimeException error) { log.debug("Recovery audit observation unavailable: {}", error.toString()); }
    }

    /** A failed read never advances the cursor; repeated successful batches cannot duplicate events. */
    synchronized void poll() {
        List<RecoveryHistory.AuditFact> facts = jdbc.query("SELECT a.id,a.at,a.actor,a.action,a.detail::text,e.org_id " +
                        "FROM audit_log a LEFT JOIN executions e ON e.id=a.detail->>'executionId' " +
                        "WHERE a.id>? AND a.action IN ('worker.dead','worker.restarted','execution.recoveryStarted'," +
                        "'execution.recovered','execution.assignment','checkpoint.completed') ORDER BY a.id LIMIT 500",
                (rs, row) -> {
                    try {
                        return new RecoveryHistory.AuditFact(rs.getLong("id"), rs.getTimestamp("at").getTime(),
                                rs.getString("actor"), rs.getString("action"), mapper.readTree(rs.getString("detail")),
                                rs.getString("org_id"));
                    } catch (JsonProcessingException error) {
                        throw new SQLException("Invalid recovery audit detail", error);
                    }
                }, cursor);
        for (RecoveryHistory.AuditFact fact : facts) {
            history.observe(fact);
            cursor = fact.id();
        }
    }

    @PreDestroy
    public void close() { timer.shutdownNow(); }
}
