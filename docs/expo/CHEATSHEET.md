# Expo cheat sheet (one A4)

Click only these labels. No **MOCK DATA** badge. Do not push to `main`. Do not run `docker compose down -v`.

## URLs

- App: <http://localhost:8088>
- API: <http://localhost:18080> — footer **Deployed version:** is the first 7 characters of `GET /api/version`
- Grafana: <http://localhost:13000> — dashboards **AetherStream engine** and **AetherStream Demo** (<http://localhost:13000/d/aetherstream-demo/aetherstream-demo>); anonymous, read-only. Prometheus, if asked: <http://localhost:19090>
- Actions: <https://github.com/VivekTiwary-25/AetherStream/actions> — if slow, do not wait; show [run 44440449358](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44440449358)

## Before / after the slot

- Before: `bash infra/scripts/demo-freeze.sh on`, then `bash infra/scripts/demo-freeze.sh status`. Freeze skips CD deployment and live checks with a green job and a notice in its summary; images still build/push. Wait for any deployment already running to finish before presenting.
- No `gh`? Repository **Settings → Secrets and variables → Actions → Variables → Repository variables**: set `DEMO_FREEZE` to `1`. The helper prints these instructions when `gh` is missing.
- A committed `infra/deploy/FREEZE` also freezes CD. After the slot: `bash infra/scripts/demo-freeze.sh off`; remove that file from the deployed branch too, if present. Unfreezing affects future runs only; dispatch CD on `main` to deploy a skipped SHA.
- CD and Expo autostart (online/offline) start the `monitoring` profile. Prometheus is capped at **512m**, Grafana at **256m**. Check `docker compose -p aether --profile monitoring ps` and the Grafana URL before the audience arrives.

## Six clicks (labels from DEMO.md)

1. **City** — Banner **No execution**: **Start demo pipeline**, then **City scenarios** → **Breakdown** → **Breakdown truck: truck-007**. Wait up to 90 s at `SIM_TIME_SCALE=10` for **Alerts**: `truck-007 has not moved for 10 min`. Else point at **AETHER CITY**, **Speed windows**, **Deliveries / min**, **Alerts**, **Workers**.
2. **Editor** — **Pipeline** → **Broken logistics demo** → **Validate** → **Show me** → **Truck city: speed, deliveries, stuck trucks** → **Validate**. **Run** only if you can wait; else **Close pipeline editor**. If **Examples** is missing: **Load demo**, then **Validate**.
3. **Chaos** — In **Chaos controls**, click **Send late events**. Open **Dead letters**, tab **Late (N)** (other tab **Rejected (N)**); rehearsal reached **Late (20)** within 1.5 s without reload. Set **Slow down output** to **200 ms per record**, then **Off**; **Backpressured** appeared within 5 s at queues 32/32. The on-screen hint says bounded at 1,000; Compose uses a bounded queue (32 in the demo config). **Kill worker** → **Busiest worker (worker-1)** → **Confirm kill**. Banner **Recovering from checkpoint #N**, then **Recovered**. Do not wait for **Dead**. Do not click **Confirm kill** twice.
4. **Compare** — In **Reference comparison**, click **Compare with reference**. It took 2.6 s in rehearsal; **Comparing…** was too brief to see. Read **N / N results match** on the page. Do not reuse 71 or 811.
5. **Ops** — Open **Operations**. **Take savepoint**. **Restore** asks **Restart the pipeline from this savepoint?** — only if you can wait. **Rescale** on a balanced assignment returns `execution ID does not need a rebalance`. That refusal is the feature.
6. **Scenarios** — Open **City scenarios**. Click **Rush hour**, **Breakdown**, **Surge**, or **Reset**. **Breakdown** also has **Breakdown truck**. Status shows **Active: Rush hour**, **Active: Breakdown**, **Active: Surge**, or **Active: Reset** for the clicked button; Reset corresponds to simulator scenario `none`.

## Five saves (only commands in this checkout)

1. `infra/scripts/expo-check.sh` is not here. Do not run it.
2. Empty panels, no execution, or no window progress: `bash infra/scripts/demo-ready.sh`
3. Worker stuck after a kill: `docker compose -p aether start worker-1` (substitute `worker-2` or `worker-3`)
4. No `docker compose restart` of `web` or `control-api` is written here. Page up, no data: `curl -fsS http://localhost:18080/api/ready`
5. No internet, images already cached by `expo-prepull.sh`: `bash infra/scripts/expo-offline-up.sh` (separate `aether-offline` stack, includes monitoring; do not run alongside the live stack on the same ports).

Clean demo between visitor groups: `bash infra/scripts/expo-reset.sh` (preview: `--dry-run`; alternate API: `--api URL`; erase pipeline/execution history too: `--hard`).

## Show me

From this checkout, with the Compose demo running: `bash infra/scripts/expo-proofs.sh all`.
Requires Bash, Docker Compose, curl, and jq; alternate API: `API=http://localhost:18080 bash infra/scripts/expo-proofs.sh all`.
Read-only: no checkpoint triggers, chaos calls, object writes, or database updates.

- `checkpoints`: five latest MinIO manifests plus the latest completed manifest's id, generation, partition next-read offsets, and worker state object keys.
- `offsets`: each Kafka partition's log end versus the completed checkpoint; replay distance is not consumer-group lag (workers use manual assignment).
- `fencing`: current execution generation/audit history and up to ten matching coordinator/worker log lines from the last 30 minutes. No matching logs means no rejection was observed, not that fencing failed.
- `recovery`: latest completed worker-loss recovery in the stack audit, including worker, restored checkpoint, and `durationMs`; explicitly identifies whether it belongs to the current execution.
- `all`: all five blocks (checkpoint listing, manifest, offsets, fencing, recovery). Missing evidence or a failed read exits nonzero; finish the existing **Kill worker** demo first if no recovery is recorded. Checkpoint 0 alone may have no manifest.

## Backup

Recording folder: `docs/expo/backup/` (not in this checkout). Screenshots that exist: `docs/ux/screenshots/`.

## Five answers

1. **Exactly once?** Replay is at-least-once; state is one completed checkpoint; Postgres window, alert, and dead-letter upserts are effectively exactly-once. No other sink.
2. **Coordinator dies?** One active coordinator. On restart it reloads `RUNNING` or `RECOVERING`, waits one heartbeat timeout, then recovers. There is no second coordinator.
3. **Flink?** No. Tumbling windows, two key fields, Postgres sinks, one coordinator.
4. **Savepoint vs checkpoint?** A savepoint is a retained completed checkpoint with a compatibility check on restore.
5. **Footer vs git?** It matches `GET /api/version` (the image `GIT_SHA`). Compare it to the CD smoke line, not to an unpushed task branch.
