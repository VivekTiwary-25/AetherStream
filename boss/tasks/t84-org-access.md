# T84: Organisation-scoped access for pipelines and executions (cross-organisation access is denied)

Lane 3 (control-api). handoff.md section 10 acceptance criterion: "Cross-organisation access - authorisation denies access
to another organisation's resources." Implement a small, honest version (no real login): every API request carries the
caller's organisation in header `X-Org` (letters, digits, '-', max 40 chars; missing header = `demo`; invalid = 400
INVALID_ORG). The UI and scripts send nothing, so they stay in `demo`.

Schema (the DB of the live demo already exists, so do it as an idempotent migration run by control-api at startup, e.g. a
small `@Component` with `ApplicationRunner` using JdbcTemplate):
`ALTER TABLE pipelines ADD COLUMN IF NOT EXISTS org_id TEXT NOT NULL DEFAULT 'demo'` and the same for `executions`;
`CREATE INDEX IF NOT EXISTS ...` on (org_id). Do NOT edit infra/db/schema.sql (the boss updates the contract file).

Rules: pipelines are owned by the org that created them; `GET /api/pipelines` lists only the caller's org;
`GET /api/pipelines/{id}`, `POST /api/pipelines/{id}/executions`, `GET /api/executions/{id}`, `/stop`, `/checkpoints`,
`/savepoints`, `/restore`, `/rescale` on another org's resource -> 404 NOT_FOUND (do not reveal existence); `POST
/api/pipelines` with an id that exists in another org -> 409 PIPELINE_ID_TAKEN. When the API starts an execution it sets
`executions.org_id` (the coordinator inserts the row; update it right after, like idempotency_key). Audit rows carry the org in
detail. Results/compare/chaos endpoints act on the current execution of the caller's org only.

Files (edit/create only these):
- control-api/src/main/java/io/aetherstream/api/** (add an `org/` package for the header parsing, migration and checks;
  edit PipelineController, PipelineRepository, ExecutionService, ControlApiController, ResultRepository, ops/**, compare/**
  only where needed)
- control-api/src/test/java/io/aetherstream/api/**
- tests/src/test/java/io/aetherstream/tests/OrgAccessIT.java (Testcontainers Postgres: create a pipeline as org `a`, try to
  read/start/stop it as org `b` -> 404s; `demo` default works; migration idempotent when run twice)

Acceptance check: `ci-java` and `ci-integration` green; `ci-e2e` still green (UI/scripts use the default org).

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly (contract 04 paths and shapes stay the same; X-Org is an
  additive header). Do not edit docs/contracts/ or infra/db/schema.sql.
- Do not change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only edit the files listed. If you are on vivek-pc: do NOT start Docker containers there.
