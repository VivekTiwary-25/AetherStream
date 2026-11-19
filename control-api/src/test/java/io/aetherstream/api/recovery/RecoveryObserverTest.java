package io.aetherstream.api.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class RecoveryObserverTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final RecoveryHistory history = new RecoveryHistory();
    private final ScheduledExecutorService timer = mock(ScheduledExecutorService.class);

    @Test void lifecycleStartsObservationWithNoHubOrSocketAndKeepsOwnership() throws Exception {
        AtomicReference<Runnable> scheduled = new AtomicReference<>();
        doAnswer(invocation -> {
            scheduled.set(invocation.getArgument(0));
            return mock(ScheduledFuture.class);
        }).when(timer).scheduleWithFixedDelay(any(Runnable.class), eq(0L), eq(250L), eq(TimeUnit.MILLISECONDS));
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("id")).thenReturn(42L);
        when(row.getTimestamp("at")).thenReturn(new Timestamp(1_000));
        when(row.getString("actor")).thenReturn("coordinator");
        when(row.getString("action")).thenReturn("worker.dead");
        when(row.getString("detail")).thenReturn("{\"executionId\":\"exec-1\",\"workerId\":\"worker-2\"}");
        when(row.getString("org_id")).thenReturn("alpha");
        List<Long> cursors = new ArrayList<>();
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<RecoveryHistory.AuditFact>>any(), any(Object[].class)))
                .thenAnswer(invocation -> {
                    cursors.add(invocation.getArgument(2));
                    RowMapper<RecoveryHistory.AuditFact> rowMapper = invocation.getArgument(1);
                    return List.of(rowMapper.mapRow(row, 0));
                });
        RecoveryObserver observer = new RecoveryObserver(jdbc, mapper, history, timer);
        try {
            observer.start();
            assertThat(scheduled.get()).isNotNull();
            scheduled.get().run();
            scheduled.get().run();
            assertThat(cursors).containsExactly(0L, 42L);
            assertThat(history.history("alpha")).singleElement().satisfies(entry ->
                    assertThat(entry).containsEntry("event", "WORKER_DEAD").containsEntry("workerId", "worker-2")
                            .containsEntry("at", 1_000L));
            assertThat(history.history("demo")).isEmpty();
        } finally {
            observer.close();
        }
        verify(timer).shutdownNow();
    }

    @Test void failedDatabaseReadDoesNotLoseFactsOrAdvanceCursor() throws Exception {
        var fact = new RecoveryHistory.AuditFact(7, 100, "coordinator", "execution.recovered",
                mapper.readTree("{\"executionId\":\"exec-1\",\"generation\":2,\"checkpointId\":4,\"recoveryDurationMs\":50}"), "alpha");
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<RecoveryHistory.AuditFact>>any(), any(Object[].class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("unavailable"))
                .thenReturn(List.of(fact));
        AtomicReference<Runnable> scheduled = new AtomicReference<>();
        doAnswer(invocation -> { scheduled.set(invocation.getArgument(0)); return mock(ScheduledFuture.class); })
                .when(timer).scheduleWithFixedDelay(any(Runnable.class), eq(0L), eq(250L), eq(TimeUnit.MILLISECONDS));
        RecoveryObserver observer = new RecoveryObserver(jdbc, mapper, history, timer);
        try {
            observer.start();
            scheduled.get().run();
            assertThat(history.history("alpha")).isEmpty();
            scheduled.get().run();
            assertThat(history.history("alpha")).singleElement().satisfies(entry ->
                    assertThat(entry).containsEntry("event", "RECOVERED").containsEntry("durationMs", 50L));
            verify(jdbc, org.mockito.Mockito.times(2)).query(anyString(),
                    ArgumentMatchers.<RowMapper<RecoveryHistory.AuditFact>>any(), eq(0L));
        } finally {
            observer.close();
        }
    }
}
