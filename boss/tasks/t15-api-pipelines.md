# T15: control-api pipeline endpoints + starting executions through the coordinator

Lane 3 (control-api). Add the `/api/pipelines/**` endpoints of contract 04 using the compiler already on main
(`io.aetherstream.compiler.PipelineCompiler`: `validate`, `plan`, `demoPipeline()`). The control-api core (health, ready,
version, results, chaos proxies, CoordinatorClient, SimulatorClient, ResultRepository, ApiErrorHandler) is on main: extend
it, keep its style. Spring Boot 3.5.16 (Jackson 2). Use `io.aetherstream.common.Json.MAPPER`-compatible JSON (the Spring
ObjectMapper is Jackson 2 too).

Files (create/edit only these):
- control-api/src/main/java/io/aetherstream/api/PipelineController.java (new)
- control-api/src/main/java/io/aetherstream/api/PipelineRepository.java (new, JdbcTemplate on table `pipelines`)
- control-api/src/main/java/io/aetherstream/api/ExecutionService.java (new)
- control-api/src/main/java/io/aetherstream/api/UpstreamClients.java (only to add a `startExecution(StartExecutionRequest)`
  method to CoordinatorClient, if it is not there)
- control-api/build.gradle.kts: add `implementation(project(":compiler"))` only if it is not already reachable (it is, via
  :reference -> :compiler api; do not edit if it compiles)
- control-api/src/test/java/io/aetherstream/api/PipelineControllerTest.java (new)

Endpoints (contract 04): POST /api/pipelines/validate (always 200 with ValidationResult), POST /api/pipelines (400
INVALID_PIPELINE + `errors` when invalid; same id -> version = max+1; store definition jsonb; audit_log row
`pipeline.created`), GET /api/pipelines, GET /api/pipelines/{id} (404 NOT_FOUND), GET /api/pipelines/demo,
POST /api/pipelines/{id}/executions `{"idempotencyKey"}`:
- if an execution with that idempotency_key exists, return it;
- else stop the current RUNNING/RECOVERING execution via the coordinator (POST /coord/executions/{id}/stop), plan the latest
  pipeline version with 6 partitions, create the id `exec-<yyyyMMdd-HHmmss>-<4 random hex>`, and call coordinator
  POST /coord/executions {executionId, plan}. The COORDINATOR inserts the executions row (it owns that table); after the call,
  set `idempotency_key` on that row (UPDATE). Return the coordinator's ExecutionInfo. Audit row `execution.started`.

Tests: validate returns errors for an invalid pipeline (no sink) and valid+plan for the demo; create twice gives versions 1
and 2 (mock the repository or use an in-memory fake); start execution calls the coordinator client with a plan whose
operators match the demo and reuses the execution for the same idempotency key.

Acceptance check: `ci-java` workflow green on your branch.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql. If a contract looks wrong, follow it anyway and say so in your result.
- Do not change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only create/edit the files listed under "Files". If you truly need another file, say so in your result instead of editing it.
- Your PC may not have Java, Node or Docker. The acceptance check runs in GitHub Actions on your task branch (CI runs on push to task/**). Write code carefully so it compiles first time; keep it simple and readable.
- Do not commit secrets, build outputs, node_modules or .gradle/ folders.
- In your result, list what you built, how the acceptance check proves it, and anything left undone.
