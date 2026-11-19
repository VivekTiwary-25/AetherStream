# AetherStream examiner demo (12 minutes)

Presenter script. Click only labels that are on the page. Read numbers off the screen. Do not push to `main` during the slot: `cd` deploys onto the machine you are demoing.

Checked against this checkout: `web/src/app/shell/AppShell.tsx` mounts the city, **Pipeline** editor, live panels, **Chaos controls**, **Reference comparison**, **Checkpoint & recovery timeline**, **Operations**, and **City scenarios**. The **Tour** button opens the eight-step tour, and `IntroSplash` is mounted. Use the visible labels named below; collapse/expand controls use **Collapse <panel title>** and **Expand <panel title>**.

A production page must not show **MOCK DATA**. That badge is only rendered when `VITE_USE_MOCK=1`.

## 30 minutes before

1. Freeze first: `bash infra/scripts/demo-freeze.sh on`, then `bash infra/scripts/demo-freeze.sh status`. Without `gh`, set repository variable `DEMO_FREEZE=1` under **Settings → Secrets and variables → Actions → Variables**. A committed `infra/deploy/FREEZE` also freezes CD. Images still build/push; deploy and live checks skip with a green job and a summary notice. Wait for any already-running deploy to finish before the demo. After the slot, run `bash infra/scripts/demo-freeze.sh off` and remove the committed file if present; only future CD runs resume.
2. CD and Expo autostart enable `monitoring` (also offline): Prometheus **512m**, Grafana **256m** memory caps. If this is not already the live demo stack, start locally with `docker compose -p aether --profile monitoring up -d --build`; never build on vivek-pc. `docker compose -p aether --profile monitoring ps`: application services should be healthy and Prometheus/Grafana running. `kafka-init` and `minio-init` should be exited. Do not run `docker compose down -v`.
3. Open `http://localhost:8088`. Confirm no **MOCK DATA** badge. Confirm **AETHER CITY**, trucks, and a non-zero `events/s` only if the map is visible. The stream and engine are covered by the dashboard; open `docs/ux/screenshots/desktop-03-city-zoomed.png` for that view. Do not rely on collapsing panels. If a panel is a chip, click it (**Expand Speed windows**, and the same pattern for the other panel titles).
4. If the banner says **No execution**, click **Start demo pipeline**. Leave it running so **Alerts** can show the stuck-truck line before you start. At `SIM_TIME_SCALE=10`, truck-007 stops after 5 simulated minutes (30 real seconds) and the rule needs 10 one-minute windows (another 60 real seconds), about 90 s in all. While you wait, a running execution shows **No alerts — every truck is moving.**
5. `curl -fsS http://localhost:18080/api/ready` and `curl -fsS http://localhost:18080/api/version`. Write down `commit`. The footer shows the first 7 characters of that value, labelled **Deployed version:**. It is the running image, not this task branch.
6. Open Grafana `http://localhost:13000`, dashboard **AetherStream engine**. Anonymous access is read-only. Prometheus, if asked, is `http://localhost:19090`.
7. Open these runs in tabs. They were green when this script was written. If a newer green run is on the Actions page, use that and read it.
   - CD, publish + deploy + smoke: [44438884629](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44438884629) (`d1714e34e04fa1ed5feba43dfb959342be8d92a6`, runner `vivek-pc-deploy`, log line `PASS: health is UP and deployed commit is d1714e34e04fa1ed5feba43dfb959342be8d92a6`).
   - Compose e2e: [44410772993](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44410772993) (`71 / 71 results match`; steps 9-11 skipped).
   - Testcontainers: [44395770221](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44395770221) (`EngineMatchesReferenceIT`, `"matched":811`).
   - kind: [44398295492](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44398295492).
   - Terraform: [44395471523](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44395471523).
   - Unit tests: [44439772370](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44439772370) (`./gradlew build`).
8. Keep `docs/ux/screenshots/` open. Use a file only if the live step fails.

## Cold start

- CI measurement: pending the first green [cold-start workflow run](https://github.com/VivekTiwary-25/AetherStream/actions/workflows/ci-coldstart.yml); it prints the phase timings in its summary. The script appends dated measurements to [`docs/expo/cold-start-times.md`](docs/expo/cold-start-times.md).
- vivek-pc laptop: <to be measured by the boss with `bash infra/scripts/cold-start-timer.sh --from-cold`>.
- Start Docker Desktop at least N minutes before the demo, where N is the latest measured total rounded up to minutes plus a 5-minute margin. Do not run this measurement on vivek-pc before the boss does.

## 12-minute script

### 0:00-0:45 — Stack is up

**Click / run:** `docker compose ps`. Point at `coordinator`, `worker-1`, `worker-2`, `worker-3`, `simulator`, `control-api`, `web`.

**Audience sees:** Healthy app containers. Init containers exited.

**Say:** "This is the Compose stack the examiner is looking at: Kafka, Postgres, MinIO, three workers, and the web UI on port 8088."

**Fallback:** [ci-compose 44439772374](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44439772374), step **Smoke** (`bash infra/scripts/smoke.sh`). No container screenshot is checked in.

### 0:45-2:00 — City, stream, engine panels

**Click / run:** Stay on `http://localhost:8088`. Point at **Speed windows**, **Deliveries / min**, **Alerts**, **Workers**. Point at **AETHER CITY**, trucks, and `events/s` only if the map is on screen. Hover one truck if you can land on it: **Speed**, **Route**, **Partition**. Do not point at the particle stream or the engine core on the live page.

**Audience sees:** Legend **Driving**, **Unloading**, **Idle**, **Stopped**, **Event**, **Rejected**, **Late** when the legend is visible. Speed rows like `32.4 km/h`. Deliveries: **N deliveries in the last completed sim minute**, and **sim minute · latest highlighted**. Workers show **Running**, **Source queue**, **Sink queue**, capacity 1000. If the pipeline has been up long enough, **Alerts** contains `truck-007 has not moved for 10 min`. Empty, while running: **No alerts — every truck is moving.**

**Say:** "The trucks are the data source. Events enter the engine through Kafka, six partitions, keyed by truck. If the dashboard is covering the stream, that view is `desktop-03-city-zoomed.png`. Every number in these panels is a window or alert the engine wrote, not a drawing."

**Fallback:** Stream and engine covered: `docs/ux/screenshots/desktop-03-city-zoomed.png`. Also `desktop-01-app-viewport-60s.png`, `desktop-02-app-fullpage-60s.png`, `desktop-panel-speed-windows.png`, `desktop-panel-deliveries-min.png`, `desktop-panel-alerts.png`, `desktop-panel-workers.png`. If the socket dot is not connected, say **Live connection lost — reconnecting…** and open `curl -fsS http://localhost:18080/api/ready`.

### 2:00-3:30 — Pipeline editor: examples, Validate, Run

**Click / run:** Click **Pipeline**. In **Examples**, choose **Broken logistics demo**. Read **This pipeline has 3 mistakes - press Validate**. Click **Validate**. Click one **Show me**. Then choose **Truck city: speed, deliveries, stuck trucks**, click **Validate**. Before **Run**, say it starts a new execution, so the stuck-truck alert needs about 90 s again. Click **Run** only after the audience has seen the success line, and only if you can wait. If the alert must stay on screen, close with **Close pipeline editor** and say **Run** is enabled but you are not restarting.

**Audience sees:** Dialog **Pipeline editor**. Palette **Operators**: **Source**, **Filter**, **Map**, **Key-by**, **Window**, **Rule**, **Sink**. After Validate on the broken example, region **Validation errors** with three rows and three **Show me** buttons. The messages are:

- `TYPE_MISMATCH` — Window `speed1m` needs a Key-by before it.
- `DANGLING_NODE` — Filter `del` has no output connection.
- `TYPE_MISMATCH` — Sink `alertSink` needs a Window before it.

**Run** stays disabled until validation succeeds. The good example prints `Valid - 11 operators, 6 partitions`, then **Plan order:**.

**Say:** "The compiler collects every mistake and says how to fix it. The same operators run in the engine and in the reference executor. **Run** saves this graph and starts it; a second start stops the previous execution."

**Fallback:** If the examples request returns 404, the editor shows **Load demo** instead of the **Examples** selector. Click **Load demo**, then **Validate**. No editor screenshot is in `docs/ux/screenshots/`. API fallback:

```sh
curl -fsS http://localhost:18080/api/pipelines/examples
curl -fsS http://localhost:18080/api/pipelines/examples/broken-for-demo -o broken.json
curl -fsS -X POST http://localhost:18080/api/pipelines/validate -H 'content-type: application/json' --data-binary @broken.json
```

### 3:30-5:15 — Late events, slow output, kill, recover

**Click / run:** In **Chaos controls**, click **Send late events**. Open **Dead letters** and click tab **Late (N)** (the other tab is **Rejected (N)**). Do not wait for late rows in that tab. Set **Slow down output** to **200 ms per record**, point at the hint, and do not wait for **Backpressured**. Set it back to **Off** before the kill. Click **Kill worker**. Confirm text: **Kill worker worker-1? It will crash without warning.** Leave **Busiest worker (worker-1)**. Click **Confirm kill**. Read the id on screen if it is not worker-1. Watch the banner.

**Audience sees:** Status **20 late events sent**. Tabs **Rejected (N)** / **Late (N)**. Hint: **Output slowed: queues fill up (bounded at 1,000) and the engine pushes back.** After confirm: **Worker worker-1 killed.** Banner **Recovering from checkpoint #N**, then **Recovered**. Timeline title, if you can read it: **Recovering from checkpoint #N (generation G → G+1)**. Do not expect a **Dead** row. Do not read **Recovered in -** as a duration.

**Say:** "Late means the window already closed on that partition's watermark; the event is a dead letter, not a silent drop. Killing a worker is a real halt. The coordinator restores the last completed checkpoint, bumps the generation, and fences the old worker off the Postgres sink."

**Fallback:** Do not wait for **Backpressured**. It did not appear within 75 s at 200 ms per record. Late rows may stay **Late (0)** until a reload. Reload once, or read `GET /api/results/dead-letters?kind=LATE` and summary `late`. Do not call an empty tab "no late events". After the kill the worker card may disappear, then show **Idle**, then **Running**. Use banner **Recovered**. If the timeline ends on **Recovered in -**, use the e2e duration, not the dash. Open [ci-e2e 44410772993](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44410772993): `4 PASS Late events reached the dead-letter sink`, `7 PASS Slow sink caused backpressure; clearing delay drained it`, `5 PASS worker-3 killed; recovered in 6s from checkpoint >= 2, generation > 1`. Screenshots: `desktop-panel-dead-letters.png`, `desktop-panel-workers-after-kill.png`, `desktop-04-recovering-viewport.png`, `desktop-05-recovering-fullpage.png`, `desktop-recovered-banner.png`, `desktop-06-after-recovery-fullpage.png`. Command equivalent, from `infra/scripts/demo-e2e.sh`:

```sh
curl -fsS -X POST http://localhost:18080/api/chaos/late-events -H 'content-type: application/json' -d '{"count":20,"lateByMs":180000}'
curl -fsS -X POST http://localhost:18080/api/chaos/slow-sink -H 'content-type: application/json' -d '{"delayMs":200}'
curl -fsS -X POST http://localhost:18080/api/chaos/slow-sink -H 'content-type: application/json' -d '{"delayMs":0}'
```

The script kills with `docker compose kill` on the Compose service `worker-1`, `worker-2`, or `worker-3`. The button uses `POST /api/chaos/kill-worker`. Either is a real crash. Kind is a different path: [44398295492](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44398295492) logs `Recovered from checkpoint >= 1 with generation > 1 within 60s` and then `kubectl get pods -A -o wide`. Kind pods are `worker-0`, `worker-1`, `worker-2`.

### 5:15-6:15 — Compare with reference

**Click / run:** In **Reference comparison**, click **Compare with reference**. Wait for **Comparing…** to finish.

**Audience sees:** Heading **N / N results match** when `match` is true, plus **Checked up to checkpoint #N - replayed … events in … ms**. A mismatch shows **N mismatches** and columns **Type**, **Sink**, **Key**, **Window start**, **Detail**. Read the N on the page. Do not reuse 71 or 811 for this click.

**Say:** "The reference is a single-process executor running the same operators on the Kafka records up to the latest completed checkpoint. A match is that range, not a proof of every future failure."

**Fallback:**

```sh
curl -fsS -X POST http://localhost:18080/api/compare -H 'content-type: application/json' -d '{}'
```

Cited logs, not substitutes for a live click: e2e [44410772993](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44410772993) printed `6 PASS 71 / 71 results match`. Integration [44395770221](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44395770221) printed `"referenceResults":811,"engineResults":811,"matched":811,"match":true`. No compare screenshot is checked in.

### 6:15-7:15 — Savepoint, restore, rescale

**Click / run:** Open **Operations**. Click **Take savepoint**. Before **Restore**, say it starts a new execution, so the stuck-truck alert needs about 90 s again. **Restore** asks **Restart the pipeline from this savepoint?** Click **Restore** only if you can wait. The rescale sentence is **Pauses at the next checkpoint, moves partitions, resumes - a controlled pause, not live migration.** Click **Rescale** to show the refusal.

**Audience sees:** A `savepointId` and checkpoint number. Restore should return a new execution id. The same idempotency key returns that same execution. Rescale on a balanced three-worker assignment returns HTTP 409, body code `CONFLICT`, message `execution ID does not need a rebalance`. That refusal is the feature. It runs when a live worker owns no partitions, or the busiest and quietest differ by at least two, and not again within 30 seconds.

**Say:** "A savepoint keeps a completed checkpoint. Restore starts a new execution from it. Rescale is a pause at the next checkpoint, not live migration. CI does not currently prove restore: `ci-e2e` sets `E2E_EXTENDED=0` and prints `9-11 SKIP extended steps`."

**Fallback:** Curls, if the click fails. Then open [44410772993](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44410772993) and point at that skip line. No ops screenshot is in `docs/ux/screenshots/`. An incompatible savepoint is a 409 `INCOMPATIBLE_SAVEPOINT`, not a timeout.

```sh
curl -fsS http://localhost:18080/api/executions/current
curl -fsS -X POST "http://localhost:18080/api/executions/$ID/savepoints"
curl -fsS http://localhost:18080/api/savepoints
curl -fsS -X POST "http://localhost:18080/api/executions/$ID/restore" -H 'content-type: application/json' -d "{\"savepointId\":\"$SP\",\"idempotencyKey\":\"demo-restore-1\"}"
curl -fsS -X POST "http://localhost:18080/api/executions/$ID/rescale"
```

### 7:15-8:00 — Scenarios

**Click / run:** Open **City scenarios**. Click **Rush hour**, **Breakdown**, **Surge**, or **Reset**. **Breakdown** also has **Breakdown truck**.

**Audience sees:** Panel status **Active: Rush hour** (or the label you clicked). Do not claim a truck-count or speed change you cannot see.

**Say:** "These are simulator inputs, not engine operators. Breakdown stops one truck so the stuck rule has something to see. Reset returns the scenario to none."

**Fallback:** Curls below. Live labels come from `GET /api/scenarios`: **Rush hour**, **Breakdown**, **Surge**, **Reset**. **Surge (+6 trucks)** appears only when that GET returns 404. No scenario screenshot is checked in. If the simulator is down, the API returns `UPSTREAM_DOWN` / `simulator is not reachable`.

```sh
curl -fsS http://localhost:18080/api/scenarios
curl -fsS -X POST http://localhost:18080/api/simulator/scenario -H 'content-type: application/json' -d '{"name":"rush-hour"}'
curl -fsS http://localhost:18080/api/simulator/state
curl -fsS -X POST http://localhost:18080/api/simulator/scenario -H 'content-type: application/json' -d '{"name":"reset"}'
```

### 8:00-8:45 — Presenter tour and version footer

**Click / run:** Click **Tour** and walk the eight steps in order. Then point at the footer **Deployed version:** and compare it with `curl -fsS http://localhost:18080/api/version`.

**Audience sees:** Tour step titles **The city**, **The stream into the engine**, **Live panels**, **Pipeline editor**, **Chaos**, **Kill a worker**, **Compare with reference**, **DevOps**, with **Back**, **Next**, **Close**, **Finish**, and **Step N of 8**. The intro card says **a fault-tolerant stream engine** and **Skip** when shown. The footer is seven characters of `commit`, or **unknown** if `/api/version` failed.

**Say:** "The tour is eight sentences over this same page. The footer commit is the GIT_SHA of the image that is running, which is what the deploy smoke checks."

**Fallback:** If step 2, **The stream into the engine**, spotlights a covered city, use `docs/ux/screenshots/desktop-03-city-zoomed.png`. Read `web/src/app/tour/tourSteps.ts`. No tour screenshot is checked in.

### 8:45-10:00 — A push through Actions, GHCR, vivek-pc, smoke

**Click / run (optional live push):** This is the sole exception to the opening no-push rule. Reserve about **10 minutes**, not this step's 75 seconds; for a timed talk use the recorded fallback below. Pause chaos actions and explain that CD restarts the live stack. In a separate, clean `main` checkout on vivek-pc, already matching both remote `main` and `http://localhost:18080/api/version`, run `bash infra/scripts/demo-push.sh --push`. It refuses task branches, unpublished commits, dirty/staged/untracked files, a different push remote, or a main CD run still in flight. It changes only `web/public/expo-build.txt`, commits `demo: expo build #N`, pushes without force, prints the commit checks/CD run URLs, waits for that SHA's **deploy** job to succeed, then checks the local API for the exact full SHA. Do not retry blindly after a failure: inspect the checkout and printed run first.

**Audience sees:** Elapsed seconds for preflight, counter/commit, push, Actions discovery, publish/deploy, and live version verification. Open the printed Actions run and show **publish** and **deploy** on `vivek-pc-deploy`. After `DEPLOYED`, refresh the browser: the footer shows the new seven-character **Deployed version:** beside **Expo build #N**, loaded at runtime from `/expo-build.txt`.

**Timing evidence (2026-11-19):** Public API CD `created_at` → **deploy job `completed_at`** for the five recent successful main pushes: [44452672776](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44452672776) **9m12s**, [44451691749](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44451691749) **10m15s**, [44449780278](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44449780278) **14m46s**, [44449183832](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44449183832) **12m06s**, [44448578181](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44448578181) **8m30s**. Median **10m15s**, range **8m30s–14m46s**, including queueing and live-demo verification. [INFERENCE] Expected new counter-only push → deployed: **about 10 min**, with an idle deploy runner; filtering unrelated CI reduces contention but is not a measured latency guarantee. Allow 15 minutes; queued or cold-cache runs can take longer.

**Say while waiting:** "Push → deployed takes about 10 minutes. This commit changes only the visible Expo counter. Web CI still runs; long backend tests, benchmarks, Kubernetes and Terraform do not run for that counter-only main push. CD publishes four SHA-tagged images using its existing BuildKit cache, then the main-only `aether-deploy` runner pulls that exact SHA and checks health, version, and the live demo. We keep all four images: deployment uses one `AETHER_TAG` for every service, so a web-only publish would leave the new SHA's backend images missing. While Actions works, I can explain checkpoints and fencing."

**Fallback:** Do not push during a short slot or with a busy runner. Open [cd run 44452672776](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44452672776), or use `gh run list --workflow cd.yml --branch main --limit 5` and select a successful run with **publish** and **deploy**. Show GHCR tags, runner `vivek-pc-deploy`, **Deploy requested image SHA**, **Smoke-test deployment**, and **Verify live demo**. Read that run's smoke SHA, not today's footer unless the API matches it. The new filters skip `ci-e2e`, `ci-compose`, `ci-images`, `ci-coldstart`, `ci-monitoring`, and `ci-ui-e2e` for a counter-only push; integration, soak, bench, Kubernetes and Terraform already exclude it. There are no `ci-longrun` or `ci-kill-repeat` workflow files in this checkout. CD security, SHA pulls, image selection and caching are unchanged; there is no claimed measured speedup yet.

### 10:00-10:45 — Containers and Grafana

**Click / run:**

```sh
docker compose ps
docker compose images
docker inspect --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' "$(docker compose ps -q control-api)"
```

Then open Grafana, dashboard **AetherStream engine**.

**Audience sees:** Compose image names and a revision label if the build set one. Local images are `aether/engine:local` and the other `:local` tags. Grafana panels for alive workers, checkpoint duration, queue size, and backpressure. Empty panels mean the metric is not there yet.

**Say:** "Compose is what you are watching. The GHCR tags are what CD pulled. Grafana is the monitoring profile, not part of a plain `docker compose up`."

**Fallback:** [ci-compose 44439772374](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44439772374). `ci-images` builds `aether/engine:ci` and the other `:ci` tags and does not push. No Grafana screenshot is checked in.

### 10:45-12:00 — Tests

**Click / run:** Open these logs. Do not start kind or Terraform on the demo machine.

**Audience sees:**

- Unit: [44439772370](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44439772370), step **Build and unit-test every module**, command `./gradlew build`.
- Web, if asked: `npm ci`, `npm run lint`, `npm run test`, `npm run build` in `web/`. Workflow `ci-web`.
- Testcontainers: [44395770221](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44395770221), `./gradlew :tests:integrationTest`, `EngineMatchesReferenceIT` 811/811.
- e2e: [44410772993](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44410772993), `bash infra/scripts/demo-e2e.sh`.
- kind: [44398295492](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44398295492), `kubectl get pods -A -o wide`.
- Terraform: [44395471523](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44395471523). Apply step prints `Plan: 24 to add, 0 to change, 0 to destroy.` and `Apply complete! Resources: 24 added, 0 changed, 0 destroyed.` Destroy prints `Plan: 0 to add, 0 to change, 24 to destroy.` There is no step named plan. This is the Docker provider, not a cloud account.

**Say:** "Unit tests and Testcontainers run in CI. The e2e script drives this same API. kind and Terraform are extra deployment proofs, not the stack on this laptop."

**Fallback:** `gh run list --limit 20`. A missing log is not a pass. `bench` is `./gradlew :tests:benchmarkTest`. Do not quote a benchmark number.

## 3-minute short version

| Clock | Click / run | Audience sees | Say |
|---|---|---|---|
| 0:00 | `http://localhost:8088`, **AETHER CITY**, **Speed windows** | Trucks and panel numbers. If the stream is covered, `desktop-03-city-zoomed.png` | "Trucks are the source. Panels are engine output." |
| 0:30 | **Pipeline** → **Broken logistics demo** → **Validate** → good example → **Validate** | Three errors, then `Valid - 11 operators, 6 partitions` | "The compiler names the fix." Before **Run**, say the stuck-truck alert needs about 90 s again. Skip **Run** if you cannot wait. |
| 1:10 | **Kill worker** → **Confirm kill** | Banner **Recovering from checkpoint #N**, then **Recovered**. Do not wait for **Dead** | "Real crash, restore last completed checkpoint, new generation." |
| 1:50 | **Compare with reference** | The **N / N results match** line on screen | "Same operators, one checkpoint range. This N is this click." |
| 2:20 | Footer **Deployed version:** and [cd run 44438884629](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44438884629) | `vivek-pc-deploy`, GHCR tags, smoke `PASS` | "That push published images and deployed them here. I am not pushing now." |
| 2:45 | [44398295492](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44398295492) or [44395770221](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44395770221) | kind recovery line, or 811/811 | "kind and Testcontainers are the other proofs." |

If a click fails, use the fallback in the matching 12-minute step.

## Likely examiner questions

1. **Is this exactly once?** Input replay after failure is at-least-once. Operator state is restored with the offsets from one completed checkpoint. Postgres window, alert, and dead-letter tables are upserts, so those tables are effectively exactly-once. No other sink has that claim. Contract 03 section 5.
2. **What if the coordinator dies?** One active coordinator. On restart it reloads `RUNNING` or `RECOVERING`, waits one heartbeat timeout for workers to register, then recovers. There is no second active coordinator.
3. **Why per-partition watermarks?** Records are processed in offset order inside a partition. An idle partition does not advance its watermark, so its open windows stay open. Contract 02 section 4.
4. **How is backpressure bounded?** Each worker has a source queue and a sink queue, capacity 1000. A full queue blocks the upstream stage. The UI marks **Backpressured** at 90% full.
5. **What is a late event?** If the window has already closed on that partition's watermark, the record is a `LATE` dead letter and is not added. A duplicate `eventId` inside an open key-window is ignored, not a dead letter.
6. **How is a dead worker fenced?** Assignment generation is the token. The sink transaction reads `generation` with a row lock and aborts if it differs. A command for an older generation is ignored.
7. **What is an aligned checkpoint?** The source places a barrier behind records already queued. The sink flushes outputs that came before the barrier, then uploads state and offsets. The coordinator marks the checkpoint `COMPLETED` only after every partition has acked and the manifest upload succeeded. Restore uses the latest `COMPLETED` checkpoint only.
8. **Why a reference executor?** It runs the same operator code in one process over the checkpointed offset range. The button's N is that call. The 71/71 line is e2e run 44410772993. The 811/811 line is integration run 44395770221. They are not the same execution.
9. **Is this Flink?** No. It is a scoped teaching engine: tumbling windows, two key fields, Postgres sinks, one coordinator.
10. **Savepoint versus checkpoint?** A savepoint is a retained completed checkpoint with a compatibility check on restore. The routes are `POST /api/executions/{id}/savepoints`, `GET /api/savepoints`, and `POST /api/executions/{id}/restore`. Contract 04 does not list them; the controller and `infra/scripts/demo-e2e.sh` do. Current e2e skips those steps.
11. **Does rescale move partitions live?** No. It pauses at the next checkpoint, bumps generation, and reassigns the six partitions. A balanced assignment is HTTP 409 `does not need a rebalance`.
12. **Where are the scenario buttons and the tour?** On the page. **Operations**: **Take savepoint**, **Restore**, **Rescale**. **City scenarios**: **Rush hour**, **Breakdown**, **Surge**, **Reset**, and **Breakdown truck**. **Tour** opens eight steps with **Close**, **Back**, **Next**, **Finish**, and **Step N of 8**. Scenario names are `rush-hour`, `breakdown`, `surge`, `reset`. Step copy is in `web/src/app/tour/tourSteps.ts`.
13. **Does the footer match git?** It matches `GET /api/version`, which is the image `GIT_SHA`. Compare it to the CD smoke line, not to an unpushed task branch.
14. **Is a green `ci-ui-e2e` a passed browser test?** No. That job is `continue-on-error: true`. Open the log.

## If a step fails

- Unhealthy container: `docker compose ps -a` and `docker compose logs --tail=100 coordinator` (or the service that failed). Do not volume-down the live demo.
- Page up, no data: `curl -fsS http://localhost:18080/api/ready`. Then logs for `control-api`, `coordinator`, `simulator`, `worker-1`.
- **Start demo pipeline** missing: an execution is already current. Use **Pipeline** then **Run**, or `GET /api/executions/current`.
- Compare errors: wait until the banner shows a completed checkpoint, then click again. Otherwise open run 44410772993 or 44395770221.
- Kill shows nothing in 20 seconds: banner **Recovered** is the proof. Do not wait for a **Dead** row or a timeline duration. Show `desktop-04-recovering-viewport.png` and the e2e or kind log. Do not click **Confirm kill** twice.
- Rescale 409: expected when the assignment is balanced. Read the message.
- Restore fails: read the JSON. Point at `9-11 SKIP extended steps (E2E_EXTENDED=0)` in run 44410772993. Do not call the skip a pass.
- Grafana empty: say the profile is up but the series is absent. Do not draw a graph.
- Actions run red or missing: open the log. A workflow file is not a green run.
