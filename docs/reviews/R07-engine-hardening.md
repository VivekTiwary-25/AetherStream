# R07: Engine hardening (coordinator + worker runtime)

Task T81. Scope: `engine/src/main/java/io/aetherstream/engine/{coordinator,worker,storage,db}`, read against contract 03.
Goal: get through a live demo with repeated worker kills, a slow sink, late events, savepoints, rescaling and a CD
redeploy (coordinator restart) while an execution is RUNNING.

Line numbers refer to `Coordinator.java` after this change. Every fix has a unit test that fails on the old code.
I checked this by putting back the original `Coordinator.java` (plus the test-only `maxQueuedCommands()` probe):
8 of 9 new coordinator tests failed, and the 9th is the same-id regression check under "Checked and fine".

## Bugs found and fixed

### 1. A lost ASSIGN leaves the execution stuck in RECOVERING (or with idle partitions)
- **Where:** `heartbeat()` drains the command queue into the response (`Coordinator.java:268` →
  `maybeResendAssign`, `:1100`).
- **Scenario:** the coordinator is slow for more than 5 s (e.g. a `bumpGeneration` waiting on the row lock held by a sink
  batch, or a Postgres hiccup during CD). The worker's HTTP client times out, but the coordinator still handles the
  request and empties the queue. The CANCEL + ASSIGN for the new generation are gone. Nothing ever re-sent an ASSIGN, so
  `allAssignedRunning` stayed false: RECOVERING forever, and no checkpoints. The same happens when a member's runtime
  stops by itself (a Kafka source failure or a restore error makes the worker IDLE in the *current* generation).
- **Fix:** the coordinator remembers the ASSIGN it sent to each member in the current generation. Workers handle a
  response before sending the next heartbeat. So if a member still reports an older generation, another execution, or
  no execution, it never got its ASSIGN, and the coordinator queues the same ASSIGN again. ASSIGN is idempotent per
  generation (contract 03 §2). Resends happen at most once per 2 heartbeat intervals. If a checkpoint is pending in this
  generation, its TRIGGER_CHECKPOINT is re-queued too.
  A member that is IDLE in the current generation is restarted only after it has stayed IDLE for one heartbeat timeout.
  A worker that sends its final IDLE report while shutting down is declared dead before then. This also keeps
  `StaleWorkerFencingIT` able to see its fenced worker go IDLE.
- **Tests:** `CoordinatorHardeningTest.lostAssignResponseIsResentOnNextHeartbeat`,
  `CoordinatorHardeningTest.memberWhoseRuntimeFailedGetsAssignAndPendingTriggerAgain`, and the convergence checks in
  `CoordinatorStressTest` (on the old code it got stuck in RECOVERING after cycle 80).

### 2. A worker that rejoins while the execution is RECOVERING stays idle forever
- **Where:** `register()` only marked a new worker as a rebalance candidate when the state was `RUNNING`. Heartbeat
  re-admission never marked it (`:226`, `:256` → `markRebalanceCandidate`, `:1085`).
- **Scenario:** the presenter kills worker-1. After 4 s it is declared dead and its partitions move to worker-2 and
  worker-3 (RECOVERING for about 1 s while they restore). Compose restarts worker-1 with the same `WORKER_ID`, and it
  registers inside that window. It was never a candidate, so auto-rebalance never gave it any partitions. The same
  happened to a worker re-admitted by heartbeat after a network blip.
- **Fix:** a worker that joins while the execution is active (RUNNING or RECOVERING), already has an assignment, and is
  not part of it becomes a candidate. The existing 5 s stability rule and 30 s rate limit still apply.
- **Test:** `CoordinatorHardeningTest.workerRejoiningWhileRecoveringBecomesRebalanceCandidate`.

### 3. A Postgres hiccup in the middle of failure handling makes recovery stop for good
- **Where:** `detectFailures()` / `beginRecovery()` (`:709`, `:725`) and the audit calls (`audit()`, `:1193`).
- **Scenario:** `detectFailures` sets `alive=false` and then writes the `worker.dead` audit row. If that insert (or the
  `updateState(RECOVERING)` in `beginRecovery`) throws, the tick aborts. The assignment then still contains the dead
  worker, and later ticks never look at it again (it is already dead, and `maybeAssign` skips a non-empty assignment).
  The execution waits for a dead worker forever.
- **Fix:** (a) audit rows are now best effort: failures are logged and never abort control flow. (b) Self-heal: if an
  active execution's assignment names a worker that is dead or unknown, every tick starts recovery (the retry path
  repeats the state update and the abort of pending checkpoints).
- **Tests:** `CoordinatorHardeningTest.auditFailureWhileDeclaringWorkerDeadStillRecovers`,
  `CoordinatorHardeningTest.failedStateUpdateInBeginRecoveryIsRetriedByTheNextTick`.

### 4. The in-memory generation could fall behind the DB fencing token
- **Where:** `recoverAssign()` (`:802`).
- **Scenario:** `bumpGeneration` succeeds, then `setRestoredFrom` throws. `e.generation` was only updated after that
  second call, so memory said G while Postgres said G+1. Until the next successful retry, the coordinator would accept
  acks for G, and any ASSIGN it built would be fenced by every sink transaction.
- **Fix:** update `e.generation` right after the bump, before any other repository call.
- **Test:** `CoordinatorHardeningTest.inMemoryGenerationFollowsDatabaseEvenIfALaterRepoCallFails`. The stress test also
  checks "in-memory generation == DB generation" after every tick.

### 5. A rebalance (or checkpoint) could start during the coordinator-restart grace window
- **Where:** `allAssignedRunning()` (`:869`) returned true for an empty assignment.
- **Scenario:** after a CD redeploy the loaded execution is RUNNING with an empty assignment for 4 s. In that window,
  `POST /rebalance` (or the auto-rebalance path) passed every check, inserted a PENDING checkpoint with no participants,
  and left `rebalanceRequested` set. That caused an extra generation bump after recovery.
- **Fix:** an empty assignment is never "all assigned running".
- **Test:** `CoordinatorHardeningTest.rebalanceRequestDuringRestartGraceWindowIsRejected`.

## Stress test
`CoordinatorStressTest.invariantsHoldThrough200KillRejoinCheckpointCycles` runs the real Coordinator with a fake clock,
seeded so it is deterministic. Four simulated workers use compose-style fixed ids (`worker-1..4`) and the same command
rules as `Worker`. Over 200 cycles it applies random chaos: silent crashes, chaos KILL, restarts with the same id, forced
checkpoints, rebalance requests, coordinator restarts (`loadActiveExecutions` against the same repository), and 10% lost
heartbeat responses. It checks these after **every** 250 ms tick:
- no partition is run by two live workers in the current generation (worker side), or assigned twice (coordinator side);
- every COMPLETED checkpoint has a manifest covering all 6 partitions, with every ack from the checkpoint's single
  generation (the generation is encoded in each ack's object key and compared with the record and the manifest);
- the generation never decreases, ASSIGN generations never go back, and one generation has exactly one assignment;
- the in-memory generation equals the DB generation;
- command queues stay bounded (≤ 8, checked with the package-private `maxQueuedCommands()` probe and on every response).

Every 10 cycles, and once more at the end after restarting every worker, it checks liveness: with no more chaos the
execution must reach RUNNING and complete a new checkpoint. I also ran it locally with 5 other seeds (1, 2, 3, 42, 777);
all passed.

## Checked and fine (no change)
- **Same-id restart = new incarnation:** `register()` replaces the `WorkerEntry`, so the old queue is dropped (no stale
  KILL or TRIGGER). If the old incarnation was alive and owned partitions, recovery starts with a new generation, and
  in-flight acks from the old incarnation are rejected as stale. Regression test:
  `sameIdReRegistrationIsANewIncarnationWithoutStaleCommands` (it passed before the change too).
- **Acks from mixed generations:** acks must match the current generation, and every generation bump (`recoverAssign`,
  `beginRecovery`) clears the pending checkpoint first. `performRebalance` only runs after the pending one completes.
  So a pending checkpoint can never collect acks from two generations. The stress test checks this.
- **Recovery triggered twice:** several deaths in one tick start one recovery. A second death during recovery correctly
  starts a new one (a new generation).
- **Rebalance during a pending checkpoint:** it reuses the pending checkpoint and rebalances when that completes. A
  failure in between clears the request (`beginRecovery`). A timeout keeps it for the next checkpoint.
- **Coordinator restart with live workers:** they are re-admitted by heartbeat. During the grace window they get CANCEL
  for their old generation (they are not members), and then `recoverAssign` bumps the generation, so old sink batches
  are fenced.
- **Unbounded maps:** there is only one pending checkpoint, and its ack map is bounded by the partition count. A dead
  worker's queue is cleared when it is declared dead, and its entry is pruned after 60 s. Live queues are drained every
  heartbeat; CANCEL and SET_SINK_DELAY are de-duplicated, and resent ASSIGNs are only added if not already queued.
- **TaskRuntime shutdown:** the Kafka consumer is closed on the source thread in `finally`, including when `stop()`
  happens before `assign`. Threads are joined with a shared 2.5 s deadline. The sink connection is try-with-resources.
  New test `WorkerCycleLeakTest.assignCancelCyclesReleaseEveryThreadAndSource` runs 150 ASSIGN/CANCEL/TRIGGER cycles
  and checks that every source is closed and no runtime thread is left.
- **Savepoints:** `createSavepoint` waits with `wait()`, which releases the monitor so heartbeats and acks can still be
  handled. It fails cleanly if the checkpoint is aborted by a recovery.
- **Fencing:** `UPDATE ... generation + 1` versus the sink's `FOR SHARE` works as contract 03 §5 says.

## Notes and possible contract gaps
- Contract 03 §6 says "a restarted worker registers with a new workerId", but compose sets a fixed `WORKER_ID`. The
  coordinator handles both, as described above. The contract could say so.
- During the 4 s restart grace window, `GET /coord/state` still shows the loaded state (usually RUNNING) with no
  assignment. I left that as it is (cosmetic); recovery switches to RECOVERING when it runs.
- I could not run the Testcontainers ITs locally: Docker must not be started on this PC. CI `ci-integration` decides. I
  read `StaleWorkerFencingIT`, `IncompleteCheckpointIT` and `WorkerCrashRecoveryIT` against the new resend rule, and the
  4 s IDLE grace keeps their expectations valid.
