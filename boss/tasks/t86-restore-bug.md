# T86: Fix restore-from-savepoint: restored execution never completes 2 checkpoints

Lane 1 (engine) + control-api ops. Live bug found by ci-e2e step 9 (infra/scripts/demo-e2e.sh on main; steps 9-11 run
only when E2E_EXTENDED=1, CI currently sets "0" in .github/workflows/ci-e2e.yml): after
`POST /api/executions/{id}/savepoints` then `POST /api/executions/{id}/restore {"savepointId", "idempotencyKey"}`, the new
execution becomes RUNNING and windows arrive, but it never reaches 2 COMPLETED checkpoints within 60 s
(`lastCompletedCheckpoint` stays < 2). Read contract 03, the coordinator start-from-savepoint path (Coordinator.java,
CoordinatorHttpServer.java), worker restore (worker/TaskRuntime, Worker), and control-api ops/OpsController.

Do this: reproduce it in a coordinator unit test with the fake clock / in-memory deps (start from a savepoint, run ticks +
acks, expect checkpoints 1 and 2 COMPLETED), find the root cause (e.g. checkpoint numbering continuing from the savepoint's
checkpoint id, acks rejected for a generation/execution mismatch, missing checkpoint 0 for the new execution, workers
ignoring TRIGGER for the new execution), fix it with the smallest change, then set `E2E_EXTENDED: "1"` in
.github/workflows/ci-e2e.yml and make sure ci-e2e passes steps 1-11.

Files (edit only these): engine/src/main/java/io/aetherstream/engine/**, engine/src/test/java/io/aetherstream/engine/**,
control-api/src/main/java/io/aetherstream/api/ops/**, .github/workflows/ci-e2e.yml (only the E2E_EXTENDED line).

Acceptance check: `ci-java` and `ci-e2e` (all 11 steps PASS) green on your branch.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql.
- Do not change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only edit the files listed. You are on vivek-pc: do NOT start Docker containers there.
