# T78c: Chaos soak - restart killed workers + verify they rejoin; fix a flaky engine test  [tests/infra]

Lanes 7/8. Diagnosis from T105 (GitHub run 44440052641, compose-logs.txt): infra/scripts/soak.sh calls `docker compose kill
<worker>` and never starts it again. Docker treats kill as a manual stop, so `restart: unless-stopped` does NOT restart it. The
first two "PASS ... recovered" lines only measured the execution failing over to the remaining workers; after the 3rd kill no
worker was left. The soak files are on origin/task/t261119-0718-f365 (rejected); start from current main and bring them over
(git checkout origin/task/t261119-0718-f365 -- infra/scripts/soak.sh .github/workflows/ci-soak.yml).

1. soak.sh: after each kill, wait until the execution is RUNNING in a newer generation (failover), then `docker compose start
   <worker>` and wait until that worker re-registers and owns partitions again (rejoin, contract 03 / coordinator status API).
   Report both times per kill in the summary (failover s, rejoin s). Keep the 30 s deadline for failover; allow 60 s for rejoin.
   Keep reference comparisons, slow-sink pulses and late events as they are.
2. ci-soak.yml triggers: workflow_dispatch, nightly schedule, push to task/** only when soak.sh or ci-soak.yml change. Never main.
3. Flaky test: engine TaskRuntimeTest.fencedSinkStopsRuntimeAndWritesNothingMore failed once at line 186 (processedRecords
   expected 1135, got 1136): a record in flight is counted after isStopped(). Decide: if the runtime counts a record it then
   drops, fix the counter in TaskRuntime (count only processed-and-forwarded records); otherwise make the test wait until the
   processing thread has exited before taking the snapshot. Never weaken "no rows written after fencing".

Files (edit only these): infra/scripts/soak.sh, .github/workflows/ci-soak.yml,
engine/src/test/java/io/aetherstream/engine/worker/TaskRuntimeTest.java, engine/src/main/java/io/aetherstream/engine/worker/TaskRuntime.java (only if step 3 needs it).

Acceptance check: ci-soak green on your branch (paste the summary table with failover + rejoin times) and ci-java green.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql (if a contract looks wrong, say so in your report).
- Do not read or change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only edit the files listed. If you are on vivek-pc: do NOT start Docker containers or kind there (the live demo runs on it). Acceptance runs in GitHub Actions on your task branch.
