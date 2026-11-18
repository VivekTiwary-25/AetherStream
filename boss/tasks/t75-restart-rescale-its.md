# T75: Integration tests: coordinator restart, rescale correctness, incompatible savepoint

Lane 8 (tests). handoff.md section 10 still lacks tests for: "Coordinator restart - single-coordinator baseline restores
metadata and reconciles execution", "Rescale - no unsupported concurrent partition ownership; results remain correct",
"Incompatible savepoint - restore is rejected with a useful explanation". Build them on the harness on main:
tests/src/test/java/io/aetherstream/tests/support/** (IntegrationInfrastructure, EngineCluster - now with crash support,
WorkloadGenerator) and the reference comparison used in WorkerCrashRecoveryIT / EngineMatchesReferenceIT.

Files (create/edit only these):
- tests/src/test/java/io/aetherstream/tests/CoordinatorRestartIT.java
- tests/src/test/java/io/aetherstream/tests/RescaleIT.java
- tests/src/test/java/io/aetherstream/tests/SavepointIT.java
- tests/src/test/java/io/aetherstream/tests/support/EngineCluster.java (only to add `restartCoordinator()` and
  `addWorker()` if missing)

Tests (`@Tag("integration")`):
1. CoordinatorRestartIT: run the workload with 3 workers; after checkpoint >= 2 stop the coordinator abruptly and start a new
   one on the same DB/S3; workers re-register; execution ends RUNNING (recovered from the latest COMPLETED checkpoint,
   generation increased); full consumption; reference compare matches.
2. RescaleIT: start with 2 workers, add a third, trigger rebalance (or wait for auto rebalance); assert during and after:
   never the same partition assigned to two alive workers in the same generation (poll /coord/state frequently); after
   rebalance 2/2/2; final reference compare matches.
3. SavepointIT: take a savepoint; start a new execution from it with the same plan -> succeeds and compare matches; start
   from it with a modified plan (window sizeMs changed) -> 409 INCOMPATIBLE_SAVEPOINT whose message names the window.

Acceptance check: `ci-integration` green on your branch with the 3 new tests PASSED.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql.
- Do not change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only create/edit the files listed. If a test reveals a REAL engine bug, keep the failing assertion and describe the bug
  precisely in your result instead of weakening the test.
- If you are running on vivek-pc: do NOT start Docker containers there.
