# AetherStream

AetherStream is a small, home-made version of Apache Flink. You draw a data pipeline (source, filter, key-by,
window, rule, sink) in the browser, the compiler checks it and turns it into an execution plan, and a coordinator
spreads that plan over three workers. The workers read events from Kafka, keep state in event-time windows, write
results to PostgreSQL, and save consistent checkpoints to MinIO (S3) so that a crashed worker can be replaced
without losing or double-counting results.

To make this easy to see, the UI is a game-like truck city. Delivery trucks drive around a 2 km x 2 km map and every
position and delivery is an event. A glowing stream carries those events into the engine, and live panels show speed
per truck per minute, deliveries per minute, alerts (like a stuck truck), backpressure and recovery. You can break
things on purpose (late events, a slow output, killing a worker) and then press **Compare with reference** to prove the
answers are still correct.

> Status note: this README describes the target system. Parts that are not on `main` yet are marked **(in progress)**.

## Features

| Feature | What it means | Status |
|---|---|---|
| Pipeline compiler with clear errors | Validates the pipeline graph and config, collects all errors, and explains each one in plain English with how to fix it (e.g. "Window 'speed1m' needs a Key-by before it"). Error codes in [contract 02](docs/contracts/02-pipeline-and-semantics.md#3-compiler-ioaetherstreamcompilerpipelinecompiler) | on main |
| Per-partition event-time windows | Tumbling windows on event time; one watermark per Kafka partition, so results do not depend on how partitions interleave | on main (`compiler/.../ops`) |
| Late events | Events for an already-closed window go to the dead-letter table as `LATE` with a reason | on main (ops) |
| Deduplication | The same `eventId` counts once per key-window | on main (ops) |
| Rules / alerts | e.g. "truck-007 has not moved for 10 min" after N quiet windows in a row | on main (ops) |
| Invalid input | Bad JSON or broken events go to dead letters as `REJECTED` | on main (ops) |
| 3 workers | Coordinator assigns the 6 Kafka partitions round-robin to 3 workers | coordinator on main; worker runtime (in progress) |
| Bounded queues + backpressure | Each worker is source -> queue(1000) -> operators -> queue(1000) -> sink. A full queue blocks the stage before it; the UI turns amber | (in progress) |
| Aligned barrier checkpoints to MinIO | Chandy-Lamport style barriers; state + Kafka offsets saved together; a manifest marks a checkpoint COMPLETED | coordinator + S3 store on main; worker side (in progress) |
| Kill-worker recovery with generation fencing | A dead worker is detected in 4 s; all partitions restart from the last completed checkpoint with a new generation; old workers can no longer write | coordinator on main; end-to-end (in progress) |
| Compare with reference | A single-process reference executor replays the same Kafka records and checks every engine result in Postgres | (in progress) |
| CI/CD with GitHub Actions | One workflow per concern (Java, web, images, compose, integration, e2e, deploy) | ci-java, ci-web, ci-images, ci-compose on main; the rest (in progress) |
| Custom Docker images | Multi-stage Dockerfiles in `infra/docker/` with an `org.opencontainers.image.revision` label = commit SHA | on main |
| Self-hosted deploy | `cd.yml` pushes images to GHCR and deploys on a self-hosted runner (label `aether-deploy`) | (in progress) |

## Architecture

A full write-up with diagrams will live in [docs/architecture.md](docs/architecture.md) **(in progress)**. Short version:

```
 simulator ──► Kafka topic "truck-events" (6 partitions, key = truckId)
                        │
                        ▼
   coordinator ◄── heartbeats ── worker-1 / worker-2 / worker-3
   (assignment,                  source ─► [queue 1000] ─► operators ─► [queue 1000] ─► sink
    checkpoints,                        │                                        │
    recovery,                           └── checkpoint state ─► MinIO (S3)       └─► PostgreSQL (fenced upserts)
    generation)
        │
        ▼
   control-api (REST + WebSocket) ◄── web (nginx + React city UI, http://localhost:8088)
```

The exact rules are in the contracts:
[00 stack and layout](docs/contracts/00-stack-and-layout.md),
[01 event envelope and simulator](docs/contracts/01-event-envelope-and-simulator.md),
[02 pipeline and semantics](docs/contracts/02-pipeline-and-semantics.md),
[03 coordinator-worker protocol](docs/contracts/03-coordinator-worker-protocol.md),
[04 control-api REST + WS](docs/contracts/04-control-api-rest-ws.md).
UX design notes are in [docs/ux/](docs/ux/README.md).

## Repository layout

| Path | What is in it |
|---|---|
| `common/` (`:common`) | Java records for every contract: envelope, pipeline, protocol, API DTOs (`io.aetherstream.common`) |
| `compiler/` (`:compiler`) | Pipeline validation and planning, plus the shared operator code in `io.aetherstream.compiler.ops` (used by both engine and reference) |
| `reference/` (`:reference`) | Deterministic single-process reference executor used by "Compare with reference" **(in progress)** |
| `engine/` (`:engine`) | Coordinator and worker runtime (two main classes) (`io.aetherstream.engine`) |
| `control-api/` (`:control-api`) | Spring Boot REST + WebSocket API the UI talks to (`io.aetherstream.api`) |
| `simulator/` (`:simulator`) | Truck-city event generator, plain Java + JDK HttpServer (`io.aetherstream.simulator`) |
| `tests/` (`:tests`) | Integration and fault tests with Testcontainers, task `integrationTest` **(in progress)** |
| `web/` | Vite + React + TypeScript app; `web/src/city/` (PixiJS city), `web/src/app/` (panels, editor) |
| `infra/docker/` | Dockerfiles for engine, simulator, control-api, web, and the nginx config |
| `infra/db/schema.sql` | PostgreSQL schema (loaded by the postgres container and by tests) |
| `infra/scripts/smoke.sh` | Smoke checks used by `ci-compose` |
| `docs/contracts/` | The interface contracts every module follows |
| `docs/ux/` | Personas, journeys, design system, usability decisions |
| `.github/workflows/` | GitHub Actions, one file per concern |

## Quick start

You need Docker (with Compose v2). Nothing else.

```bash
docker compose up -d --build
docker compose ps          # wait until everything is "healthy"
```

Then open **http://localhost:8088**.

> Today `docker-compose.yml` on main starts the infrastructure (Kafka, Postgres, MinIO and their init jobs). The app
> services (coordinator, 3 workers, simulator, control-api, web) are listed there as placeholders and are being added
> **(in progress)**.

### Ports

| Service | Host port | Container port | Notes |
|---|---|---|---|
| web (nginx + UI) | **8088** | 80 | proxies `/api` and `/ws` to control-api |
| control-api | 18080 | 8080 | REST under `/api`, WebSocket at `/ws` |
| coordinator | 18081 | 8081 | `/coord/health`, `/coord/state` |
| simulator | 18070 | 8070 | `/state` |
| worker-1..3 | - | 8090 | health only, not published |
| kafka | 19092 | 9092 | topic `truck-events`, 6 partitions |
| postgres | 15432 | 5432 | db/user `aether`, password `aether` (or `PG_PASSWORD` in `.env`) |
| minio | 19000 (API), 19001 (console) | 9000, 9001 | bucket `aether-checkpoints`, login `aether` / `aether-secret` |

Stop everything with `docker compose down` (add `-v` to also delete the data volumes).

## Running the tests

```bash
./gradlew build                       # compile + unit tests of every Java module (Java 21 toolchain)
./gradlew :tests:integrationTest      # integration + fault/recovery tests with Testcontainers (needs Docker) (in progress)

cd web
npm ci && npm test                    # web unit tests (Vitest)
npm run lint && npm run build         # what ci-web also runs
```

On Windows use `gradlew.bat` instead of `./gradlew`.

## CI/CD (GitHub Actions)

Every workflow runs on push to `main` and `task/**` and on pull requests to `main` (except `cd`, which runs on `main` only).

| Workflow | What it runs | What it proves | Status |
|---|---|---|---|
| `ci-java` | `./gradlew build`, uploads test reports | All Java modules compile and their unit tests pass (compiler errors, operator semantics, snapshot/restore, coordinator) | on main |
| `ci-web` | `npm ci`, `lint`, `test`, `build` in `web/` | The UI builds cleanly, lint is clean, and UI logic tests pass | on main |
| `ci-images` | Builds every Dockerfile in `infra/docker/` and smoke-runs them | Our custom images build and carry the commit SHA revision label | on main |
| `ci-compose` | `docker compose up -d`, waits for health, runs `infra/scripts/smoke.sh`, dumps logs | The whole stack starts together and is healthy | on main (infra only; app services in progress) |
| `ci-integration` | `./gradlew :tests:integrationTest` | End-to-end engine behaviour against real Kafka/Postgres/MinIO, including kill-and-recover | (in progress) |
| `ci-e2e` | Browser tests against the running stack | The demo flow works through the real UI | (in progress) |
| `cd` | Publish images to GHCR tagged with the commit SHA, deploy on the self-hosted runner `aether-deploy` | A commit on `main` reaches the running deployment automatically; the UI footer shows the new commit | (in progress) |

## Tech stack

| Area | Technology |
|---|---|
| Language / build | Java 21 (Temurin), Gradle 9.6 (Kotlin DSL, committed wrapper) |
| Messaging | Apache Kafka 4.3.2 (KRaft, single node), kafka-clients 4.3.2 |
| Database | PostgreSQL 17, JDBC 42.7, HikariCP 6.3 |
| Checkpoint storage | MinIO (S3 API), MinIO Java client 8.5 |
| API | Spring Boot 3.5 (REST + plain WebSocket) |
| JSON | Jackson 2 |
| Tests | JUnit 5, AssertJ, Testcontainers 2; Vitest + Testing Library for the web |
| Web | React 19, TypeScript 5.9, Vite 7, PixiJS 8 (city), @xyflow/react 12 (pipeline editor) |
| Containers | Docker, Docker Compose, multi-stage Dockerfiles, nginx 1.29 |
| CI/CD | GitHub Actions, GHCR, self-hosted runner |

## Team and course

Built as the team project for the **Collaborative Development and DevOps (CDD)** course. It is shown live at the
**CDD expo on 21 November 2026** (the simulator's clock even starts at 09:00 UTC on that day).
Project owner: Vivek Tiwary ([VivekTiwary-25](https://github.com/VivekTiwary-25)), with teammates.
The live demo script for the examiner is in [DEMO.md](DEMO.md).

## License

Proprietary. All rights reserved. See [LICENSE](LICENSE). The `swarm/` folder in particular is the author's
private tooling and may not be copied, reused or explained.
