package io.aetherstream.api.org;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

public final class OrgAccess {
    private OrgAccess() {}

    public static void requireExecution(JdbcTemplate jdbc, String id) {
        Boolean owned = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM executions WHERE id=? AND org_id=?)",
                Boolean.class, id, OrgContext.current());
        if (!Boolean.TRUE.equals(owned))
            throw new OrgException(HttpStatus.NOT_FOUND, "NOT_FOUND", "execution not found");
    }

    /** The single coordinator cannot run two organisations simultaneously. Call under the start lock. */
    public static void requireAvailableCluster(JdbcTemplate jdbc) {
        Boolean busy = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM executions " +
                        "WHERE state IN ('CREATED','RUNNING','RECOVERING') AND org_id<>?)",
                Boolean.class, OrgContext.current());
        if (Boolean.TRUE.equals(busy))
            throw new OrgException(HttpStatus.CONFLICT, "EXECUTION_BUSY", "cluster is running another organisation's execution");
    }
}
