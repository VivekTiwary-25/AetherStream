# T118: control-api contract tests for contract 04 (REST + WebSocket)  [tests]

Lane 3, tests only. Add Spring Boot tests (MockMvc / WebTestClient with mocked collaborators, no Docker) that check every
REST endpoint in docs/contracts/04-control-api-rest-ws.md: path, method, status codes, response field names and types, error body
shape, idempotency-key behaviour, X-Org scoping (cross-org denied), and the WebSocket message types (`execution` events including
RECOVERED with durationMs, results, metrics) as far as they can be tested without a real engine. One test class per controller
area, named <Area>ContractTest. Do not change main code; a contract mismatch gets a failing test marked @Disabled("CONTRACT: ...")
and a line in your report.

Files (edit only these): control-api/src/test/java/** (new files only; do not edit existing tests).

Acceptance check: ci-java green on your branch; report tests per endpoint and any contract mismatches.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql (if a contract looks wrong, say so in your report).
- Do not read or change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only edit the files listed. If you are on vivek-pc: do NOT start Docker containers or kind there (the live demo runs on it). Acceptance runs in GitHub Actions on your task branch.
