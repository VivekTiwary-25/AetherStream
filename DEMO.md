# AetherStream: examiner demo script (about 13 minutes)

**Status note for the presenter:** The dashboard shell at `web/src/app/shell/AppShell.tsx` shows the truck city, live panels, and a **Start demo pipeline** button. It does not mount the pipeline editor, chaos controls, compare panel, scenario buttons, or checkpoint timeline. Those components are in the tree. Do not click a control that is not on the page. The APIs and the Actions logs below are the evidence for those features. A contract, or a workflow file, is not a passing run.

## Before the examiner arrives

- Build and start the local stack: `docker compose up -d --build`.
- Optional metrics: `docker compose --profile monitoring up -d`. Grafana is [http://localhost:13000](http://localhost:13000), dashboard **AetherStream engine**. Prometheus is [http://localhost:19090](http://localhost:19090). A plain `docker compose up` does not start this profile. See `infra/monitoring/README.md`.
- Confirm services: `docker compose ps`. App services should show healthy; one-shot `kafka-init` and `minio-init` should show exited successfully, not running. If any service is unhealthy, use the recovery section below.
- Open the web UI at `http://localhost:8088` and check it loads. Keep a terminal ready at the repository root.
- Open these Actions logs before you start. Do not describe a run you have not opened.
  - [44395770221](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44395770221) (`ci-integration`): `EngineMatchesReferenceIT` logged 811 reference results, 811 engine results, 811 matched, and dead letters 25 and 25 equal.
  - [44398295492](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44398295492) (`ci-k8s`): step "Always dump pods and diagnostic logs" runs `kubectl get pods -A -o wide`.
  - [44395471523](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44395471523) (`ci-terraform`): the apply log prints the plan, then apply. There is no separate plan step in `.github/workflows/ci-terraform.yml`.
- Workflow files on `main` include `ci-java`, `ci-web`, `ci-integration`, `ci-images`, `ci-compose`, `ci-e2e`, `ci-k8s`, `ci-terraform`, `bench`, `ci-ui-e2e`, `ci-mock`, and `cd`. `ci-ui-e2e` is `continue-on-error: true`. `bench` publishes reports; do not quote a benchmark number from memory. `benchmarks/README.md` says the repo publishes no fabricated metrics.
- If an externally hosted demo has actually been deployed, open its real public URL in a second tab. Otherwise say: “This checkout has no configured public demo URL; I am showing the local Compose deployment.” Do not invent or expose a URL.
- Have a clean, authorized demo execution and enough time for the simulator's clock. At `SIM_TIME_SCALE=10`, one simulated minute takes about six real seconds. A stuck-truck alert needs roughly 90 seconds from simulator start: five simulated minutes before truck-007 stops, then ten one-minute windows for the rule.
- Explain that completion of a local command or the presence of a contract does not by itself prove a feature works.

## Timed presentation

### 0:00–1:00 — 0. Pre-flight and scope

**Click / say:** In the terminal run `docker compose ps`; point to healthy application services and successful init jobs. Show the Actions tab. Say: “This is a local Compose deployment. The workflows on main cover Java, web, integration tests, images, Compose smoke, a full-system demo, kind, Terraform, a benchmark task, and a self-hosted Compose deploy. I will open the logs I have, not a file name.”

**Audience sees:** Running container status and the repository's actual workflow list/logs.

**Proves:** Docker Compose, service health checks, and honest CI/CD evidence. CDD: container orchestration and continuous integration. It does not prove every workflow's latest run is green.

### 1:00–1:45 — 1. The city is the data source

**Click / say:** Open `http://localhost:8088`. Point at the animated truck city and the connected/live status if shown. Say: “The simulator generates truck-position and delivery events; Kafka transports them to the engine. The city is the event source made visible, not the processing engine.”

**Audience sees:** Truck scene, depot/routes, and the dashboard shell. If the city or live connection is unavailable, say so and use `/api/health` as the process-health check; do not substitute a mock view without labeling it.

**Proves:** Event-driven system framing and the logistics simulator. CDD: user-facing visualization and service integration.

### 1:45–3:00 — 2. Pipeline editor and validation **(in progress as a click)**

**Click / say:** The editor component is `web/src/app/editor/PipelineEditor.tsx`. It is not mounted in the shell, so do not claim these clicks. The example library is live: `curl -fsS http://localhost:18080/api/pipelines/examples`. The ids on main are `logistics-demo`, `speeding-alerts`, `fleet-activity`, and `broken-for-demo`. Say: “The compiler explains a bad graph in plain English. The click-through is not on this page. `broken-for-demo` is the teaching graph; I will not pretend the shell loaded it.”

**Audience sees:** The examples JSON, or the validation contract at `docs/contracts/02-pipeline-and-semantics.md` if the API is down. The current dashboard's **Start demo pipeline** button starts the predefined pipeline when there is no current execution. It is not a graph editor.

**Proves:** The example API and the intended compiler check. CDD: fail-fast validation. This is not live editor evidence.

### 3:00–4:15 — 3. Live results and stuck-truck rule

**Click / say:** On the dashboard, click **Start demo pipeline** if no execution is running. Point to **Speed windows**, **Deliveries / min**, **Alerts**, **Dead letters**, and **Workers**. Say: “Speed is grouped by truck into one-minute event-time windows; deliveries are shown per completed simulation minute. The stuck rule should alert after truck-007 has been stopped long enough.”

**Audience sees:** The live panels populate only if the API, simulator, execution, and WebSocket are all connected. Allow at least about 90 seconds from simulator start for the contract's truck-007 scenario. If data or the alert is missing, state that plainly; do not describe empty panels as successful processing.

**Proves:** Event-time aggregation, results/alerts, and operations visibility when populated. CDD: observability and domain rules.

### 4:15–5:15 — 4. Late events and bounded backpressure **(in progress as a click)**

**Click / say:** `web/src/app/chaos/ChaosPanel.tsx` has **Send late events** and **Slow output**. It is not mounted. Do not claim those clicks. `infra/scripts/demo-e2e.sh`, run by `.github/workflows/ci-e2e.yml`, posts `/api/chaos/late-events` and `/api/chaos/slow-sink`. Say: “The queues have a fixed capacity of 1,000 items; a full downstream queue blocks its upstream stage. The shell does not expose the buttons. The Compose demo script does call the API.”

**Audience sees:** The chaos contract in `docs/contracts/02-pipeline-and-semantics.md` and `docs/contracts/03-coordinator-worker-protocol.md`, or a real `ci-e2e` log if you have opened one. Do not invent a pass time.

**Proves:** Intended late-data policy and bounded buffering. CDD: controlled failure/load demonstration. Current page evidence is not a completed click-through.

### 5:15–6:45 — 5. Kill worker, kind pods, and recover

**Click / say:** The kill button is not mounted. Do not fake it. Open [run 44398295492](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44398295492). Point at the step "Always dump pods and diagnostic logs" and the command `kubectl get pods -A -o wide`. The same job logged `Recovered from checkpoint >= 1 with generation > 1 within 60s` before that dump. That sentence is the script's deadline text, not a stopwatch reading. Say: “kind deleted pod `worker-1` and required the execution to come back RUNNING at a newer generation, restored from at least the recorded checkpoint. The pod table is the cluster evidence. Compose recovery is a different path: `infra/scripts/demo-e2e.sh` runs `docker compose kill` on a live worker service.”

**Audience sees:** The kind log, including `kubectl get pods -A -o wide`, and the dashboard's execution banner if a local execution is actually recovering. Worker pod names in kind are `worker-0`, `worker-1`, and `worker-2`. Compose services are `worker-1`, `worker-2`, and `worker-3`.

**Proves:** Checkpoint recovery in the kind CI log, and the intended generation fence. CDD: fault tolerance. Do not claim exactly-once behavior outside the documented PostgreSQL sink path.

### 6:45–7:45 — 6. Compare with the reference executor

**Click / say:** `web/src/app/chaos/ComparePanel.tsx` is not mounted. The API is `curl -fsS -X POST http://localhost:18080/api/compare -H 'content-type: application/json' -d '{}'`. Read the JSON it returns. Do not announce a match count you did not just read. For a cited run, open [44395770221](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44395770221) and point at `EngineMatchesReferenceIT`: 811 reference results, 811 engine results, 811 matched, dead letters 25 and 25 equal. Say: “That number is that log. A live call can return a different count. The reference executor uses the same operator semantics and replays records through the latest completed checkpoint.”

**Audience sees:** Either the live JSON or that integration log. The checkpoint timeline component is `web/src/app/timeline/CheckpointTimeline.tsx`. It is not mounted, so do not point at a timeline on the page.

**Proves:** A real comparison log, and a live comparison only if the call succeeds. CDD: testable correctness.

### 7:45–8:45 — 7. Terraform plan/apply log and the other workflows

**Click / say:** Open [run 44395471523](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44395471523). In the apply step, point at `Plan: 24 to add, 0 to change, 0 to destroy.` and `Apply complete! Resources: 24 added, 0 changed, 0 destroyed.` In the destroy step, point at `Plan: 0 to add, 0 to change, 24 to destroy.` and `Destroy complete! Resources: 24 destroyed.` Say: “CI apply prints the plan. There is no step named plan. This Terraform configuration uses the Docker provider. It is not a cloud account. Compose on the self-hosted runner is still the deploy path in `cd.yml`.”

**Audience sees:** That apply/destroy log. Also name `ci-e2e` (`bash infra/scripts/demo-e2e.sh`) and `bench` (`./gradlew :tests:benchmarkTest`) if asked. Do not quote a benchmark figure. Do not call `ci-ui-e2e` green from the workflow conclusion alone; that job is `continue-on-error: true`.

**Proves:** Infrastructure-as-code apply and destroy for this demo, from one cited log. CDD: IaC and CI. It does not prove a cloud deployment.

### 8:45–9:30 — 8. Grafana and scenario buttons

**Click / say:** If the monitoring profile is up, open [http://localhost:13000](http://localhost:13000) and the **AetherStream engine** dashboard. Otherwise run `docker compose --profile monitoring up -d` only if Docker is healthy and you have the minute. Say: “Prometheus scrapes coordinator metrics. The dashboard is provisioned from `infra/monitoring/grafana/dashboards/aetherstream.json`. Empty panels mean the metrics are not there yet.”

Then show scenario buttons as an API, not as a click on this page. `web/src/app/scenarios/ScenarioPanel.tsx` has four buttons: Rush hour, Breakdown, Surge, and Reset. The shell does not mount that panel. Run:

```sh
curl -fsS http://localhost:18080/api/scenarios
curl -fsS -X POST http://localhost:18080/api/simulator/scenario \
  -H 'content-type: application/json' \
  -d '{"name":"rush-hour"}'
```

Say: “Those four names are what `GET /api/scenarios` returns. Breakdown accepts an optional `truckId`. I will not claim the buttons are on the dashboard.”

**Audience sees:** The Grafana dashboard if the profile started, and the scenario JSON. If Grafana is down, say so.

**Proves:** The optional metrics profile and the scenario API. CDD: observability and a controllable simulator. Not a mounted button click.

### 9:30–10:30 — 9. Savepoint, restore, and rescale

**Click / say:** These are API calls. There is no savepoint button on the page. After **Start demo pipeline**, read the execution id, then:

```sh
curl -fsS -X POST "http://localhost:18080/api/executions/$ID/savepoints"
curl -fsS http://localhost:18080/api/savepoints
curl -fsS -X POST "http://localhost:18080/api/executions/$ID/restore" \
  -H 'content-type: application/json' \
  -d "{\"savepointId\":\"$SP\",\"idempotencyKey\":\"demo-restore-1\"}"
```

Say: “A savepoint waits for an aligned checkpoint and keeps that manifest. Restore stops this execution and starts a new one from that savepoint. An incompatible pipeline, protocol, partition count, topic, or operator set is rejected. The same idempotency key returns the execution already created.”

For rescale:

```sh
curl -fsS -X POST "http://localhost:18080/api/executions/$ID/rescale"
```

Say: “Rescale is a controlled pause at a checkpoint, then reassignment. It is not online migration. On a balanced three-worker demo this call is expected to return HTTP 409, because nothing is unbalanced. That refusal is the feature, not a missing route. It runs when a live worker owns no partitions, or the busiest and quietest live workers differ by at least two partitions, and not again within 30 seconds.”

**Audience sees:** The JSON from those calls, including a 409 body if the assignment is balanced. Do not describe a 409 as success, and do not describe it as an absent endpoint.

**Proves:** Savepoint, restore, and the rescale pause rule. CDD: controlled restart and an honest scaling limit.

### 10:30–11:15 — 10. Docker images and revision identity

**Click / say:** In the terminal run:

```sh
docker compose ps
docker images | grep aether
docker inspect --format='{{index .Config.Labels "org.opencontainers.image.revision"}}' aether/engine:local
docker compose logs --tail=30 coordinator worker-1
```

Say: “The custom application images are built from this checkout. The revision label identifies the source revision when the build supplied one; `dev` is the Compose default.”

**Audience sees:** Current container status, locally built image tags, label output if present, and recent service logs. The `ci-images` workflow separately checks revision labels on its CI-tagged images; a local `:local` image may have a different label or no commit SHA.

**Proves:** Docker images, image metadata, and container logs. CDD: container build and runtime inspection.

### 11:15–12:00 — 11. Tests and their limits

**Click / say:** Show the `ci-java`, `ci-web`, and `ci-integration` Actions logs and their exact commands. Say: “`./gradlew :tests:integrationTest` uses Testcontainers. `WorkerAdaptersIT` checks the Kafka source and the Postgres sink, including generation fencing. The 811/811 line is run 44395770221 only.”

**Audience sees:** Real CI logs, plus the integration task definition. Run `./gradlew :tests:integrationTest` live only if time and Docker are available; do not imply CI ran it if it was run locally.

**Proves:** Unit/build checks, web tests, and one cited integration log. CDD: automated verification.

### 12:00–13:00 — 12. Questions

Use the short answers below. If asked to demonstrate a control that is not on the page, use the API or the cited log and say the shell does not mount it.

## Q&A cheat sheet

1. **Is this exactly once?** Input can be replayed after failure. The contract claims exactly-once operator state from a consistent checkpoint and effectively exactly-once deterministic upserts for PostgreSQL result tables only. Other sinks are not covered.
2. **What if the coordinator dies?** The specified baseline is one active coordinator. On restart it reloads execution state, waits for workers to re-register, then reconciles/reassigns. High availability with multiple active coordinators is not claimed. kind and Terraform do not add a second coordinator.
3. **Why per-partition watermarks?** Records are processed in each Kafka partition's offset order; per-partition watermarks make window firing independent of partition interleaving and worker ownership. An idle partition does not advance its watermark, so its open windows can remain open.
4. **How is backpressure bounded?** Each worker stage queue has capacity 1,000. A full downstream queue blocks the upstream stage; the queue cannot grow without bound.
5. **What happens to a late event?** Each window checks its partition watermark. An event for a closed window is sent to a `LATE` dead-letter stream and is not added to that window.
6. **How are duplicates handled?** The event ID is counted at most once within a keyed window. A repeat after its window has fired follows the late-event policy.
7. **How is a stale worker prevented from writing?** Assignment generations act as fencing tokens. Sink transactions check the current generation while holding a database row lock; an old generation aborts.
8. **What does checkpoint alignment mean here?** A barrier follows already queued records through source, operator, and sink stages. The sink flushes prior output before the worker uploads state/offsets; the coordinator completes a checkpoint only after all partition acknowledgements and manifest upload.
9. **Why compare against a reference executor?** Both paths use the same operator implementation. Replaying the checkpointed input range against a deterministic single-process executor provides an independent run of the distributed results. The cited integration log is 811/811 with dead letters 25/25 equal, in run 44395770221. A live `POST /api/compare` is a different count until you read it.
10. **Is this a production replacement for Flink?** No. It is an educational, deliberately scoped engine project. The contracts describe its intended semantics; the live implementation, tests, and operational guarantees must be judged by the evidence shown, not by a claim of feature parity.
11. **What is a savepoint?** A retained completed checkpoint, with a compatibility check on restore. It is not a branch of checkpoint history. The routes are `POST /api/executions/{id}/savepoints`, `GET /api/savepoints`, and `POST /api/executions/{id}/restore`.
12. **Does rescale scale without stopping?** No. It pauses at a completed checkpoint, bumps the generation, and reassigns the same six partitions. A balanced assignment is refused. Online migration is not implemented.
13. **Where are the scenario buttons?** In `web/src/app/scenarios/ScenarioPanel.tsx`: Rush hour, Breakdown, Surge, Reset. They are not on the dashboard shell. `GET /api/scenarios` and `POST /api/simulator/scenario` are the live path.

## If something goes wrong during the demo

- **A Compose service is unhealthy:** run `docker compose ps -a` and `docker compose logs --tail=100 <service>`. For a local disposable demo stack, restart only that service with `docker compose restart <service>`, then re-check `docker compose ps`. If its dependency or initialization state is broken, preserve useful logs before deciding whether to rebuild/recreate the stack.
- **The page loads but has no live data:** check `http://localhost:18080/api/ready`, then inspect `control-api`, `coordinator`, `simulator`, and worker logs. A process health response alone does not prove all dependencies are ready.
- **The execution or data looks stale:** do not silently claim success. Check execution/worker state and WebSocket connectivity, then use **Start demo pipeline** only when no execution is running. A new run may stop the prior one; avoid repeated clicks.
- **Reference comparison is not on the page:** call `POST /api/compare` or open run 44395770221. Do not invent `N / N results match` for the live call.
- **A worker kill or late/slow-sink button is not on the page:** use the kind log, or explain `infra/scripts/demo-e2e.sh`. Do not edit page state to simulate a click.
- **Rescale returns 409:** on a balanced three-worker assignment that is the expected refusal. Read the error body. Do not keep retrying as if the route were down.
- **Savepoint or restore fails:** read the JSON error. An incompatible savepoint is a rejection, not a timeout to hide. Do not reuse an idempotency key if you wanted a second restore.
- **Grafana is empty or absent:** the monitoring profile is optional. Say it was not started, or that metrics are not exposed yet. Do not invent a graph.
- **A GitHub Actions run is red or missing:** open its actual logs. If there is no applicable run, explain the workflow trigger/path filter. Never describe a missing log as green. `ci-ui-e2e` can look green while the browser job failed.
- **The local stack cannot be restored quickly:** switch to the three cited Actions logs and the contract documents, label them as CI/specification evidence, and finish without claiming a live recovery.
