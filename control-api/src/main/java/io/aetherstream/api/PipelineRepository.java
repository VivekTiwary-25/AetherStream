package io.aetherstream.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aetherstream.common.pipeline.PipelineDefinition;
import io.aetherstream.api.org.OrgContext;
import io.aetherstream.api.org.OrgException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class PipelineRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    PipelineRepository(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    @Transactional
    public PipelineSummary create(PipelineDefinition definition) {
        // A row lock cannot protect the first version of an id that does not exist yet.
        jdbc.execute("LOCK TABLE pipelines IN SHARE ROW EXCLUSIVE MODE");
        Boolean foreign = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pipelines WHERE id=? AND org_id<>?)",
                Boolean.class, definition.id(), OrgContext.current());
        if (Boolean.TRUE.equals(foreign))
            throw new OrgException(HttpStatus.CONFLICT, "PIPELINE_ID_TAKEN", "pipeline id is already taken");
        int version = jdbc.queryForObject("SELECT COALESCE(max(version),0)+1 FROM pipelines WHERE id=?",
                Integer.class, definition.id());
        PipelineDefinition stored = new PipelineDefinition(definition.id(), version, definition.name(),
                definition.nodes(), definition.edges());
        PipelineSummary summary = jdbc.queryForObject(
                "INSERT INTO pipelines(id,version,name,definition,org_id) VALUES (?,?,?,?::jsonb,?) RETURNING id,version,name,created_at",
                this::summaryRow, stored.id(), stored.version(), stored.name(), json(stored), OrgContext.current());
        jdbc.update("INSERT INTO audit_log(actor,action,detail) VALUES ('ui','pipeline.created',?::jsonb)",
                OrgContext.detail(summary).toString());
        return summary;
    }

    List<PipelineSummary> list() {
        return jdbc.query("SELECT DISTINCT ON (id) id,version,name,created_at FROM pipelines WHERE org_id=? ORDER BY id,version DESC",
                this::summaryRow, OrgContext.current());
    }

    Optional<PipelineDefinition> latest(String id) {
        List<PipelineDefinition> rows = jdbc.query(
                "SELECT definition::text FROM pipelines WHERE id=? AND org_id=? ORDER BY version DESC LIMIT 1",
                (rs, row) -> definition(rs.getString(1)), id, OrgContext.current());
        return rows.stream().findFirst();
    }

    private PipelineSummary summaryRow(ResultSet rs, int row) throws SQLException {
        return new PipelineSummary(rs.getString("id"), rs.getInt("version"), rs.getString("name"),
                rs.getTimestamp("created_at").toInstant().toString());
    }

    private PipelineDefinition definition(String json) {
        try { return mapper.readValue(json, PipelineDefinition.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("stored pipeline is not valid JSON", e); }
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("cannot serialize pipeline", e); }
    }

    record PipelineSummary(String id, int version, String name, String createdAt) {}
}
