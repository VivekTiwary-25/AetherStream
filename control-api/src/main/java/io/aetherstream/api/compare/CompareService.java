package io.aetherstream.api.compare;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aetherstream.api.org.OrgContext;
import io.aetherstream.common.api.CompareResult;
import io.aetherstream.common.pipeline.ExecutionPlan;
import io.aetherstream.reference.EngineAlertRow;
import io.aetherstream.reference.EngineWindowRow;
import io.aetherstream.reference.KafkaRangeReader;
import io.aetherstream.reference.RawRecord;
import io.aetherstream.reference.ReferenceExecutor;
import io.aetherstream.reference.ResultComparator;
import io.aetherstream.reference.ReferenceExecutor.ReplayStage;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class CompareService {
    private static final Logger LOG = LoggerFactory.getLogger(CompareService.class);
    private static final TypeReference<Map<Integer, Long>> OFFSETS = new TypeReference<>() {};
    private static final TypeReference<Map<String, Double>> VALUES = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final String bootstrap;
    private final String topic;
    private final RangeReader rangeReader;
    private final ReentrantLock lock = new ReentrantLock();

    @Autowired
    public CompareService(JdbcTemplate jdbc, ObjectMapper mapper,
                          @Value("${aetherstream.kafka-bootstrap}") String bootstrap,
                          @Value("${KAFKA_TOPIC:truck-events}") String topic) {
        this(jdbc, mapper, bootstrap, topic, new KafkaRangeReader()::read);
    }

    CompareService(JdbcTemplate jdbc, ObjectMapper mapper, String bootstrap, String topic, RangeReader rangeReader) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.bootstrap = bootstrap;
        this.topic = topic;
        this.rangeReader = rangeReader;
    }

    public CompareResult compare() {
        if (!lock.tryLock()) {
            throw new CompareException(HttpStatus.TOO_MANY_REQUESTS, "COMPARE_BUSY", "A comparison is already running");
        }
        long started = System.nanoTime();
        try {
            // Keep selection identical to ResultRepository.currentExecutionId, including stopped executions.
            List<Execution> executions = jdbc.query(
                    "SELECT id,plan::text FROM executions WHERE org_id=? ORDER BY (state <> 'STOPPED') DESC, created_at DESC LIMIT 1",
                    (rs, row) -> new Execution(rs.getString("id"), readJson(rs.getString("plan"), ExecutionPlan.class)),
                    OrgContext.current());
            if (executions.isEmpty()) {
                throw new CompareException(HttpStatus.CONFLICT, "NO_EXECUTION", "No current execution to compare");
            }
            Execution execution = executions.get(0);
            List<Checkpoint> completed = jdbc.query(
                    "SELECT checkpoint_id,source_offsets::text FROM checkpoints WHERE execution_id=? " +
                            "AND state='COMPLETED' AND checkpoint_id>0 ORDER BY checkpoint_id DESC LIMIT 1",
                    (rs, row) -> new Checkpoint(rs.getLong("checkpoint_id"), readJson(rs.getString("source_offsets"), OFFSETS)),
                    execution.id());
            if (completed.isEmpty()) {
                throw new CompareException(HttpStatus.CONFLICT, "NO_CHECKPOINT",
                        "No completed checkpoint yet - wait a few seconds");
            }
            Checkpoint checkpoint = completed.get(0);
            var stages = replayLineage(execution, checkpoint.offsets());
            var reference = new ReferenceExecutor().run(stages);
            List<EngineWindowRow> windows = jdbc.query(
                    "SELECT sink_id,key,window_start,window_end,values::text,event_count FROM window_results WHERE execution_id=?",
                    (rs, row) -> new EngineWindowRow(rs.getString("sink_id"), rs.getString("key"),
                            rs.getLong("window_start"), rs.getLong("window_end"),
                            readJson(rs.getString("values"), VALUES), rs.getLong("event_count")), execution.id());
            List<EngineAlertRow> alerts = jdbc.query(
                    "SELECT sink_id,alert_id FROM alerts WHERE execution_id=?",
                    (rs, row) -> new EngineAlertRow(rs.getString("sink_id"), rs.getString("alert_id")), execution.id());
            CompareResult result = new ResultComparator().compare(reference, windows, alerts, checkpoint.id(), 0);
            return new CompareResult(result.checkpointId(), result.referenceResults(), result.engineResults(),
                    result.matched(), result.mismatches(), result.recordsReplayed(), elapsedMs(started), result.match());
        } finally {
            lock.unlock();
            LOG.info("Reference comparison finished in {} ms", elapsedMs(started));
        }
    }

    private List<ReplayStage> replayLineage(Execution execution, Map<Integer, Long> endOffsets) {
        List<ReplayRange> ranges = new ArrayList<>();
        var visited = new HashSet<String>();
        while (true) {
            if (!visited.add(execution.id())) {
                throw new CompareException(HttpStatus.CONFLICT, "INVALID_LINEAGE", "Savepoint lineage contains a cycle");
            }
            InitialCheckpoint initial = jdbc.queryForObject(
                    "SELECT source_offsets::text,manifest_key FROM checkpoints " +
                            "WHERE execution_id=? AND checkpoint_id=0 AND state='COMPLETED'",
                    (rs, row) -> new InitialCheckpoint(readJson(rs.getString("source_offsets"), OFFSETS),
                            rs.getString("manifest_key")), execution.id());
            ranges.addFirst(new ReplayRange(execution.plan(), initial.offsets(), endOffsets));
            if (initial.manifestKey() == null) break;
            // Checkpoint 0 keeps the immutable savepoint manifest even after ordinary recovery.
            List<Execution> sources = jdbc.query(
                    "SELECT source.id,source.plan::text FROM audit_log saved " +
                            "JOIN executions source ON source.id=saved.detail->>'executionId' " +
                            "WHERE saved.action='savepoint.created' " +
                            "AND ('savepoints/' || (saved.detail->>'savepointId') || '/manifest.json')=? " +
                            "AND source.org_id=? ORDER BY saved.id DESC LIMIT 1",
                    (rs, row) -> new Execution(rs.getString("id"), readJson(rs.getString("plan"), ExecutionPlan.class)),
                    initial.manifestKey(), OrgContext.current());
            if (sources.isEmpty()) {
                throw new CompareException(HttpStatus.CONFLICT, "NO_LINEAGE", "Savepoint source execution is unavailable");
            }
            execution = sources.getFirst();
            endOffsets = initial.offsets();
        }
        List<ReplayStage> stages = new ArrayList<>(ranges.size());
        for (ReplayRange range : ranges) {
            stages.add(new ReplayStage(range.plan(),
                    rangeReader.read(bootstrap, topic, range.from(), range.to())));
        }
        return stages;
    }

    private <T> T readJson(String json, Class<T> type) throws SQLException {
        try { return mapper.readValue(json, type); }
        catch (JsonProcessingException e) { throw new SQLException("Invalid comparison JSON", e); }
    }

    private <T> T readJson(String json, TypeReference<T> type) throws SQLException {
        try { return mapper.readValue(json, type); }
        catch (JsonProcessingException e) { throw new SQLException("Invalid comparison JSON", e); }
    }

    private static long elapsedMs(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    @FunctionalInterface
    interface RangeReader {
        Map<Integer, List<RawRecord>> read(String bootstrap, String topic,
                                         Map<Integer, Long> fromInclusive, Map<Integer, Long> toExclusive);
    }

    private record Execution(String id, ExecutionPlan plan) {}
    private record Checkpoint(long id, Map<Integer, Long> offsets) {}
    private record InitialCheckpoint(Map<Integer, Long> offsets, String manifestKey) {}
    private record ReplayRange(ExecutionPlan plan, Map<Integer, Long> from, Map<Integer, Long> to) {}

    static final class CompareException extends RuntimeException {
        final HttpStatus status;
        final String code;

        CompareException(HttpStatus status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }
}
