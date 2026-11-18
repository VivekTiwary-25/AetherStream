package io.aetherstream.tests;

import static org.assertj.core.api.Assertions.assertThat;

import io.aetherstream.common.pipeline.ExecutionPlan;
import io.aetherstream.engine.coordinator.CheckpointRecord;
import io.aetherstream.engine.coordinator.ExecutionRecord;
import io.aetherstream.engine.coordinator.PostgresExecutionRepository;
import io.aetherstream.tests.support.IntegrationInfrastructure;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

@Tag("integration")
class PostgresExecutionRepositoryIT {
    private PostgresExecutionRepository repository;

    @BeforeEach
    void setUp() {
        IntegrationInfrastructure.truncateAll();
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(IntegrationInfrastructure.jdbcUrl());
        dataSource.setUser("aether");
        dataSource.setPassword("aether");
        repository = new PostgresExecutionRepository(dataSource);
    }

    @Test
    void persistsExecutionGenerationCheckpointsAndAuditRows() {
        ExecutionPlan plan = new ExecutionPlan("pipeline-it", 3, "truck-events", 6, "earliest", java.util.List.of());
        ExecutionRecord execution = new ExecutionRecord("exec-it", "pipeline-it", 3, plan, "RUNNING", 1, null, 1_800_000_000_000L);
        repository.insertExecution(execution);

        assertThat(repository.findExecution("exec-it")).contains(execution);
        assertThat(repository.bumpGeneration("exec-it")).isEqualTo(2);
        assertThat(repository.findExecution("exec-it")).get().extracting(ExecutionRecord::generation).isEqualTo(2L);

        repository.insertCheckpoint(new CheckpointRecord("exec-it", 1, 2, CheckpointRecord.PENDING,
                null, null, 1_800_000_000_100L, null, null));
        repository.insertCheckpoint(new CheckpointRecord("exec-it", 2, 2, CheckpointRecord.PENDING,
                null, null, 1_800_000_000_200L, null, null));
        repository.completeCheckpoint("exec-it", 2, "checkpoints/2/manifest.json",
                Map.of(0, 17L, 2, 31L), 1_800_000_000_350L, 150);
        repository.abortCheckpoint("exec-it", 1);
        repository.insertCheckpoint(new CheckpointRecord("exec-it", 3, 2, CheckpointRecord.PENDING,
                null, null, 1_800_000_000_400L, null, null));
        repository.audit("execution.recovered", Map.of("checkpointId", 2, "generation", 2));

        assertThat(repository.findCheckpoint("exec-it", 2)).get().satisfies(checkpoint -> {
            assertThat(checkpoint.state()).isEqualTo(CheckpointRecord.COMPLETED);
            assertThat(checkpoint.sourceOffsets()).containsExactlyInAnyOrderEntriesOf(Map.of(0, 17L, 2, 31L));
            assertThat(checkpoint.manifestKey()).isEqualTo("checkpoints/2/manifest.json");
            assertThat(checkpoint.durationMs()).isEqualTo(150L);
        });
        assertThat(repository.findCheckpoint("exec-it", 1)).get().extracting(CheckpointRecord::state)
                .isEqualTo(CheckpointRecord.ABORTED);
        assertThat(repository.latestCompleted("exec-it")).get().extracting(CheckpointRecord::checkpointId).isEqualTo(2L);
        assertThat(repository.maxCheckpointId("exec-it")).isEqualTo(3L);
        assertThat(repository.abortPendingCheckpoints("exec-it")).isEqualTo(1);
        assertThat(repository.latestCompleted("exec-it")).get().extracting(CheckpointRecord::checkpointId).isEqualTo(2L);

        try (var connection = java.sql.DriverManager.getConnection(IntegrationInfrastructure.jdbcUrl(), "aether", "aether");
             var statement = connection.prepareStatement("SELECT action, detail->>'checkpointId' FROM audit_log");
             var rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("execution.recovered");
            assertThat(rows.getString(2)).isEqualTo("2");
        } catch (Exception e) {
            throw new AssertionError("cannot verify audit row", e);
        }
    }
}
