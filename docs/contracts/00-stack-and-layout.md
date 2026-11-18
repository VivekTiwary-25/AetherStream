# Contract 00: stack, versions, repo layout, ports

Every task MUST follow docs/contracts/ exactly. Do not change any file in docs/contracts/ or swarm/.
If a contract looks wrong, implement it as written and say so in your result.

## Pinned stack (do not upgrade or swap)
| Thing | Version |
|---|---|
| Java | 21 (Temurin) — `java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }` |
| Gradle | 9.6.0 via the committed wrapper (`./gradlew`), Kotlin DSL (`*.gradle.kts`) |
| Spring Boot | 4.1.2 (only in `control-api`) |
| kafka-clients | 4.3.2 |
| Jackson (non-Spring modules) | com.fasterxml.jackson.core:jackson-databind 2.22.4 (+ jackson-datatype-jdk8) |
| PostgreSQL JDBC | 42.7.14 |
| MinIO Java client | io.minio:minio 8.5.17 (S3 API) |
| HikariCP | 6.3.0 |
| JUnit | 5.13.4 (junit-jupiter) + AssertJ 3.27.6 |
| Testcontainers | 2.0.5 (artifacts `org.testcontainers:testcontainers`, `testcontainers-junit-jupiter`, `testcontainers-kafka`, `testcontainers-postgresql`; use `GenericContainer` for MinIO). 2.x is required: 1.x cannot talk to Docker 29 |
| SLF4J / Logback | slf4j-api 2.0.17, logback-classic 1.5.18 |
| Node (CI only) | 22 LTS |
| React | 19.x, TypeScript 5.9.x, Vite 7.x |
| City rendering | PixiJS 8.x (web/city) |
| Pipeline editor graph | @xyflow/react 12.x |
| Docker images | `apache/kafka:4.3.2` (KRaft single node), `postgres:17-alpine`, `pgsty/minio:RELEASE.2026-08-04T00-00-00Z` (community MinIO, S3 API), `eclipse-temurin:21-jre` (runtime base), `node:22-alpine` (web build), `nginx:1.29-alpine` (web serve) |

Versions are kept in `gradle/libs.versions.toml`. If a pinned version cannot be resolved in CI, use the nearest
existing patch of the same minor and say so in your result.

## Gradle modules (settings.gradle.kts)
| Module path | Folder | Lane | What |
|---|---|---|---|
| `:common` | `common/` | contracts | Java records for every contract (envelope, pipeline, protocol, API DTOs). Package `io.aetherstream.common` |
| `:compiler` | `compiler/` | 2 | pipeline JSON validation + planning. Package `io.aetherstream.compiler` |
| `:reference` | `reference/` | 2 | deterministic single-process reference executor. Package `io.aetherstream.reference` |
| `:engine` | `engine/` | 1 | coordinator + worker runtime (two main classes). Package `io.aetherstream.engine` |
| `:control-api` | `control-api/` | 3 | Spring Boot REST + WebSocket. Package `io.aetherstream.api` |
| `:simulator` | `simulator/` | 4 | truck-city event generator (plain Java + JDK HttpServer). Package `io.aetherstream.simulator` |
| `:tests` | `tests/` | 8 | integration / fault tests (Testcontainers). Task `integrationTest` |

Operator semantics live ONCE, in `:compiler` (package `io.aetherstream.compiler.ops`) and are used by both
`:engine` and `:reference`. This is what guarantees engine and reference compute the same function; the engine adds
distribution, checkpoints and recovery around them.

Dependency direction: common <- compiler <- reference <- engine/control-api/tests; simulator depends on common only.

## Web
`web/` is one Vite + React + TS app (`web/package.json`). Lane 5 owns `web/src/city/`, lane 6 owns `web/src/app/`.
Shared API types: `web/src/api/types.ts` (mirror of contract 04) and client `web/src/api/client.ts`.
Dev mock server: `web/mock/` (Node, no deps beyond `ws`), started with `npm run mock`. The UI shows a visible
"MOCK DATA" badge whenever `VITE_USE_MOCK=1`. Production build (`npm run build`) must have `VITE_USE_MOCK` unset.

## Services, ports (docker compose, network `aether`)
| Service | Container port | Host port |
|---|---|---|
| kafka | 9092 (internal listener `kafka:9092`) | 19092 (external listener `localhost:19092`) |
| postgres | 5432 (db `aether`, user `aether`, password from `.env`, default `aether`) | 15432 |
| minio | 9000 API, 9001 console (bucket `aether-checkpoints`) | 19000, 19001 |
| coordinator | 8081 | 18081 |
| worker-1..3 | 8090 (health only) | - |
| simulator | 8070 | 18070 |
| control-api | 8080 | 18080 |
| web (nginx, proxies `/api` and `/ws` to control-api) | 80 | 8088 |

Environment variables (all services read these, with these defaults):
`KAFKA_BOOTSTRAP=kafka:9092`, `KAFKA_TOPIC=truck-events`, `PG_URL=jdbc:postgresql://postgres:5432/aether`,
`PG_USER=aether`, `PG_PASSWORD=aether`, `S3_ENDPOINT=http://minio:9000`, `S3_ACCESS_KEY=aether`,
`S3_SECRET_KEY=aether-secret`, `S3_BUCKET=aether-checkpoints`, `COORDINATOR_URL=http://coordinator:8081`,
`SIMULATOR_URL=http://simulator:8070`, `GIT_SHA=dev`, `WORKER_ID` (optional; generated if empty).

Kafka topic `truck-events`: 6 partitions, replication 1, key = truckId (UTF-8), value = envelope JSON (contract 01).
Created by the compose `kafka-init` one-shot service (and by tests).

## Database
Schema file: `infra/db/schema.sql` (owned by contracts; mounted into postgres `docker-entrypoint-initdb.d`; tests load it too).

## CI (one workflow file per concern, so lanes never edit the same file)
All run on `push` to `main` and `task/**`, and on `pull_request` to main.
| File | Job(s) | Command |
|---|---|---|
| `.github/workflows/ci-java.yml` | `java` | `./gradlew build` (compile + unit tests of every module), uploads test reports |
| `.github/workflows/ci-web.yml` | `web` | in `web/`: `npm ci && npm run lint && npm run test --if-present && npm run build` |
| `.github/workflows/ci-integration.yml` | `integration` | `./gradlew :tests:integrationTest` (Testcontainers), uploads reports |
| `.github/workflows/ci-compose.yml` | `compose` | builds all images, `docker compose up -d`, waits for health, runs `infra/scripts/smoke.sh`, dumps logs |
| `.github/workflows/cd.yml` | `publish`, `deploy` | push to main only: images to GHCR tagged with commit SHA; deploy on self-hosted runner label `aether-deploy` |
Every task's acceptance check runs in this CI. A task is accepted only when CI on its `task/<id>` branch is green.
