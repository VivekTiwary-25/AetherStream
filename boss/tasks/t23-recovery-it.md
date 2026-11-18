# T23: Fault tests: kill a worker mid-stream, recover from checkpoint, still match the reference (M2)

Lane 8 (tests). Builds on tests/src/test/java/io/aetherstream/tests/support/EngineCluster.java, WorkloadGenerator.java and
EngineMatchesReferenceIT.java (on main). Read them and the worker/coordinator public APIs first.

Files (create/edit only these):
- tests/src/test/java/io/aetherstream/tests/WorkerCrashRecoveryIT.java
- tests/src/test/java/io/aetherstream/tests/StaleWorkerFencingIT.java
- tests/src/test/java/io/aetherstream/tests/IncompleteCheckpointIT.java
- tests/src/test/java/io/aetherstream/tests/support/EngineCluster.java (add `crash(i)`: stop worker i abruptly - stop its
  heartbeat loop and runtime threads with no flush and no ack, like a halt, without killing the JVM - and `addWorker()`)

Tests (tag "integration"):
1. WorkerCrashRecoveryIT: produce the workload; start coordinator (checkpoint every 2 s) + 3 workers; wait until checkpoint
   >= 2 is COMPLETED; crash the worker owning the most partitions; assert within 15 s the execution goes RECOVERING then
   RUNNING with generation incremented and `restored_from_checkpoint` = the last COMPLETED checkpoint id before the crash;
   audit_log has worker.dead / execution.recovering / execution.recovered. Then wait for full consumption and run the reference
   comparison exactly like EngineMatchesReferenceIT: match must be true (no duplicates, no missing windows). Print recovery
   duration and the CompareResult.
2. StaleWorkerFencingIT: start, then bump `executions.generation` directly in SQL (simulating a reassignment the old worker
   did not see); assert the old worker's next sink transaction writes nothing (row count unchanged) and the worker reports
   IDLE / stops.
3. IncompleteCheckpointIT: crash a worker right after a checkpoint is triggered but before all acks (poll for PENDING); assert
   that checkpoint ends ABORTED, no manifest.json exists for it in MinIO, and recovery uses the previous COMPLETED one.

Acceptance check: `ci-integration` workflow green on your branch with these 3 tests PASSED.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql. If a contract looks wrong, follow it anyway and say so in your result.
- Do not change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only create/edit the files listed under "Files". If you truly need another file, say so in your result instead of editing it.
- Your PC may not have Java, Node or Docker. The acceptance check runs in GitHub Actions on your task branch (CI runs on push to task/**). Write code carefully so it compiles first time; keep it simple and readable.
- Do not commit secrets, build outputs, node_modules or .gradle/ folders.
- In your result, list what you built, how the acceptance check proves it, and anything left undone.
