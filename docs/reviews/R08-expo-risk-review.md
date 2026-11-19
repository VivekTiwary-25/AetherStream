# R08: Pre-expo risk review of the 19 Nov engine + control-api changes

Scope: commits since 2026-11-19 07:00 IST touching `engine/`, `control-api/`, `simulator/`
(T93 compare lineage, T110 RECOVERED durationMs, T125 simulator tail fence, T140 consumer lag,
T141 WS seq/replay, T151 LATE/REBALANCED events, T152 small worker queues), plus the code they call.
Read-only review: nothing was built, run or changed. Line numbers are at commit `d02e0fc`.

Demo load assumed below: 8 trucks (`SIM_INITIAL_TRUCKS`), one position event per truck every 200 real ms
(time scale 10), so about 40 events/s, up to about 70 events/s during `surge`. One execution left running
for a 6-hour expo, with JVM heaps capped at `-Xmx384m -XX:+ExitOnOutOfMemoryError` (`docker-compose.yml:24`).

## Summary

| # | Rank | Area | Short |
|---|---|---|---|
| 1 | HIGH | compare | Compare loads every record since checkpoint 0 into a 384 MB heap. Hours into the run, control-api OOMs and exits, and nothing restarts it |
| 2 | MEDIUM | WS events | A killed worker that re-registers with the same `WORKER_ID` within 4 s is shown as REBALANCED, with no WORKER_DEAD and no RECOVERED duration |
| 3 | MEDIUM | WS replay | A reconnect replay can exceed the 512 KiB send buffer. The browser is dropped and reconnects for up to ~80 s |
| 4 | MEDIUM | DB polling | The 1 s WS result polls filter on `written_at`, which has no index, so the cost grows with run length |
| 5 | MEDIUM | engine | `Worker` holds its lock through `TaskRuntime.stop()` (up to 2.5 s), which delays the survivor's heartbeat during recovery |
| 6 | LOW | WS events | A worker death with no recovery after it leaves `recoveryPending` set, so a later REBALANCED shows as RECOVERED with a huge `durationMs` |
| 7 | LOW | WS rows | Recovery replay re-upserts rows with a new `written_at`, so they are re-broadcast as "new" rows |
| 8 | LOW | engine | Runtime stage threads catch only `RuntimeException`. An `Error` kills one stage silently while the worker still reports RUNNING |
| 9 | LOW | engine | The lag monitor thread can die once, and lag then stays empty for that assignment |
| 10 | LOW | simulator | The startup tail fence stops at 3 s. On a slow Kafka it gives up, and a restart can rewind time and cause LATE dead letters |
| 11 | INFO | contract | Compose sets `WORKER_QUEUE_CAPACITY=32`, but contract 03 §1 says capacity 1000 |

Checked and found OK: the WS history ring is bounded (500, `LiveHub.java:250`). The `NewRowsTracker` de-dup map is
trimmed to a 1-minute window on every call (`NewRowsTracker.java:51`). `ExecutionEvents` holds only scalars and a
partition-owner map with at most 6 entries. Dead workers are pruned after 60 s (`Coordinator.java:1064-1067`). Pollers
are single-flight, and a scheduler task cannot throw. Sink fencing (`TaskRuntime.java:491`) and stale-generation
filtering (`Worker.java:110-117`) are unchanged and correct. Lag refresh no longer blocks the source thread (T140).

---

## HIGH

### 1. Compare holds the whole run in memory: control-api OOM after a few hours
- **Where:** `control-api/src/main/java/io/aetherstream/api/compare/CompareService.java:103-138` (`replayLineage`),
  `reference/src/main/java/io/aetherstream/reference/KafkaRangeReader.java:64-110` (builds a `List<RawRecord>` per partition).
  Heap cap is `docker-compose.yml:24`. The `control-api` service (`docker-compose.yml:237`) has no `restart:` policy.
- **Failure:** `POST /api/compare` replays `[checkpoint0, latestCompleted)` for the current execution, and since T93
  also the whole savepoint lineage before it. All raw JSON values (about 300-400 B each with object overhead) are
  held in memory together, and the engine's `window_results` rows are loaded on top. At 40-70 events/s that is about
  150-250k records per hour, or roughly 60-100 MB per hour of heap. With a 384 MB heap, compare gets slower the
  whole time (it re-reads everything from Kafka on each press). After roughly 3-4 hours, one press hits
  `OutOfMemoryError`. `ExitOnOutOfMemoryError` then kills control-api, and with no restart policy the UI loses REST
  and `/ws` until someone restarts the container by hand.
- **Likelihood in a 6 h expo:** high if one execution runs all day and someone presses Compare in the afternoon.
  It does not happen if the execution is restarted every hour or two.
- **Smallest safe fix (in order):**
  1. Operational, no code: before each compare demo (or at least every 2 h), start a fresh execution
     (Run pipeline). This resets checkpoint 0. Avoid restoring from a savepoint taken long ago, because that lineage
     is replayed too.
  2. Config: give control-api its own heap, e.g. `JAVA_OPTS: -Xms64m -Xmx1g -XX:+ExitOnOutOfMemoryError` on the
     `control-api` service only, and add `restart: unless-stopped` to it.
  3. Code (after the expo): stream the replay one partition at a time into the reference pipeline instead of
     materialising the lists. Watermarks are per partition (contract 02 §4), so the result is the same.

## MEDIUM

### 2. Fast same-id restart turns a kill into REBALANCED on the timeline
- **Where:** `engine/.../coordinator/Coordinator.java:216-233` (re-register of an alive id that owns partitions:
  `worker.dead` audit and `beginRecovery`), `control-api/.../ws/ExecutionEvents.java:76-83, 157-158`
  (a death is detected only when a worker id leaves the `alive` set).
- **Failure:** compose workers use fixed ids (`WORKER_ID: worker-1..3`, `docker-compose.yml:197-211`). If the killed
  container is restarted (by the soak script or by hand) and re-registers before the 4 s heartbeat timeout, the
  coordinator replaces the entry with `alive=true` and recovers. The 1 s poll never sees the id disappear, so
  `recoveryPending` stays false. Both the RECOVERING and RUNNING transitions are then reported as `REBALANCED`
  (`ExecutionEvents.java:156-164`). The result: no WORKER_DEAD, no "Recovering from checkpoint #N" event, and no
  `durationMs`. Compare still matches, but the story shown to the audience is wrong.
- **Likelihood:** low for a manual demo (restarting takes longer than 4 s). Medium if an automated restart loop
  (T78c soak style) runs against the live stack.
- **Smallest safe fix:** in `ExecutionEvents.observe`, treat a change of `restoredFromCheckpoint` together with
  state `RECOVERING` as a death if none was seen (set `recoveryPending=true`, `workerDeadAt=now`). Or, operationally,
  wait at least 5 s before restarting a killed worker.

### 3. Reconnect replay can overflow the 512 KiB per-session send buffer
- **Where:** `control-api/.../ws/LiveHub.java:112-120` (queues the whole retained history in one go),
  `LiveHub.java:271-273` (closes the peer once `pendingBytes > 512 KiB`).
- **Failure:** the ring holds 500 messages, about 80 s of traffic (trucks 4/s, workers 1/s, flow 1/s, plus events).
  A `trucks` message is about 1-2.5 KB (8-14 trucks), so a full replay is about 0.5-1 MB. A browser that drops for
  roughly 40-80 s (Wi-Fi blip, laptop lid) reconnects with an old `since`. The replay is queued faster than it
  drains, and the session is closed with `SESSION_NOT_RELIABLE`. The client retries with a slightly newer `since`
  and can flap until its cursor falls out of the ring and it gets `resync`. That self-heals in up to about 80 s,
  but the UI looks disconnected meanwhile.
- **Likelihood:** medium during `surge` (more trucks) on flaky expo Wi-Fi. Low on a wired demo PC.
- **Smallest safe fix:** in `initialize`, add up the payload bytes of the messages to replay. If the total is over
  about half of `SEND_BUFFER_LIMIT`, send `resync` and then the snapshot instead of a partial replay
  (contract 04 already allows resync when replay is not possible).

### 4. WS result polls get slower over the day (no `written_at` index)
- **Where:** `control-api/.../ws/IncrementalResultRepository.java:28-68`. The `WHERE execution_id=? AND written_at>=?`
  filter is run 3 times per second. `infra/db/schema.sql:42-80` has only the primary keys, so every poll reads all
  rows of the execution through the PK prefix and then filters them. `counts()` (`:71`) also counts every
  dead-letter row of the execution every second.
- **Failure:** about 280 window rows per real minute (8 trucks × 2 sinks × 10 sim-minutes/min) gives about 100k rows
  after 6 h. That is not fatal, but each poll does a growing scan plus a sort. Together with compare's full
  `window_results` read (#1), Postgres CPU climbs on the demo PC. A poll that runs longer than 1 s is skipped (the
  pollers are single-flight), so the windows feed gets jumpy.
- **Likelihood:** low to medium. It shows up in the last hours if the execution is never restarted.
- **Smallest safe fix:** restart the execution periodically (same as #1). Durable fix, which needs the owner because
  tasks may not edit `schema.sql`: add `CREATE INDEX ... ON window_results (execution_id, written_at)`, and the same
  for `alerts` and `dead_letters`.

### 5. Survivor heartbeat blocked while the old runtime stops
- **Where:** `engine/.../worker/Worker.java:91-101, 135-141` (`assign`/`cancel` call `stopRuntime()` while holding
  the `Worker` monitor). `TaskRuntime.java:157-199` shares a 2.5 s join deadline. `KafkaRecordSource.close()` now
  also joins the lag monitor for up to 1.5 s (T140, `KafkaRecordSource.java:149-163`). `status()` and `metrics()`
  are `synchronized` on the same monitor (`Worker.java:172-193`).
- **Failure:** during recovery each survivor gets CANCEL plus ASSIGN in one heartbeat response. If the sink thread
  is inside a slow JDBC transaction (for example `SELECT ... FOR SHARE` waiting on the generation bump, or a slow DB),
  or the lag consumer is still being built, the heartbeat thread waits up to 2.5 s. Heartbeat interval (1 s) plus
  2.5 s comes close to the 4 s timeout. A survivor declared dead causes a second recovery, which makes recovery
  slower and the timeline confusing. `/metrics` scrapes also stall.
- **Likelihood:** low (normally the joins return in milliseconds), but it rises when slow-sink chaos is active
  while a worker is killed.
- **Smallest safe fix:** cut the lag-monitor join to about 300 ms (it only needs `wakeup` plus interrupt). Longer
  term: swap `runtime` under the lock and run `stop()` outside it.

## LOW

### 6. A stale `recoveryPending` mislabels a later rebalance
- **Where:** `ExecutionEvents.java:80-83, 156-164`.
- **Failure:** a worker dies but no recovery follows (it owned no partitions, or the execution was STOPPED or
  RECOVERING already and the coordinator folded it in). `recoveryPending` stays true until a RECOVERED, STOPPED or
  replacement event. The next generation change in the same execution, for example a rejoin rebalance, is then
  reported as `RECOVERED` with `durationMs` measured from the old death, which can be minutes or hours.
- **Likelihood:** low (with 3 workers and 6 partitions every worker owns partitions).
- **Fix:** clear `recoveryPending` when an observation shows `RUNNING` with an unchanged generation for more than
  about 10 s, or emit RECOVERED only when the previous state was `RECOVERING`.

### 7. Recovery replays re-broadcast old window rows
- **Where:** `engine/.../worker/PostgresResultSink.java:22-37` (the upsert sets `written_at = now()`),
  `NewRowsTracker.java:44` (the de-dup key is the pair of timestamp and primary key).
- **Failure:** after every recovery, rows re-produced from the checkpoint get a new `written_at` and are sent again
  in `windows`/`alerts`/`deadLetters`. That is harmless if the UI upserts by key. If the UI appends, it shows
  duplicates.
- **Fix:** have the UI de-duplicate by primary key, or skip the `written_at` update when values are unchanged
  (`... WHERE window_results.values IS DISTINCT FROM EXCLUDED.values`).

### 8. Runtime stages die silently on `Error`
- **Where:** `TaskRuntime.java:277-283, 375-382, 465-472` catch only `InterruptedException` and `RuntimeException`.
- **Failure:** an `Error` (for example `StackOverflowError` on a malformed snapshot, or OOM without the exit flag)
  ends one stage thread. The other two keep running, so queues fill, `backpressured` stays true, the worker still
  reports RUNNING, and checkpoints stop completing. Recovery is never triggered. (With `ExitOnOutOfMemoryError`,
  an OOM itself exits the JVM.)
- **Fix:** catch `Throwable` in the three loops and call `stopByItself(...)`, so the worker reports IDLE and the
  coordinator reassigns.

### 9. The lag monitor can stop for the rest of an assignment
- **Where:** `KafkaRecordSource.java:102-130`. A failure in the factory or outside the inner try ends the thread,
  and it is started only once per source (`:71`).
- **Failure:** the Grafana lag panel and `WorkerStatus.lag` stay empty until the next ASSIGN. Processing is not
  affected.
- **Fix:** none needed for the expo.

### 10. The simulator tail fence can give up on a slow Kafka
- **Where:** `simulator/.../KafkaEventTime.java:59-124`, with a 3 s overall deadline.
- **Failure:** if the simulator restarts while Kafka is slow, the walk back past chaos-only windows stops early
  (`fence incomplete` warning). The clock may restart behind the previous maximum, and the next events are LATE
  dead letters. This happens at simulator startup only.
- **Fix:** avoid restarting the simulator during the expo. If you must, check `/api/results/summary` for a jump
  in the `late` count.

## INFO

### 11. Contract deviation: worker queue capacity
`docker-compose.yml:198,205,212` set `WORKER_QUEUE_CAPACITY: "32"` (T152), but contract 03 §1 specifies bounded
queues of capacity 1000. The smaller queue is better for the demo: backpressure appears within seconds, and a
barrier waits behind at most 64 items, so checkpoints still complete under `slow-sink 50 ms`. With 1000 they would
take about 100 s and hit the 30 s abort. I recommend updating the contract to "capacity 1000 by default,
configurable" rather than reverting.

## Demo-day checklist from this review
1. Start a fresh execution before each Compare demo, or every 2 h (#1, #4).
2. Give control-api `-Xmx1g` and `restart: unless-stopped` (#1).
3. Wait at least 5 s before restarting a killed worker (#2).
4. Turn slow-sink off before killing a worker (#5, and so checkpoints keep completing).
5. Keep the demo browser on wired or stable network (#3).
