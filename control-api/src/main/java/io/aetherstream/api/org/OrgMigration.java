package io.aetherstream.api.org;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Additive migration for existing demo databases; existing resources remain in demo. */
@Component
public class OrgMigration implements ApplicationRunner {
    private final JdbcTemplate jdbc;

    public OrgMigration(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        jdbc.execute("ALTER TABLE pipelines ADD COLUMN IF NOT EXISTS org_id TEXT NOT NULL DEFAULT 'demo'");
        jdbc.execute("ALTER TABLE executions ADD COLUMN IF NOT EXISTS org_id TEXT NOT NULL DEFAULT 'demo'");
        jdbc.execute("CREATE INDEX IF NOT EXISTS pipelines_org_id_idx ON pipelines(org_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS executions_org_id_idx ON executions(org_id)");
    }
}