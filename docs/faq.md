# Examiner questions

Short answers for an external examiner. The rules the engine must follow are in `docs/contracts/`. `docs/architecture.md` is the design narrative. `docs/testing.md` and `docs/ci-cd.md` say which checks exist, and a workflow file does not by itself prove that a run passed.

Where those documents still say a feature is only planned, but the code on this tree already implements it, the answer follows the code and says so. Where something is not in the tree, the answer says **planned**. This page does not report throughput, latency, or a green Actions run.

## Engine

### Why not just use Flink?

Apache Flink is the research reference for event time, watermarks, and checkpoint recovery. It is not the runtime. A UI wrapped around Flink would miss the boundary in `handoff.md` section 5: our own compiler, workers, assignment, and recovery protocol. Kafka, Postgres, and MinIO store and replay data; they do not run the operators or choose which checkpoint is safe to restore. That decision is the coordinator's (`docs/architecture.md` section 1).

### What does exactly-once mean here, and where does it stop?

Contract 03 section 5, restated in `docs/architecture.md` section 8, splits the claim into three parts. Input is replayed at-least-once: after a failure the worker seeks to the checkpoint offset and may process later records more than once. Operator state is exactly-once for inputs covered by a completed checkpoint, because state and source offsets come from that same cut. Postgres window, alert, and dead-letter rows are effectively exactly-once only because those outputs are deterministic and upserted on a primary key. No other sink gets the claim, an incomplete checkpoint is not a restore point, and this is not a general transaction across Kafka and Postgres.

### Why are watermarks per partition?

A watermark is the highest event time seen on one Kafka partition, minus `outOfOrdernessMs`. It is a progress policy for that partition, not a proof that no older event will arrive, and there is no minimum across partitions. Windows, dedup, and rule streaks read only that partition's log, so which worker owns it does not change the result. An idle partition is not treated as caught up, which is why a key that is not the Kafka key is rejected: a shuffle would make a per-partition cut incomplete (contract 02 section 4).

### What happens to late events?

A record is late when its window has already closed under the watermark from before that record. It becomes one dead letter of kind `LATE`, from the first window node in node-id order, and it is not added, does not rewrite a fired result, and does not move the watermark. Invalid records are a different dead letter, kind `REJECTED`, and they stop before the watermark too. The demo allows 5000 ms of lateness on a 60-second tumbling window; an older event after that is a dead letter, not a correction.

### How does dedup work?

Inside one key and one still-open window, an `eventId` counts at most once, because the window state keeps the set of ids it has absorbed. A second copy in that open window is ignored and is not a dead letter. A copy that arrives after the window has fired is judged by the late-event rule, so it becomes a `LATE` dead letter rather than a silent merge into a fired row (contract 01 section 3 and contract 02 section 4).

### What does a checkpoint contain?

A completed checkpoint is a consistent cut, not a random dump of worker memory. Each worker file in MinIO holds the generation and, per partition, the next offset not yet queued plus the operator snapshot: window aggregates, absorbed event ids, rule streaks, and `maxTs`. The manifest names the execution, checkpoint id, generation, pipeline id and version, protocol version, and each partition's next offset and worker-file key. Checkpoint 0 is written at start, already completed, with empty state and the topic's end or beginning offsets. Kafka remains the replay log; the checkpoint is not a second copy of the input (contract 03 section 4).

### Why is the manifest written last?

Restore needs every partition's next offset and the object key for state after exactly the records before that offset, and those facts are not known until every partition has acked. The coordinator uploads `manifest.json` only then, and sets `checkpoints.state` to `COMPLETED` only after that upload succeeds. Writing the manifest earlier could point a restore at a cut where some partition had not flushed outputs; marking `COMPLETED` before the upload could leave a done row with no manifest. Pending and aborted checkpoints are never restored, even if some worker files already exist.

### What does generation fencing prevent?

The generation on the `executions` row is a fencing token that stops a worker which no longer owns the execution from committing output or completing a checkpoint. Every reassignment bumps it first, then sends `CANCEL` for the old generation and `ASSIGN` for the new one; a lower-generation command is ignored, and a checkpoint ack is accepted only if the generation is current. Each sink batch starts with `SELECT generation FROM executions WHERE id = ? FOR SHARE` and writes nothing if the value differs, and the bump takes that same row lock. Both the ignore rule and the lock are required, including after a chaos kill, which halts with no cleanup.

### What happens if the coordinator dies?

There is one active coordinator; decisions live in Postgres and checkpoint bytes live in MinIO. While it is down, no new checkpoint can complete and no recovery can be decided, though workers can keep processing until a decision is required, because commands arrive only on the heartbeat response. On restart it reads `RUNNING` or `RECOVERING` executions, waits one heartbeat timeout for workers to register, and runs the same recovery as a worker failure. High availability with a leader lock is **planned**, and a second active coordinator is refused because two assigners would break the fence; the restart path is an in-memory unit test, not a killed coordinator process.

### How does backpressure stay bounded?

Each worker has two queues, source-to-operator and operator-to-sink, each of capacity 1000. A full queue blocks the upstream stage, which is the backpressure: not a drop, and not an unbounded buffer. The heartbeat reports size and capacity, and `backpressured` is true when any queue is at least 90 percent full, which the UI must show as text plus colour. `SET_SINK_DELAY` fills those queues until the source stops polling and Kafka lag grows; setting the delay back to 0 drains them, which shows the bound rather than measuring capacity.

### How do rebalance and savepoints work?

Rebalance of the existing six partitions is a controlled pause in `Coordinator.java`, not interruption-free scaling and not a change of partition count: a new worker alive for 5 seconds, or `POST /coord/executions/{id}/rebalance`, triggers it only when the assignment is uneven, then the coordinator waits for the next completed checkpoint, bumps the generation, and reassigns round-robin, at most once per 30 seconds and never while already recovering. `docs/architecture.md` section 10 still says v1 only assigns on start and recovery; that sentence is behind this code, and online key migration remains **planned**. Savepoints are on the coordinator only: `POST /coord/executions/{id}/savepoint` copies a completed manifest to `savepoints/sp-<executionId>-<checkpointId>/manifest.json`, and a later start with `fromSavepoint` returns 409 `INCOMPATIBLE_SAVEPOINT` if pipeline id, protocol version, partition count, topic, or stateful operators differ. The browser API has no savepoint route, and `docs/architecture.md` plus `docs/testing.md` still call savepoints **planned** even though `SavepointTest.java` rejects incompatible restores. Image rollback still does not make an incompatible checkpoint readable.

### What happens when a worker dies?

Workers heartbeat every 1000 ms and are declared dead after 4000 ms of silence. If the dead worker owned partitions, pending checkpoints are aborted, the latest completed checkpoint is loaded, the generation is bumped, and all partitions are assigned round-robin to the workers still alive, which restore and seek to those offsets. Kafka is replayed from there, and the execution returns to `RUNNING` when every assignee reports `RUNNING` on the new generation. A restarted process gets a new worker id and does not resume the old partitions by itself; the chaos kill is a real halt with no ack.

### Why not a Kafka consumer group?

The source uses `KafkaConsumer.assign`, not a consumer group. A group rebalance would move partitions without a checkpoint and without a generation bump, which would break the fence. Ownership is the coordinator's decision, scoped to an execution generation, so v1 does not subscribe (`docs/architecture.md` section 5 and contract 03).

## Testing

### How do you know results are correct?

The workers and the reference executor call the same operator code, so a comparison checks placement and recovery, not two different functions. `POST /api/compare` reads Kafka from checkpoint 0 up to the latest completed checkpoint and requires every closed window row to match Postgres on `eventCount` and values within `1e-6`. The distributed check is `EngineMatchesReferenceIT`: three workers, real Kafka, Postgres, and MinIO, and a fixed seed-42 workload. The Compose script `infra/scripts/demo-e2e.sh` step 6 asks the same API, but a green `ci-e2e` workflow is not proof the demo passed, because that job is `continue-on-error`, and no benchmark numbers are stored in the repo.

### What is the reference executor?

`ReferenceExecutor` replays one offset range per partition in one process, using the same `PartitionPipeline` class the worker uses, and it does not talk to the coordinator. It is the trusted executor behind "Compare with reference", not the hot path. If worker placement changed the function, a replay with no workers would not match the distributed run over the same offsets. Unit tests are in `reference/src/test/java/io/aetherstream/reference/ReferenceExecutorTest.java`.

### What fault tests exist?

Stale-generation fencing against real Postgres is `WorkerAdaptersIT`: a write at the old generation does not insert or overwrite, and a generation bump waits for an in-flight `FOR SHARE` transaction. Incomplete-checkpoint rejection, coordinator restart, and worker-failure reassignment are unit tests in `CoordinatorTest` with a fake clock, not a killed process against MinIO. Compose crash recovery is `demo-e2e.sh` step 5; there is no `CrashRecoveryIT`, and `EngineMatchesReferenceIT` does not kill a worker because `EngineCluster` throws on `KILL`. Bounded backpressure is `TaskRuntimeTest`, and incompatible savepoint rejection is `SavepointTest`, which `docs/testing.md` does not yet list. Cross-organisation access has no test, because login is **planned**.

## DevOps

### Which CI workflows run, and what does each prove?

A workflow file is the design of a check, not proof that a run passed. Push to `main` or `task/**`, and pull requests to `main`, run `ci-java` (Gradle unit tests, no Testcontainers), `ci-web` (lint, Vitest, and build; push is limited to `web/**`), `ci-integration`, `ci-compose` (`smoke.sh`), `ci-images` (build, no push, revision label must equal the SHA), `ci-mock`, and `ci-e2e` (ShellCheck is blocking; the demo job is `continue-on-error`). `cd.yml` publishes and deploys only from this repository's `main`. Most push triggers ignore `docs/**`, so a docs-only commit does not rebuild Java or images; the table is in `docs/ci-cd.md`.

### How does a commit reach the running demo?

A task branch is `task/**`; its push runs CI and does not publish images. Review is not a YAML job: `docs/ci-cd.md` says a finished task is reviewed against the contracts and the CI run, then merged or rejected. Merge to `main` starts CI again, then `publish` pushes four GHCR images tagged with that commit SHA, and `deploy` on the self-hosted runner labelled `aether-deploy` pulls them and starts Compose. `smoke-deploy.sh` passes only when nginx on port 8088 reports health UP and `GET /api/version` returns that same SHA; this page does not claim a particular deploy succeeded.

### How are images tagged by SHA?

`publish` pushes `ghcr.io/vivektiwary-25/aetherstream-engine`, `aetherstream-simulator`, `aetherstream-control-api`, and `aetherstream-web`, each with the full commit SHA and a moving `main` tag. `latest` is not used, and deploy does not use the `main` tag: the overlay sets `AETHER_TAG` to the SHA and `pull_policy: always`. Java images receive `GIT_SHA` and the web image receives `VITE_GIT_SHA`, which `GET /api/version` must echo. `ci-images` builds the same Dockerfiles with `push: false` and checks that the OCI revision label equals `GITHUB_SHA`.

### How is the self-hosted runner kept from running untrusted work?

`cd.yml` has no `pull_request` trigger, `publish` runs only on a push to `main`, and `deploy` also requires `github.repository` to be `VivekTiwary-25/AetherStream`, so a fork cannot land on this runner by copying the workflow. The token starts at `contents: read`; only `publish` adds `packages: write`, and login uses `GITHUB_TOKEN` rather than a personal access token in the file. Images are pulled by commit SHA, not by the moving `main` tag, and there is no Actions environment reviewer in the YAML. The setting "Require approval for workflows from outside collaborators" is not a job in the file, so check that checkbox in repository settings, and do not put registry credentials in the repo.

### How does rollback work?

Rollback is deploy of an earlier published SHA, not a retag of `main`. Dispatch workflow `cd` from `main` with input `sha` set to a full commit that already has all four images, or on the demo PC run `infra/deploy/deploy.sh` and then `infra/deploy/smoke-deploy.sh` with that SHA. A blank dispatch input deploys the current `main` SHA, and `.last-deployed` is local runtime state, not a rollback mechanism. Image rollback does not undo a Postgres migration or make an incompatible checkpoint readable, and `cd.yml` has no migration-down job.

### What is the status of Kubernetes and Terraform?

Kubernetes and Terraform are **planned** and are not in this tree: there is no `infra/kubernetes/` directory and no Terraform file. v1 deploy is Docker Compose on the self-hosted runner, and Kubernetes would not implement the recovery protocol even if the manifests existed. An optional Compose `monitoring` profile lives under `infra/monitoring/`; that profile is not Kubernetes, and it is not the default demo path.

## UX

### Why a city?

The demonstration workload is a simulated logistics city so an examiner can point at a late event, a stuck truck, and a killed worker. Truck positions on screen come from simulator state over the WebSocket; window rows come from Postgres through the control API, so the city is context, not the result path. `docs/ux/design-thinking.md` rejected a city-only view because motion hides exact values. The panels stay authoritative, and the city must not be the only way to read status.

### What accessibility choices were made?

Contract 04 requires running, backpressured, checkpointing, recovering, paused or stopped, and failed to be shown as text plus colour, not colour alone, and recovering should name the checkpoint when that id is known. `docs/ux/usability-decisions.md` adds a keyboard path, visible focus, a truck list beside the canvas, reduced-motion support, confirmation before a worker kill, and WCAG AA contrast for normal text. The footer must show the commit from `GET /api/version`, and a `MOCK DATA` badge is required whenever `VITE_USE_MOCK` is `1`. These are design rules, not a completed user study; personas in `docs/ux/personas.md` are proto-personas.

## Team

### How was the work split?

Contract 00 assigns modules to lanes so two people do not own the same file: shared records are the contracts lane, the engine is lane 1, compiler and reference are lane 2, the control API is lane 3, the simulator is lane 4, the city view is lane 5, the application shell is lane 6, and integration tests are lane 8. Infra and docs were split the same way in later tasks. The files in `docs/contracts/` are the shared interface, Java records in `:common` match those JSON names, and each concern has its own workflow file. Written reviews are `docs/reviews/R01-ops-coordinator.md` through `R05-tests.md`; a verdict there is the finding at that time, so open the cited file before treating a failure as still open. Merge review is not a GitHub Actions job: `docs/ci-cd.md` says a finished `task/**` branch is reviewed against the contracts and the CI run, then merged or rejected, and only the merge to `main` starts publish and deploy.
