# AetherStream: examiner demo script (11 minutes)

**Status note for the presenter:** This is the target end-to-end runbook mapped to the contracts. The current checkout has a truck-city/dashboard shell and a **Start demo pipeline** button, but the pipeline editor, chaos/compare controls are not wired into that shell. The `ci-integration.yml`, `ci-e2e.yml`, and `cd.yml` workflows are also absent. Those steps are explicitly marked **(in progress)** below. Do not claim a control or CI run happened unless you have verified it in the live environment. Use the available dashboard and CI logs as evidence; explain the rest as planned behavior.

## Before the examiner arrives

- Build and start the local stack: `docker compose up -d --build`.
- Confirm services: `docker compose ps`. App services should show healthy; one-shot `kafka-init` and `minio-init` should show exited successfully, not running. If any service is unhealthy, use the recovery section below.
- Open the web UI at `http://localhost:8088` and check it loads. Keep a terminal ready at the repository root.
- Open the repository's GitHub **Actions** tab. Have recent runs/logs for `ci-java`, `ci-web`, `ci-images`, and `ci-compose` ready. No `ci-integration`, `ci-e2e`, or `cd` workflow is present in this checkout.
- If an externally hosted demo has actually been deployed, open its real public URL in a second tab. Otherwise say: “This checkout has no configured public demo URL; I am showing the local Compose deployment.” Do not invent or expose a URL.
- Have a clean, authorized demo execution and enough time for the simulator's clock. At `SIM_TIME_SCALE=10`, one simulated minute takes about six real seconds. A stuck-truck alert needs roughly 90 seconds from simulator start: five simulated minutes before truck-007 stops, then ten one-minute windows for the rule.
- Explain that completion of a local command or the presence of a contract does not by itself prove a feature works.

## Timed presentation

### 0:00–1:00 — 0. Pre-flight and scope

**Click / say:** In the terminal run `docker compose ps`; point to healthy application services and successful init jobs. Show the Actions tab. Say: “This is a local Compose deployment. Four workflows are present: Java, web, image, and full Compose smoke checks. Integration CI, browser end-to-end CI, and self-hosted deployment are still in progress.”

**Audience sees:** Running container status and the repository's actual workflow list/logs.

**Proves:** Docker Compose, service health checks, and honest CI/CD evidence. CDD: container orchestration and continuous integration. It does not prove a deployment workflow exists.

### 1:00–1:45 — 1. The city is the data source

**Click / say:** Open `http://localhost:8088`. Point at the animated truck city and the connected/live status if shown. Say: “The simulator generates truck-position and delivery events; Kafka transports them to the engine. The city is the event source made visible, not the processing engine.”

**Audience sees:** Truck scene, depot/routes, and the dashboard shell. If the city or live connection is unavailable, say so and use `/api/health` as the process-health check; do not substitute a mock view without labeling it.

**Proves:** Event-driven system framing and the logistics simulator. CDD: user-facing visualization and service integration.

### 1:45–3:00 — 2. Pipeline editor and validation **(in progress)**

**Click / say:** The planned flow is to load the logistics demo, delete its `Key-by` node, choose **Validate**, show the compiler's plain-English “needs a Key-by before it” error, restore the node, validate again, and choose **Run**. Say: “The compiler is specified to explain the type mismatch before workers start.”

**Audience sees:** In this checkout, the editor/validate controls are not present in the dashboard shell. Do not claim these clicks succeeded. Show the validation contract at `docs/contracts/02-pipeline-and-semantics.md` §3, and state: “The editor-to-compiler demo flow is in progress.” The current dashboard's **Start demo pipeline** button starts the predefined pipeline when there is no current execution; it does not provide a graph editor.

**Proves:** Intended compiler validation and graph type checking. CDD: fail-fast validation and usable error feedback. This is contract evidence, not live UI evidence.

### 3:00–4:15 — 3. Live results and stuck-truck rule

**Click / say:** On the dashboard, click **Start demo pipeline** if no execution is running. Point to **Speed windows**, **Deliveries / min**, **Alerts**, **Dead letters**, and **Workers**. Say: “Speed is grouped by truck into one-minute event-time windows; deliveries are shown per completed simulation minute. The stuck rule should alert after truck-007 has been stopped long enough.”

**Audience sees:** The live panels populate only if the API, simulator, execution, and WebSocket are all connected. Allow at least about 90 seconds from simulator start for the contract's truck-007 scenario. If data or the alert is missing, state that plainly; do not describe empty panels as successful processing.

**Proves:** Event-time aggregation, results/alerts, and operations visibility when populated. CDD: observability and domain rules.

### 4:15–5:15 — 4. Late events and bounded backpressure **(in progress)**

**Click / say:** The planned controls are **Send late events** and **Slow output**. Trigger late events and point to `LATE` dead letters; enable a slow sink and point to queue depth/backpressure, then turn the delay off. Say: “The queues have a fixed capacity of 1,000 items; a full downstream queue blocks its upstream stage.”

**Audience sees:** The `ChaosPanel` has controls in the codebase, but they are not mounted in the current application shell. Do not claim to have triggered chaos from this UI. Show the queue and late-data rules in `docs/contracts/02-pipeline-and-semantics.md` and `docs/contracts/03-coordinator-worker-protocol.md`, and mark the integrated chaos flow **in progress**.

**Proves:** Intended late-data policy, bounded buffering, and backpressure. CDD: controlled failure/load demonstration. Current evidence is the contract, not a completed click-through.

### 5:15–6:45 — 5. Kill worker and recover **(in progress)**

**Click / say:** In the planned chaos controls, select the busiest worker and choose **Kill worker**. Point to the dead worker state, then the execution banner: **“Recovering from checkpoint #N”**. Say: “The coordinator should restore the latest completed checkpoint and increment the generation before reassignment; the old worker is fenced from writing.”

**Audience sees:** The dashboard has execution and worker status views, but the kill action is not mounted in the current shell. A live recovery is not demonstrated by this checkout's UI. Show the aligned checkpoint and generation-fencing specification in `docs/contracts/03-coordinator-worker-protocol.md` instead; call the wired failure-injection/recovery flow **in progress**.

**Proves:** Intended checkpoint recovery and stale-worker fencing. CDD: fault tolerance and recovery. Do not claim exactly-once behavior outside the documented PostgreSQL sink path or without failure-test evidence.

### 6:45–7:45 — 6. Compare with the reference executor **(in progress)**

**Click / say:** The intended action is **Compare with reference**. On success, show the returned `N / N results match` and say: “The reference executor uses the same operator semantics. It replays records from saved offsets through the latest completed checkpoint. PostgreSQL upserts make the supported sink output effectively exactly-once; the input itself can be replayed.”

**Audience sees:** The compare panel is not mounted in the current shell. The API/algorithm is specified in `docs/contracts/04-control-api-rest-ws.md` §Compare, but there is no live UI comparison to show here. Mark the integrated comparison **in progress**; do not invent a match count.

**Proves:** Intended deterministic reference validation and a clearly scoped output guarantee. CDD: testable correctness.

### 7:45–8:45 — 7. GitHub Actions and deployment status

**Click / say:** In the GitHub **Actions** tab, open a real run for each present workflow: `ci-java`, `ci-web`, `ci-images`, and `ci-compose`. Point to commands/logs and artifacts. Say: “These workflows prove the checks their jobs ran. The separate integration/recovery, browser E2E, and CD workflows are not present yet.”

**Audience sees:** Actual run status and logs, or no run if none exists. A tiny code change can create a branch and pull request to trigger applicable CI; do not make an unapproved change on a live deployment. Markdown-only changes are excluded from several workflows and may not trigger a run. There is no self-hosted deployment to show in this checkout: **CD is in progress**. The UI footer reads the API's `/api/version` commit value; show its current short revision, but do not claim it changed through a deployment.

**Proves:** GitHub Actions, reproducible build/test/image/Compose checks. CDD: CI/CD concepts, with continuous deployment explicitly not yet demonstrated.

### 8:45–9:30 — 8. Docker images and revision identity

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

### 9:30–10:15 — 9. Tests and their limits

**Click / say:** Show the `ci-java` and `ci-web` Actions logs and their exact commands. Say: “The repository also defines `./gradlew :tests:integrationTest`; it uses Testcontainers. The integration test suite is not yet wired to a dedicated CI workflow here, so I will not call it a passing CI check unless the log shows it ran.”

**Audience sees:** Real CI logs, plus the integration task definition and any existing test report. Run `./gradlew :tests:integrationTest` live only if time and Docker are available; do not imply CI ran it if it was run locally.

**Proves:** Unit/build checks and web tests in CI; distinguishes local integration coverage from automated CI evidence. CDD: automated verification.

### 10:15–11:00 — 10. Questions

Use the short answers below. If asked to demonstrate an **in progress** path, return to its contract and state the current UI/CI limitation directly.

## Q&A cheat sheet
1. **Is this exactly once?** Input can be replayed after failure. The contract claims exactly-once operator state from a consistent checkpoint and effectively exactly-once deterministic upserts for PostgreSQL result tables only. Other sinks are not covered.
2. **What if the coordinator dies?** The specified baseline is one active coordinator. On restart it reloads execution state, waits for workers to re-register, then reconciles/reassigns. High availability with multiple active coordinators is not claimed.
3. **Why per-partition watermarks?** Records are processed in each Kafka partition's offset order; per-partition watermarks make window firing independent of partition interleaving and worker ownership. An idle partition does not advance its watermark, so its open windows can remain open.
4. **How is backpressure bounded?** Each worker stage queue has capacity 1,000. A full downstream queue blocks the upstream stage; the queue cannot grow without bound.
5. **What happens to a late event?** Each window checks its partition watermark. An event for a closed window is sent to a `LATE` dead-letter stream and is not added to that window.
6. **How are duplicates handled?** The event ID is counted at most once within a keyed window. A repeat after its window has fired follows the late-event policy.
7. **How is a stale worker prevented from writing?** Assignment generations act as fencing tokens. Sink transactions check the current generation while holding a database row lock; an old generation aborts.
8. **What does checkpoint alignment mean here?** A barrier follows already queued records through source, operator, and sink stages. The sink flushes prior output before the worker uploads state/offsets; the coordinator completes a checkpoint only after all partition acknowledgements and manifest upload.
9. **Why compare against a reference executor?** Both paths use the same operator implementation. Replaying the checkpointed input range against a deterministic single-process executor provides an independent run of the distributed results.
10. **Is this a production replacement for Flink?** No. It is an educational, deliberately scoped engine project. The contracts describe its intended semantics; the live implementation, tests, and operational guarantees must be judged by the evidence shown, not by a claim of feature parity.

## If something goes wrong during the demo

- **A Compose service is unhealthy:** run `docker compose ps -a` and `docker compose logs --tail=100 <service>`. For a local disposable demo stack, restart only that service with `docker compose restart <service>`, then re-check `docker compose ps`. If its dependency or initialization state is broken, preserve useful logs before deciding whether to rebuild/recreate the stack.
- **The page loads but has no live data:** check `http://localhost:18080/api/ready`, then inspect `control-api`, `coordinator`, `simulator`, and worker logs. A process health response alone does not prove all dependencies are ready.
- **The execution or data looks stale:** do not silently claim success. Check execution/worker state and WebSocket connectivity, then use **Start demo pipeline** only when no execution is running. A new run may stop the prior one; avoid repeated clicks.
- **Reference comparison is unavailable:** the comparison UI is **(in progress)** in this checkout. Fall back to the API/contract and real CI logs; do not fabricate `N / N results match`.
- **A worker kill or late/slow-sink action is unavailable:** the integrated chaos controls are **(in progress)**. Explain the intended protocol and show its contract rather than simulating success by editing page state.
- **A GitHub Actions run is red or missing:** open its actual logs. If there is no applicable run, explain the workflow trigger/path filter. Fall back to the available real run artifacts; never describe a missing CD or integration workflow as green.
- **The local stack cannot be restored quickly:** switch to real, previously recorded Actions logs and contract documents, label them as CI/specification evidence, and finish the explanation without claiming a live recovery.
