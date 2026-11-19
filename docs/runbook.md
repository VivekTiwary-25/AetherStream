# Demo-day operations runbook

Run these commands from the repository root. On the demo PC, use Docker Desktop with its Linux engine running and Git Bash for the Bash commands. The PowerShell examples use native PowerShell commands where quoting or variables differ. Local Compose commands deliberately use project `aether`, the same project name used by CD; do not start a second project under another name.

## Autostart

On the expo laptop, enable Docker Desktop's **Start when you sign in** option. Use a persistent checkout with Git Bash at `C:/Users/Arena/AppData/Local/Programs/Git/bin/bash.exe`, `curl` and `jq` on its PATH, and Docker Compose supporting `--wait`. Autostart reuses CD project `aether`, `docker-compose.yml` and `infra/deploy/docker-compose.deploy.yml`; it never builds images. Set the Arena user environment variable `AETHER_TAG` to a published commit SHA, or keep `infra/deploy/.last-deployed` from a successful CD deployment in this checkout. It does not choose a newer image tag automatically.

From PowerShell in that checkout, preview safely before the boss registers the task:

```powershell
powershell.exe -NoProfile -File infra/deploy/expo-autostart.ps1 -DryRun
# Boss only; registers the task without starting it:
powershell.exe -NoProfile -File infra/deploy/register-expo-autostart.ps1
# Remove the logon task without stopping containers:
powershell.exe -NoProfile -File infra/deploy/register-expo-autostart.ps1 -Unregister
```

`AetherStream-Expo-Autostart` runs with Arena's interactive, non-elevated token 30 seconds after Arena logs on, including on battery. Keep the registered checkout in place; rerun registration to change its path. Registration replaces only this named task and never starts Docker or Compose.

The script waits up to five minutes for Docker, then waits up to 180 seconds for Compose health and runs `infra/scripts/demo-ready.sh http://localhost:8088` via Git Bash with `EXPECT_SHA` set to the chosen image SHA. That readiness check may start/restart a missing or stalled demo pipeline. Every command, captured output line and exit code is timestamped in `C:/Users/Arena/aether-autostart.log` (native output is collected when each command finishes). Success opens a console with the final `DEMO READY ...` line and **http://localhost:8088**; failure opens a `DEMO FAILED` console and returns exit code 1.

Set the Arena user environment variable `AETHER_OFFLINE=1` before sign-in to force offline mode; otherwise a five-second GHCR connectivity probe selects it when no HTTP response is received. Offline mode appends root `docker-compose.offline.yml` after the CD override if that file exists, and always uses `--pull never --no-build`. Without the offline file it uses cached CD images. Pre-cache every required image for the selected SHA; missing images fail rather than trigger a download/build. `-DryRun` prints both branches without contacting Docker/network, writing logs, starting the pipeline or opening a result console.

## Public link watchdog

`infra/deploy/tunnel-watchdog.ps1` reads the newest `https://<words>.trycloudflare.com` URL in `C:/Users/Arena/aether-tunnel.log` every 60 seconds and GETs `/api/version` with a 10-second timeout. Three consecutive failures (including a missing URL) restart only Scheduled Task `AetherStream-Tunnel`, which runs `C:/Users/Arena/bin/aether-tunnel.cmd`; success resets the failure count. Recovery waits up to 30 seconds for task shutdown and polls every two seconds for a different URL for up to 120 seconds, then resumes monitoring if none appeared. The pre-restart URL is not republished as a replacement.

Each discovered URL updates `C:/Users/Arena/aether-public-url.txt` and `C:/Users/Arena/aether-link.html`. Open the HTML file locally for a 64px clickable URL (QR omitted; no external resources); the page refreshes every 60 seconds. These files show the last discovered URL, not a guarantee of readiness, and can remain stale while recovery is pending. The tunnel task must already exist, and Arena must have permission to stop/start it and write those files; the watchdog does not change task permissions.

```powershell
powershell.exe -NoProfile -File infra/deploy/tunnel-watchdog.ps1 -DryRun
powershell.exe -NoProfile -File infra/deploy/register-tunnel-watchdog.ps1 -DryRun
# Boss only, after reviewing the preview; do not register during worker verification:
powershell.exe -NoProfile -File infra/deploy/register-tunnel-watchdog.ps1
# Stop/remove only the watchdog, leaving the tunnel and link files alone:
powershell.exe -NoProfile -File infra/deploy/register-tunnel-watchdog.ps1 -Unregister
```

`AetherStream-Tunnel-Watchdog` runs at Arena logon with an interactive, non-elevated token, including on battery, with no execution time limit and duplicate instances ignored. Keep the registered checkout available. Registration does not start it; both scripts' `-DryRun` exits without task changes, requests, file writes, or waiting.

## Expo quick fixes (21 Nov)

Run from the repository root. Do not put credentials in commands or logs.

### Before the demo: version, health, containers, and public link

Compare the commit served by the deployed UI/API with the latest remote `main` SHA. They should match:

```bash
git ls-remote origin refs/heads/main
curl -fsS http://localhost:8088/api/version
```

Check readiness, fresh window results, a completed checkpoint, and the reference comparison; this script starts or restarts the demo pipeline if there is no active execution or no window progress:

<!-- ci-check -->
```bash
bash infra/scripts/demo-ready.sh
```

Check container state, then check the web UI and API locally:

<!-- ci-check -->
```bash
docker ps
curl -fsS http://localhost:8088/api/health
curl -fsS http://localhost:18080/api/ready
```

For the public link, run a quick tunnel and share the HTTPS URL printed in that terminal log. The URL is temporary and changes whenever the tunnel restarts:

```bash
cloudflared tunnel --url http://localhost:8088
```

### Panels empty, or only LATE events

Check the result counters and LATE dead letters. If windows are not advancing, run `demo-ready.sh`; it restarts a missing/stalled pipeline and waits for windows, a completed checkpoint, and a clean comparison:

```bash
curl -fsS http://localhost:18080/api/results/summary
curl -fsS 'http://localhost:18080/api/results/dead-letters?kind=LATE&limit=20'
bash infra/scripts/demo-ready.sh
```

### Worker stuck after a kill

Start the existing worker container; substitute its Compose service name (`worker-1`, `worker-2`, or `worker-3`):

```bash
docker compose -p aether start worker-2
docker compose -p aether ps
```

### CD deploy failed

In GitHub **Actions → cd**, open the failed run and choose **Re-run jobs** (or **Re-run failed jobs**). For a hand rollback, take the full commit SHA from a known-good `cd` run whose `publish` and `deploy` jobs succeeded, then run both existing deploy scripts from the repository root in Git Bash:

```bash
bash infra/deploy/deploy.sh <full-good-commit-sha>
bash infra/deploy/smoke-deploy.sh <full-good-commit-sha>
```

The smoke check must report the same SHA from `/api/version`. Each of the four images for that SHA must already exist in GHCR.

### Demo PC low on memory

Stop optional monitoring containers first; this leaves the demo stack running:

```bash
docker compose -p aether --profile monitoring stop prometheus grafana
```

If memory is still critically low and the demo can pause, stop the stack without deleting its data:

```bash
docker compose -p aether stop
```

### No internet

If the local images and dependencies are already available, start the local Compose stack and use the UI/API on localhost. The app is at `http://localhost:8088`; the control API is at `http://localhost:18080`.

```bash
docker compose -p aether up --build -d --wait
curl -fsS http://localhost:8088/api/health
curl -fsS http://localhost:18080/api/ready
```

`--build` may need uncached dependencies; if they are not already present, internet loss prevents a fresh build. Do not rely on the public tunnel or GitHub Actions while offline.

### GitHub Actions slow

Open `docs/report/ci-cd-evidence.md` and use its dated prior green `ci-mock` run as the example: [run 44440449358](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44440449358). A prior green run is evidence for that run only, not proof that the current commit passed.

---

## 1. Start and stop the stack

*(For complete local startup, health-check, and recovery instructions, see the sections below.)*

### Local development images

Build the local images and wait for services to become healthy:

```bash
docker compose -p aether up --build -d --wait
```

The default `docker-compose.yml` builds the app from this checkout (`aether/engine:local` and the simulator, control API, and web images). It also starts Kafka, PostgreSQL, MinIO, and three workers. If Compose does not support `--wait`, run `docker compose -p aether up --build -d` and use the health checks below.

PowerShell:

```powershell
docker compose -p aether up --build -d --wait
```

### Deployed images

CD uses the full commit SHA as `AETHER_TAG` and overlays image definitions on the base Compose stack. To start a previously published SHA manually, use Git Bash:

```bash
bash infra/deploy/deploy.sh <full-commit-sha>
bash infra/deploy/smoke-deploy.sh <full-commit-sha>
```

Do not use the deploy overlay to start an unpushed local build. See section 6 for deployment and rollback.

### Stop

Stop containers but keep Kafka, PostgreSQL, and MinIO data:

```bash
docker compose -p aether stop
```

Start the same containers again with `docker compose -p aether start`. To stop and remove containers/networks but keep named data volumes, run `docker compose -p aether down`.

## 2. Health checks

App endpoints are exposed on the host at these ports:

- Web UI: <http://localhost:8088>
- Control API: <http://localhost:18080>
- Coordinator: <http://localhost:18081>
- Simulator: <http://localhost:18070>
- Kafka external listener: `localhost:19092`; PostgreSQL: `localhost:15432`
- MinIO API: <http://localhost:19000>; console: <http://localhost:19001>

Check the stack and each layer:

```bash
docker compose -p aether ps
curl -i http://localhost:18080/api/health
curl -i http://localhost:18080/api/ready
curl -i http://localhost:18081/coord/state
```

PowerShell equivalent for the HTTP requests:

```powershell
Invoke-RestMethod http://localhost:18080/api/health
Invoke-RestMethod http://localhost:18080/api/ready
Invoke-RestMethod http://localhost:18081/coord/state
```

Interpret failures:

- `docker compose -p aether ps` should show app services running/healthy. `kafka-init` and `minio-init` are one-shot jobs and may show `Exited (0)`; that is success. `Exited` with a nonzero code means initialization failed. Use `docker compose -p aether logs <service>` to inspect a service.
- `/api/health` reports only whether the control API process is up. `UP` does not mean its dependencies are ready.
- `/api/ready` checks PostgreSQL, Kafka, coordinator, and simulator. HTTP 503 or any `DOWN` value points to that dependency or its connection; check the named service logs and `docker compose -p aether ps`.
- `/coord/state` should return JSON with an execution (if one was started) and live workers. No workers or workers marked dead means registration/heartbeat trouble; a `RECOVERING` execution is discussed in section 5.

## 3. Start the demo pipeline via API

Wait for `/api/ready` to report all checks `UP`, then run this in Git Bash. It fetches the packaged demo definition, saves it, and starts it with a fresh idempotency key:

```bash
API=http://localhost:18080
PIPELINE="$(curl -fsS "$API/api/pipelines/demo")"
curl -fsS -X POST "$API/api/pipelines" -H 'Content-Type: application/json' -d "$PIPELINE"
```

Copy the returned pipeline `id`, then start it (replace `<pipeline-id>`):

```bash
curl -fsS -X POST "$API/api/pipelines/<pipeline-id>/executions" \
  -H 'Content-Type: application/json' \
  -d "{\"idempotencyKey\":\"$(cat /proc/sys/kernel/random/uuid)\"}"
```

The response should have `state: RUNNING`. Inspect it with:

```bash
curl -fsS "$API/api/executions/current"
curl -fsS "$API/api/results/summary"
```

PowerShell equivalent; copy the `id` printed by the first command into `$pipelineId`:

```powershell
$api = 'http://localhost:18080'
$pipeline = Invoke-RestMethod "$api/api/pipelines/demo"
$saved = Invoke-RestMethod -Method Post -Uri "$api/api/pipelines" -ContentType 'application/json' -Body ($pipeline | ConvertTo-Json -Depth 50 -Compress)
$pipelineId = $saved.id
$body = @{ idempotencyKey = [guid]::NewGuid().ToString() } | ConvertTo-Json -Compress
Invoke-RestMethod -Method Post -Uri "$api/api/pipelines/$pipelineId/executions" -ContentType 'application/json' -Body $body
```

## 4. Chaos by hand

Only inject chaos during the demo, after an execution is `RUNNING`. Read current execution and worker state from `GET /api/executions/current` and `GET /api/workers`.

**Late events.** Send older events and check that late events appear in dead letters, not normal windows:

```bash
curl -fsS -X POST http://localhost:18080/api/chaos/late-events \
  -H 'Content-Type: application/json' -d '{"count":20,"lateByMs":180000}'
curl -fsS 'http://localhost:18080/api/results/dead-letters?kind=LATE&limit=50'
```

Good: the API accepts the request and LATE dead-letter rows appear.

**Slow sink / backpressure.** Increase sink delay, observe worker queues and `backpressured`, then turn the delay off:

```bash
curl -fsS -X POST http://localhost:18080/api/chaos/slow-sink \
  -H 'Content-Type: application/json' -d '{"delayMs":200}'
curl -fsS http://localhost:18080/api/workers
curl -fsS -X POST http://localhost:18080/api/chaos/slow-sink \
  -H 'Content-Type: application/json' -d '{"delayMs":0}'
```

Good: delay causes queues/backpressure or lag to rise; after setting delay to zero workers drain and processing continues.

**Kill a worker via the control API.** Select a live worker ID from `/api/workers`; this Compose setup names them `worker-1`, `worker-2`, and `worker-3`:

```bash
curl -fsS -X POST http://localhost:18080/api/chaos/kill-worker \
  -H 'Content-Type: application/json' -d '{"workerId":"worker-2"}'
```

Omit a specific target by passing `{"workerId":null}` to let the coordinator pick a worker. Good: execution moves to `RECOVERING`, restores its last completed checkpoint, then returns to `RUNNING` with a higher generation. Check `GET /api/executions/current` and `GET http://localhost:18081/coord/state`.

**Kill the Compose worker directly.** This is a real container kill, like the chaos test:

```bash
docker compose -p aether kill worker-2
```

Good: the coordinator detects the missed heartbeat and recovers the execution. Bring the service back for future demos:

```bash
docker compose -p aether start worker-2
```

Wait for its health and registration before injecting another failure.

PowerShell can use the same `docker compose` commands. For JSON requests, use `Invoke-RestMethod -Method Post -Uri <url> -ContentType 'application/json' -Body '{"count":20,"lateByMs":180000}'` (substitute the matching JSON body above).

## 5. Recovery troubleshooting

First inspect the execution, coordinator, workers, and logs:

```bash
curl -fsS http://localhost:18080/api/executions/current
curl -fsS http://localhost:18081/coord/state
docker compose -p aether ps
docker compose -p aether logs --tail 150 coordinator worker-1 worker-2 worker-3
```

- **Execution stays `RECOVERING`:** recovery waits for workers to heartbeat and report `RUNNING` with the new generation. Check `/coord/state` for live workers; check worker logs and health. Start a stopped worker using `docker compose -p aether start worker-1` (use its actual service name). Check coordinator logs for failed assignments or checkpoint restore errors. Do not keep killing workers during recovery.
- **No `COMPLETED` checkpoint:** checkpoints run every 10 seconds while execution is `RUNNING`; the first checkpoint is checkpoint 0. Query `GET /api/executions/{id}/checkpoints?limit=20`. Only `COMPLETED` checkpoints can be restored; a pending checkpoint may become `ABORTED` after 30 seconds. If checkpoint 0 is also absent, inspect coordinator, PostgreSQL, and MinIO initialization/logs before restarting the demo pipeline.
- **MinIO checkpoint bucket missing:** the required bucket is `aether-checkpoints`. Check `docker compose -p aether logs minio minio-init`, then rerun its idempotent initializer after MinIO is healthy:

  ```bash
  docker compose -p aether run --rm minio-init
  ```

  Check the MinIO console at <http://localhost:19001>. Default local credentials are `aether` / `aether-secret` unless environment variables override them.
- **Kafka topic missing:** the topic is `truck-events` (6 partitions). Check `docker compose -p aether logs kafka kafka-init`, then rerun the topic initializer:

  ```bash
  docker compose -p aether up kafka-init
  ```

  Wait for `kafka-init` to exit successfully before starting an execution. Kafka auto-topic creation is disabled.

## 6. Deploy and roll back

On a push to `main`, CD builds and publishes engine, simulator, control API, and web images tagged with the commit SHA, then deploys that SHA on the self-hosted `aether-deploy` runner. The workflow ignores documentation-only changes; a docs-only push does not deploy. The runner needs Git Bash, Docker Desktop/Compose, GHCR access, and the existing `aether` Compose stack.

To run CD manually, open **Actions → cd → Run workflow** on `main`. Leave `sha` blank to deploy the current `main` commit, or enter a previous published image SHA to roll back. That SHA must have all four images in GHCR.

Manual deploy and verification from Git Bash at the repository root:

```bash
bash infra/deploy/deploy.sh <full-commit-sha>
bash infra/deploy/smoke-deploy.sh <full-commit-sha>
```

The deploy script pulls the tagged images and updates the existing stack with project `aether`; it waits for healthy services and records the successful SHA in `infra/deploy/.last-deployed`. To roll back by hand, run the same two commands with the earlier published SHA. If GHCR denies pulls, check package visibility or Docker authentication; never put a token in the repository.

## 7. Public link

For a temporary public demo link, install and authenticate `cloudflared` on the demo PC, keep the stack running, then run:

```bash
cloudflared tunnel --url http://localhost:8088
```

Share the HTTPS URL printed by the command. The quick-tunnel link changes every restart; keep the terminal open for the demo. Anyone with the link can reach the public UI/API; do not expose sensitive data.

## 8. Monitoring profile

Start the local stack with the monitoring profile enabled:

```bash
docker compose -p aether --profile monitoring up --build -d
```

Prometheus is at <http://localhost:19090>; Grafana is at <http://localhost:13000>. Grafana's local default password is `admin`; anonymous viewer access is enabled. The profile adds monitoring containers to the local stack and can be stopped with the normal `docker compose -p aether stop` command.

## 9. Reset everything

This permanently deletes the named Kafka, PostgreSQL, and MinIO volumes, including Kafka events, saved pipelines/executions/results/checkpoints, and MinIO checkpoint objects:

```bash
docker compose -p aether down -v
```

Use this only when a clean demo environment is intended. Rebuild and initialize the stack afterward with section 1. This does not delete images from Docker's image cache.

## 10. Known limits

- This is a single-coordinator demo stack with one local Kafka broker and replication factor 1. It is not a highly available production deployment.
- Worker-loss recovery restores the latest `COMPLETED` checkpoint; uncheckpointed work may be replayed. Expect a visible recovery pause after a worker failure.
- The demo has three worker services and uses the `truck-events` topic with six partitions. Changing container counts or removing the topic breaks the expected demo behavior.
- The cloudflared quick tunnel is temporary and its URL changes on restart.
- `/api/health` is process health only. Use `/api/ready` and the coordinator/worker state before the demo.


