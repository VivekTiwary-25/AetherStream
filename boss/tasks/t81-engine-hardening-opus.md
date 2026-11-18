# T81: Deep correctness + concurrency hardening of the coordinator and worker runtime

Lane 1 (engine). You are the strongest model on the team; use it for the hardest review. The engine on main
(engine/src/main/java/io/aetherstream/engine/coordinator/**, worker/**, storage/**, db/**) passes all CI tests
(unit + Testcontainers ITs incl. crash recovery 1,646/1,646 match, fencing, incomplete checkpoints, savepoints, rebalance).
It must survive a live demo where a presenter kills workers repeatedly, slows the sink, sends late events, takes savepoints,
rescales, and CD redeploys the whole stack (coordinator restart) while an execution is RUNNING.

Do this:
0. FIRST, a known live bug (ci-e2e step 9, run on branch task/t261119-0258-5f11 / its logic is now in
   infra/scripts/demo-e2e.sh on main behind E2E_EXTENDED=1): after `POST /api/executions/{id}/savepoints` then
   `POST /api/executions/{id}/restore {savepointId}`, the new execution becomes RUNNING and windows arrive, but it never
   reaches 2 COMPLETED checkpoints within 60 s (lastCompletedCheckpoint stays < 2). Find the root cause in the coordinator
   start-from-savepoint path / worker restore / checkpoint numbering, fix it, and add a unit test. Then set
   `E2E_EXTENDED: "1"` in .github/workflows/ci-e2e.yml (you may edit that one line) and make sure ci-e2e passes steps 1-11.
1. Read contract 03 and the code carefully. Look for real bugs: races between heartbeat handling, tick(), acks and HTTP
   threads; lost/duplicated commands; a recovery triggered twice; a checkpoint completing with acks from mixed generations;
   rebalance during a pending checkpoint; worker re-registering with the SAME workerId after a container restart (compose sets
   WORKER_ID=worker-N, so a restarted worker reuses its id - make sure the coordinator treats it as a new incarnation: the old
   one is dead, no stale commands, partitions reassigned/rebalanced); TaskRuntime shutdown leaks (threads, consumers, DB
   connections) after many CANCEL/ASSIGN cycles; unbounded maps (per-checkpoint ack maps, command queues for dead workers);
   coordinator restart with a RUNNING execution whose workers are still alive (they must be fenced and reassigned cleanly).
2. Fix each real bug with the smallest change, and add a focused unit test that fails before the fix. Do not refactor for style.
3. Add a stress unit test: 200 simulated cycles of kill/rejoin/checkpoint with the fake clock -> invariants hold every tick
   (no partition owned by two live workers in the current generation; COMPLETED checkpoints have acks for all partitions
   from one generation; generation strictly increases; command queues bounded).
4. Write docs/reviews/R07-engine-hardening.md: each bug found (file:line, scenario, fix, test name) and things you checked
   that were fine.

Files (edit/create only these): .github/workflows/ci-e2e.yml (only the E2E_EXTENDED line), control-api/src/main/java/io/aetherstream/api/ops/** (only if the restore bug is there), engine/src/main/java/io/aetherstream/engine/**, engine/src/test/java/io/aetherstream/engine/**,
docs/reviews/R07-engine-hardening.md.

Acceptance check: `ci-java`, `ci-integration` and `ci-e2e` (steps 1-11) green on your branch.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql. If a contract looks wrong, say so in your result.
- Do not change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only create/edit the files listed.
- You are running on vivek-pc: do NOT start Docker containers there (the live demo runs on that PC). CI runs the ITs.
