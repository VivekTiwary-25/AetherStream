# AetherStream presentation outline

12 slides. Pair with `DEMO.md`. Read numbers off the screen. Do not push to `main` during the slot.

## 1. Title and what will be shown

- AetherStream: a fault-tolerant stream engine, shown as a truck city.
- Live page: city, engine panels, pipeline editor, chaos, compare, footer commit.
- Evidence beside the page: Compose, Grafana, one CD push, unit / Testcontainers / e2e / kind / Terraform logs.
- Not on the page in this checkout: Operations, City scenarios, Tour overlay. Those are API calls or a narrated walk.

**Show live:** Title, then `http://localhost:8088`. Confirm no **MOCK DATA** badge.
**Speaker notes:** Say what is on the page and what is a log. A workflow file is not a green run.

## 2. The failure modes the demo hits

- Events arrive late or repeated. A closed window becomes a `LATE` dead letter.
- A slow sink fills a queue of 1000 and the upstream stage blocks.
- A worker can halt. Recovery uses the latest completed checkpoint and a new generation.
- PostgreSQL upserts are the only effectively exactly-once sink. Input replay is at-least-once.

**Show live:** Contract 02 section 4 and contract 03 sections 5-6, one sentence each. Do not read them aloud.
**Speaker notes:** Name the four failures. Do not claim Flink parity.

## 3. Architecture in one picture

- Simulator emits `truck.position` and `truck.delivery` to Kafka topic `truck-events`, 6 partitions, key = truck id.
- Compiler plans the graph. Engine and reference share `io.aetherstream.compiler.ops`.
- Coordinator assigns partitions. Three workers. Checkpoints go to MinIO. Results go to PostgreSQL.
- Control API on 18080. Web nginx on 8088 proxies `/api` and `/ws`.

**Show live:** `docker compose ps`, then the module folders or `docs/contracts/README.md`.
**Speaker notes:** Compose on this machine is the demo. kind and Terraform are separate CI proofs.

## 4. City and live panels

- **AETHER CITY**, moving trucks, `events/s`, particle stream into the engine.
- **Speed windows**, **Deliveries / min**, **Alerts**, **Workers**.
- Stuck rule: truck-007, average speed under 1 for 10 one-minute windows. Message `truck-007 has not moved for 10 min` only if that row is visible.
- Footer **Deployed version:** is the first 7 characters of `GET /api/version`.

**Show live:** The page. Hover a truck for **Speed**, **Route**, **Partition**.
**Speaker notes:** Panels are engine rows, not drawings. Screenshot fallback: `docs/ux/screenshots/desktop-02-app-fullpage-60s.png`.

## 5. Pipeline editor

- Click **Pipeline**. **Examples** lists **Broken logistics demo** and **Truck city: speed, deliveries, stuck trucks**.
- **Validate** on the broken example shows three errors and **Show me**. **Run** stays disabled.
- The good example prints `Valid - 11 operators, 6 partitions`.
- **Run** saves and starts, and stops any current execution.

**Show live:** That click path. If **Examples** 404s, the button is **Load demo**.
**Speaker notes:** Do not restart if you still need the stuck-truck alert on screen. Say **Run** is enabled and close with **Close pipeline editor**.

## 6. Chaos, backpressure, kill, recover

- **Send late events**, then **Dead letters** tab **LATE**.
- **Slow down output** → **200 ms per record**. Hint names the 1,000 bound. **Backpressured** is amber when a queue is at least 90% full.
- **Kill worker**, **Confirm kill**. Banner **Recovering from checkpoint #N**, then **Recovered**. Worker row **Dead** is red.
- Timeline: **Recovering from checkpoint #N (generation G → G+1)**.

**Show live:** Those clicks. If amber is late, do not wait a minute.
**Speaker notes:** Fallback log is ci-e2e run 44410772993: late events, backpressure, `worker-3 killed; recovered in 6s`. Screenshots: `desktop-04-recovering-viewport.png`, `desktop-recovered-banner.png`.

## 7. Compare with reference

- Button **Compare with reference**. Success heading is **N / N results match**.
- Comparison replays Kafka offsets from checkpoint 0 to the latest completed checkpoint.
- Live N is this click. e2e run 44410772993 printed `71 / 71`. Integration run 44395770221 printed 811/811. Do not mix them.

**Show live:** The button, then the heading.
**Speaker notes:** If the call fails, `curl -fsS -X POST http://localhost:18080/api/compare -H 'content-type: application/json' -d '{}'` or open those two runs.

## 8. Savepoint, rescale, scenarios

- Shell does not mount **Operations** or **City scenarios**.
- Commands: `POST /api/executions/{id}/savepoints`, `POST .../restore`, `POST .../rescale`, `POST /api/simulator/scenario`.
- Balanced rescale is HTTP 409 `does not need a rebalance`. That is the rule, not a missing route.
- `ci-e2e` prints `9-11 SKIP extended steps (E2E_EXTENDED=0)`. Do not call that a restore pass.
- Scenario labels from the API: **Rush hour**, **Breakdown**, **Surge**, **Reset**.

**Show live:** The curls in `DEMO.md`, or the buttons if a later build mounted them.
**Speaker notes:** Contract 04 does not list these routes. The controller and `infra/scripts/demo-e2e.sh` do.

## 9. One push: CI, GHCR, vivek-pc, smoke

- Do not push during the talk. A `cd` deploy restarts this stack.
- Open run 44438884629. Jobs **publish** and **deploy** both succeeded.
- Images: `ghcr.io/vivektiwary-25/aetherstream-engine`, `aetherstream-simulator`, `aetherstream-control-api`, `aetherstream-web`, SHA `d1714e34e04fa1ed5feba43dfb959342be8d92a6`.
- Deploy runner name `vivek-pc-deploy`, label `aether-deploy`. Smoke: `PASS: health is UP and deployed commit is d1714e34e04fa1ed5feba43dfb959342be8d92a6`.

**Show live:** That Actions run, then the footer. They match only if `/api/version` still returns that SHA.
**Speaker notes:** A task-branch `cd` run only lints. `ci-ui-e2e` is `continue-on-error: true`.

## 10. Containers, Grafana, and the test logs

- `docker compose ps` and `docker compose images`. Revision label on the control-api container.
- Grafana `http://localhost:13000`, dashboard **AetherStream engine**. Profile `monitoring`. Empty panels stay empty.
- Unit: run 44439772370, `./gradlew build`.
- Testcontainers: run 44395770221, `./gradlew :tests:integrationTest`.
- e2e: run 44410772993, `bash infra/scripts/demo-e2e.sh`.
- kind: run 44398295492, `kubectl get pods -A -o wide`. Terraform: run 44395471523, `Plan: 24 to add, 0 to change, 0 to destroy.`

**Show live:** Compose, Grafana, then two of those runs. Do not start kind or Terraform here.
**Speaker notes:** Local `:local` images are not the GHCR tags. Terraform uses the Docker provider, not a cloud account.

## 11. Limits, said out loud

- One coordinator. No multi-active high availability.
- Tumbling windows only. Key-by is `key` or `payload.truckId`.
- Exactly-once claim stops at the PostgreSQL upserts.
- Savepoint restore and rescale are implemented but are not in the current green e2e path.
- Tour, ops, and scenario panels exist as components and are not in `AppShell`.

**Show live:** This list, next to the page.
**Speaker notes:** Offer the Q&A in `DEMO.md` rather than a new claim.

## 12. Close

- What ran on this machine: city, panels, editor, chaos, compare, Compose, Grafana.
- What a push already proved: GHCR publish, deploy on `vivek-pc-deploy`, smoke against `/api/version`.
- What CI proved separately: unit, Testcontainers 811/811, e2e 71/71, kind recovery, Terraform apply and destroy.
- Questions: use the short answers in `DEMO.md`.

**Show live:** Footer, then the CD run, then stop.
**Speaker notes:** If a click failed, name the fallback you used. Do not upgrade a screenshot or a skip line into a pass.
