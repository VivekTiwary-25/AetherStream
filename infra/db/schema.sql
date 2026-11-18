-- AetherStream database schema (contract: owned by docs/contracts, do not change in tasks)
-- Loaded by postgres docker-entrypoint-initdb.d and by integration tests.

CREATE TABLE IF NOT EXISTS pipelines (
    id            TEXT        NOT NULL,
    version       INT         NOT NULL,
    name          TEXT        NOT NULL,
    definition    JSONB       NOT NULL,           -- contract 02 pipeline JSON
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, version)
);

CREATE TABLE IF NOT EXISTS executions (
    id                 TEXT        PRIMARY KEY,   -- e.g. "exec-20261119-0001"
    pipeline_id        TEXT        NOT NULL,
    pipeline_version   INT         NOT NULL,
    plan               JSONB       NOT NULL,      -- contract 02 ExecutionPlan
    state              TEXT        NOT NULL,      -- CREATED|RUNNING|RECOVERING|STOPPED|FAILED
    generation         BIGINT      NOT NULL DEFAULT 1,  -- fencing token, +1 on every (re)assignment
    restored_from_checkpoint BIGINT,              -- checkpoint id used by the latest recovery, NULL if none
    idempotency_key    TEXT        UNIQUE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS checkpoints (
    execution_id   TEXT        NOT NULL REFERENCES executions(id),
    checkpoint_id  BIGINT      NOT NULL,          -- 1,2,3... per execution
    generation     BIGINT      NOT NULL,
    state          TEXT        NOT NULL,          -- PENDING|COMPLETED|ABORTED
    manifest_key   TEXT,                          -- S3 key of manifest.json, set only when COMPLETED
    source_offsets JSONB,                         -- {"0": 1234, "1": 99, ...} next offset to read per partition
    started_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at   TIMESTAMPTZ,
    duration_ms    BIGINT,
    PRIMARY KEY (execution_id, checkpoint_id)
);

-- Window results (sink kind "postgres.windows"). Upsert on PK makes replay idempotent.
CREATE TABLE IF NOT EXISTS window_results (
    execution_id   TEXT        NOT NULL,
    sink_id        TEXT        NOT NULL,          -- node id of the sink in the pipeline
    key            TEXT        NOT NULL,          -- e.g. "truck-007"
    window_start   BIGINT      NOT NULL,          -- epoch ms, inclusive
    window_end     BIGINT      NOT NULL,          -- epoch ms, exclusive
    values         JSONB       NOT NULL,          -- {"avgSpeedKmh": 31.2, "count": 30}
    event_count    BIGINT      NOT NULL,
    generation     BIGINT      NOT NULL,          -- generation of the writer
    written_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (execution_id, sink_id, key, window_start)
);

-- Alerts (sink kind "postgres.alerts"). Upsert on PK.
CREATE TABLE IF NOT EXISTS alerts (
    execution_id   TEXT        NOT NULL,
    sink_id        TEXT        NOT NULL,
    alert_id       TEXT        NOT NULL,          -- deterministic: ruleNodeId|key|windowStart
    key            TEXT        NOT NULL,
    rule_id        TEXT        NOT NULL,
    message        TEXT        NOT NULL,          -- "truck-007 has not moved for 10 min"
    event_time     BIGINT      NOT NULL,          -- window_end of the window that fired it
    generation     BIGINT      NOT NULL,
    written_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (execution_id, sink_id, alert_id)
);

-- Rejected (malformed / failed validation) and late events. Upsert on PK (event_id).
CREATE TABLE IF NOT EXISTS dead_letters (
    execution_id   TEXT        NOT NULL,
    event_id       TEXT        NOT NULL,          -- envelope eventId, or "p<partition>-o<offset>" if unparseable
    kind           TEXT        NOT NULL,          -- REJECTED | LATE
    reason         TEXT        NOT NULL,
    partition      INT         NOT NULL,
    "offset"       BIGINT      NOT NULL,
    event_time     BIGINT,
    raw            TEXT,
    generation     BIGINT      NOT NULL,
    written_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (execution_id, event_id, kind)
);

CREATE TABLE IF NOT EXISTS audit_log (
    id          BIGSERIAL   PRIMARY KEY,
    at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor       TEXT        NOT NULL,             -- "ui", "coordinator", "ci"
    action      TEXT        NOT NULL,             -- "pipeline.created", "execution.recovered", ...
    detail      JSONB
);
