# AetherStream: live demo script (10-12 minutes)

This is the exact script for the external examiner at the CDD expo (21 November 2026).
Each step says **Do** (what to click or say), **They see** (what the audience sees) and **Proves** (the syllabus item).
Times are targets; the total is about 11 minutes plus Q&A.

> Parts that are not on `main` yet are marked **(in progress)**. Practise the full run on the demo machine the
> evening before, once everything is merged.

Remember the time scale: **1 simulated minute = 6 real seconds** (`timeScale = 10`). A 1-minute window closes about
every 6 seconds, and the stuck-truck rule (10 quiet windows) fires about 1 real minute after truck-007 stops.

---

## Step 0. Pre-flight checklist (before the examiner arrives, ~10 min earlier)

- [ ] `docker compose up -d --build` has run and `docker compose ps` shows every service **healthy**
      (kafka, postgres, minio, coordinator, worker-1, worker-2, worker-3, simulator, control-api, web).
      `kafka-init` and `minio-init` show **exited (0)**, which is correct.
- [ ] http://localhost:8088 opens, the footer shows a commit SHA (not `dev`), the status pill says **Running**.
- [ ] If the demo pipeline is already running from practice, stop it and start a fresh one, so the numbers are small
      and Compare is fast.
- [ ] Browser tabs, in this order: (1) the UI, (2) GitHub repo **Actions** tab, (3) the public link of the
      self-hosted deployment **(in progress)**, (4) the MinIO console http://localhost:19001 (login `aether` / `aether-secret`).
- [ ] A terminal open in the repo folder, font large enough to read from the back.
- [ ] The self-hosted runner shows **Idle** in GitHub (Settings -> Actions -> Runners, label `aether-deploy`) **(in progress)**.
- [ ] A tiny prepared change for step 7 (for example a word in the footer text), not yet committed.
- [ ] Laptop on power, notifications off, screen sleep off.

---

## Step 1. The city (1 min)

**Do / say:** "This is AetherStream, a mini Apache Flink. The trucks are the data source. Each truck sends a position
event every 2 simulated seconds and a delivery event when it finishes unloading. Those events go into Kafka, 6
partitions, keyed by truck id. The glowing stream is the event flow going into the engine." Point at the events/sec
number on the stream.

**They see:** 8 trucks driving on a grid between the depot and delivery stops; a glowing stream into the engine block;
events/sec per partition.

**Proves:** a realistic, continuous data source; Kafka partitioning by key (distributed systems basics).

## Step 2. Pipeline editor: break it, read the error, fix it, run (2 min)

**Do / say:**
1. Open the pipeline editor, click **Load demo**. "Source, filter, key-by, 1-minute window, a stuck-truck rule, and
   sinks to Postgres."
2. Select the **Key-by** node `kb1` (between "positions" and the speed window) and delete it.
3. Click **Validate**.
4. Read the errors out loud. If you reconnect `pos` straight to `speed1m` you get *"Window 'speed1m' needs a Key-by
   before it"* (TYPE_MISMATCH); if you leave the gap you get the dangling-node / unreachable-node errors instead.
   "The compiler collects every error and says how to fix it, in plain English, on the right node."
5. Undo / re-add the Key-by between `pos` and `speed1m`, click **Validate** again -> **Valid**.
6. Click **Run**.

**They see:** red error text attached to the broken node; then a green "Valid"; the status pill changes to **Running**.

**Proves:** a compiler from a visual graph to an execution plan with validation (design for usability, clear
feedback); the UI and API share one contract (`POST /api/pipelines/validate`).

## Step 3. Live panels (1.5 min)

**Do / say:** "Results are computed per truck per minute of **event time**, not wall-clock time." Point at:
- **Speed per truck per minute** (avg/max km/h) - new rows every ~6 s.
- **Deliveries per minute** and parcels.
- Wait for the **stuck truck** alert: "truck-007 stops after 5 simulated minutes. The rule fires when its average
  speed stays under 1 km/h for 10 windows in a row." After about 1 real minute: *"truck-007 has not moved for 10 min"*.
  Point at truck-007 standing still in the city.

**They see:** tables/charts filling up; an alert card for truck-007.

**Proves:** stateful stream processing: keyed tumbling windows, aggregations, rules over consecutive windows.

## Step 4. Chaos: late events and backpressure (1.5 min)

**Do / say:**
1. Click **Chaos -> Late events** (20 events, 3 simulated minutes late). "These events belong to windows that are
   already closed. Each partition has its own watermark, so the engine knows they are late."
   Open the **Dead letters** panel: rows with kind **LATE** and a reason like
   `window [start,end) already closed (watermark W)`.
2. Optional: **Duplicates** -> "same eventId counts once per window, so numbers do not change."
3. Click **Chaos -> Slow output** (sink delay 50 ms). "Now the database writer is slow. Each worker has bounded
   queues of 1000 items. When a queue is full, the stage before it simply waits. That is backpressure."
   Point at the worker cards going **amber / Backpressured**, queue bars near 1000/1000, lag growing.
4. Set slow output back to **0 / off**. Queues drain and the cards go back to green **Running**.

**They see:** LATE dead letters appear; amber backpressure with full but **bounded** queues; then back to normal.

**Proves:** event-time correctness (watermarks, late data, dedup) and flow control without running out of memory.

## Step 5. Kill a worker (1.5 min)

**Do / say:** "Now a real crash." Click **Chaos -> Kill worker**. "The coordinator picks the worker with the most
partitions and it halts immediately, no clean shutdown."

**They see:** that worker card goes **red / Dead** within about 4 seconds; the banner says
**"Recovering from checkpoint #N"**; trucks **keep driving** (the simulator and Kafka are not affected); after a few
seconds the partitions are spread over the 2 live workers, the status goes back to **Running** and the panels continue.

**Say:** "Every 10 seconds we take an aligned barrier checkpoint: state and Kafka offsets are saved together to MinIO.
On failure the coordinator bumps the **generation**, restores everything from the last completed checkpoint, and
replays Kafka from the saved offsets. The old worker's generation is now stale, so it can never write again."
Optional: show the checkpoint files in the MinIO console under `aether-checkpoints/executions/<id>/checkpoints/`.

**Proves:** fault tolerance: failure detection, checkpoint/restore, fencing (reliability, DevOps operations).

## Step 6. Compare with reference (1 min)

**Do / say:** Click **Compare with reference**.

**They see:** **"N / N results match"** (for example 4812 / 4812), records replayed, time taken, 0 mismatches.

**Say why it matches even after the crash:**
1. **Same operators:** the engine and the reference run the exact same operator code (`compiler/.../ops`).
2. **Replay from saved offsets:** after recovery the engine re-reads Kafka from the offsets stored together with its
   state, so no event is lost and state is never counted twice.
3. **Fenced, idempotent sink:** results are upserted by key in one transaction that first checks the generation, so a
   replay overwrites with identical rows and a stale worker cannot write at all.

**Proves:** correctness testing against an independent oracle; the declared output guarantee
(effectively exactly-once results in Postgres).

## Step 7. DevOps: CI/CD (2 min)

**Do / say:**
1. Switch to the **Actions** tab. Show the workflows and one green run of each, opening one log:
   | Workflow | Proves |
   |---|---|
   | `ci-java` | all Java modules compile, unit tests pass |
   | `ci-web` | UI lint, tests and build pass |
   | `ci-integration` | integration + recovery tests with real Kafka/Postgres/MinIO **(in progress)** |
   | `ci-images` | every Dockerfile builds and carries the commit revision label |
   | `ci-compose` | the whole stack starts healthy and smoke checks pass |
   | `ci-e2e` | the demo flow works through the browser **(in progress)** |
   | `cd` | images pushed to GHCR, deployed on the self-hosted runner **(in progress)** |
2. Make the tiny prepared change, then:
   ```bash
   git commit -am "demo: tiny change"
   git push origin main
   ```
3. "CI runs on every push. After CI is green, `cd` publishes the images tagged with this commit SHA and the
   self-hosted runner redeploys." Show the run starting. While it runs, go on to step 8; come back at the end.
4. When `cd` finishes, refresh the UI: the **footer commit** changes to the new short SHA.

**They see:** green pipelines and real logs; the deployed commit changing on screen.

**Proves:** continuous integration, continuous delivery/deployment, automation, traceability from commit to running system.

## Step 8. Docker (1 min)

```bash
docker compose ps
docker images | grep aether
docker inspect --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' <engine-image>
docker compose logs --tail 20 coordinator
```

**Say:** "Every service is a container from our own multi-stage Dockerfiles in `infra/docker/`. Each image is
labelled with the git commit it was built from, the same SHA you see in the footer. Here are the coordinator logs:
worker dead, recovering, recovered with checkpoint and generation."

**They see:** healthy containers, our images, the revision label, readable logs.

**Proves:** containerisation, image provenance, observability through logs and health checks.

## Step 9. Tests (0.5 min)

**Do / say:** Open the latest `ci-integration` run **(in progress)** and the `ci-java` test report artifact.
"Unit tests check the compiler errors, the window/late/dedup/rule semantics and that snapshot -> restore -> continue
gives exactly the same output. Integration tests start real Kafka, Postgres and MinIO with Testcontainers, kill a
worker in the middle, and assert that the compare result still matches."

Locally: `./gradlew build`, `./gradlew :tests:integrationTest`, `cd web && npm ci && npm test`.

**Proves:** automated testing at unit and integration level, including fault injection.

---

## Step 10. Q&A cheat sheet

| Likely question | Short answer |
|---|---|
| Is it exactly-once? | Input is replayed at-least-once after a failure. Operator state is exactly-once because state and offsets are restored together from one checkpoint. The Postgres results are effectively exactly-once because outputs are deterministic and upserted by key. We do not claim it for any other sink. |
| What if the coordinator dies? | It is a single coordinator (declared baseline). Its state is in Postgres. On restart it reads RUNNING/RECOVERING executions, waits one heartbeat timeout for workers to re-register, then runs normal recovery from the last completed checkpoint. A highly available coordinator is future work. |
| Why per-partition watermarks? | Kafka only keeps order inside a partition. One watermark per partition means results do not depend on how partitions interleave or which worker owns them, so the engine and the reference always agree. Idle partitions do not advance (declared policy). |
| How is backpressure bounded? | Fixed queues of 1000 between source, operators and sink. A full queue blocks the stage before it, so memory is bounded and Kafka just waits (lag grows) instead of the worker crashing. |
| What is generation fencing? | Every reassignment increases a generation number in Postgres. Each sink transaction first locks and checks it, and aborts if it is stale. A "zombie" worker that comes back cannot write old results. |
| How does the checkpoint stay consistent without stopping? | Aligned barriers (Chandy-Lamport style): the source records offsets and puts a barrier in the queue; the operator snapshots state when the barrier arrives; the sink commits everything before it, uploads to MinIO and acks. The coordinator writes a manifest only when all partitions acked; only then is it COMPLETED. |
| What about a half-written checkpoint? | A checkpoint not completed in 30 s or interrupted by a failure is ABORTED. Restore only uses the latest COMPLETED one. |
| How do you know the results are right? | The reference executor replays the same Kafka records in one process with the same operator code and compares every row (Compare with reference). The integration tests do the same after killing a worker. |
| How are duplicates and bad events handled? | Same eventId counts once per key-window. Invalid JSON or broken fields become REJECTED dead letters with a reason; late events become LATE dead letters. Nothing is silently lost. |
| How does a commit reach production? | Push -> CI workflows (Java, web, images, compose, integration, e2e) -> `cd` builds images tagged with the SHA, pushes to GHCR, and the self-hosted runner pulls and restarts them. The footer and the image revision label show the SHA. |
| Why not just use Flink? | The goal was to understand and show the core ideas (event time, state, checkpoints, recovery, backpressure) end to end, with a test oracle to prove correctness. |

---

## If something goes wrong during the demo

| Problem | Fix |
|---|---|
| A service is unhealthy or stuck | `docker compose ps` to find it, then `docker compose restart <service>` (or `docker compose up -d <service>`). Check `docker compose logs --tail 50 <service>`. |
| The UI shows no data | Check the status pill; reload the page (the WebSocket reconnects by itself). If no execution is running, click **Run** again. |
| A killed worker does not come back | That is expected: the remaining 2 workers take over. To have 3 again: `docker compose up -d worker-1` (use the name that died). It registers with a new id. |
| Compare shows mismatches or is slow | Wait for the next completed checkpoint (10 s) and press Compare again. If it still fails, stop and start a fresh run, wait one minute, compare again, and explain from the integration test log that recovery is checked in CI. |
| Stuck-truck alert has not appeared | It needs about 1 real minute after truck-007 stops (5 simulated minutes after start). Talk through backpressure meanwhile. |
| CD takes too long or the runner is offline | Show the last successful `cd` run and its logs instead, and the footer SHA matching that run. |
| Whole stack is broken | `docker compose down` then `docker compose up -d --build` (about 2 minutes). Meanwhile walk through the green CI runs and logs in the Actions tab as evidence. |
