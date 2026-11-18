# Demo-day operations runbook

Run these commands from the repository root. On the demo PC, use Docker Desktop with its Linux engine running and Git Bash for the Bash commands. The PowerShell examples use native PowerShell commands where quoting or variables differ. Local Compose commands deliberately use project `aether`, the same project name used by CD; do not start a second project under another name.

## 1. Start and stop the stack

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
docker compose -p aether up -d worker-2
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

- **Execution stays `RECOVERING`:** recovery waits for workers to heartbeat and report `RUNNING` with the new generation. Check `/coord/state` for live workers; check worker logs and health. Start a stopped worker using `docker compose -p aether up -d worker-1` (use its actual service name). Check coordinator logs for failed assignments or checkpoint restore errors. Do not keep killing workers during recovery.
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
