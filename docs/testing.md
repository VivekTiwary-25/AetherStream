# Testing

This document lists the checks that are in the tree an examiner can open. It is not a test-run log and it does not report throughput, latency, or checkpoint times. A workflow file describes a check. It does not, by itself, prove that a run passed. Paths below were read from `main` (this branch does not differ from `main` in the test, workflow, or `infra/scripts` trees).

Java is 21. The wrapper is `./gradlew` (CI) or `gradlew.bat` (Windows). Web tests use Vitest (`web/package.json` script `test` is `vitest run`). There is no Playwright or Cypress suite.

`./gradlew build` compiles every module and runs each module's `test` task. In `:tests`, that task excludes JUnit tag `integration`, so it does not start Testcontainers. The integration suite is a separate task.

## Pyramid

| Layer | What runs | Local command | CI workflow |
|---|---|---|---|
| Unit | JUnit 5 in each module, no Docker | `./gradlew test` or `./gradlew build` | `ci-java` (`.github/workflows/ci-java.yml`), job `java`, `./gradlew build` |
| Web unit | Vitest | `cd web && npm ci && npm test` | `ci-web`, job `web`, `npm run test` |
| Integration | JUnit tag `integration`, Testcontainers | `./gradlew :tests:integrationTest` (Docker required) | `ci-integration`, job `integration` |
| Fault | Fencing against Postgres is an integration test. Crash-recovery and incomplete-checkpoint fault ITs are not separate classes on `main`. | same as the cited test | `ci-java` for unit; `ci-integration` for `WorkerAdaptersIT`; compose kill is `ci-e2e` and is non-blocking |
| Full system | `infra/scripts/demo-e2e.sh` against Compose | see below | `ci-e2e`, job `e2e` (`continue-on-error: true`) |
| Stack smoke (not the demo) | `infra/scripts/smoke.sh` | `docker compose up -d --build` then `bash infra/scripts/smoke.sh` | `ci-compose`, job `compose` |

Reports from `ci-java` are uploaded as artifact `java-test-reports` (`**/build/reports/tests/`). Reports from `ci-integration` are artifact `integration-test-reports` (`tests/build/reports` and `tests/build/test-results`).

## Unit

### Compiler validation

`compiler/src/test/java/io/aetherstream/compiler/PipelineCompilerTest.java`

Proves a pipeline is rejected before any plan is produced, and that the error list names a contract code, a node id when the code has one, and a repair hint for type and config errors. An invalid definition does not yield a plan (`plan()` is null), and `plan(...)` throws if called anyway. Fixture files are `compiler/src/test/resources/pipelines/`.

Codes asserted from fixtures: `NO_SOURCE`, `MULTIPLE_SOURCES`, `NO_SINK`, `DUPLICATE_NODE_ID`, `UNKNOWN_NODE_TYPE`, `EDGE_UNKNOWN_NODE`, `CYCLE`, `MULTIPLE_INPUTS`, `UNREACHABLE_NODE`, `DANGLING_NODE`, `TYPE_MISMATCH`, `INVALID_CONFIG`, `UNSUPPORTED_KEY`. `three-errors.json` must report three independent errors in one result. The demo pipeline must validate and plan deterministically, and the plan must round-trip through JSON.

This does not start workers. "Rejected before workers start" at the HTTP boundary is `PipelineControllerTest.invalidPipelineReturnsValidationErrorsAndCannotBeSaved` (below): validate returns errors, and save is not called.

```sh
./gradlew :compiler:test --tests io.aetherstream.compiler.PipelineCompilerTest
```

CI: `ci-java`.

### Operator semantics, including snapshot equivalence

`compiler/src/test/java/io/aetherstream/compiler/ops/PartitionPipelineTest.java`

Same operator function the engine and the reference executor call (`io.aetherstream.compiler.ops`). Proves, on the demo plan:

- a window closes when the watermark reaches its end, duplicates inside the open window collapse, and a late record is a dead letter and does not move the watermark;
- a filtered-out event still advances the partition;
- firing order is window end, then key, then sink node id;
- the stuck-truck rule fires once across ten consecutive windows and restarts after a gap or a failing window;
- map ops are sequential and do not mutate a sibling branch;
- the lexically first late window is the one that emits the dead letter.

Snapshot equivalence: `snapshotRoundTripsMatchUninterruptedExecutionAcrossFiveThousandRecords`. One pipeline runs 5000 records uninterrupted (`SplittableRandom` seed 42, including late, malformed, and repeated records). A second pipeline is snapshotted and restored at records 777, 2345, and 4321. Outputs must be identical, including windows, alerts, and `LATE` / `REJECTED` dead letters, and `maxTs` must match. That is the unit proof that operator state survives a snapshot cut. It is not a distributed checkpoint.

Related, still unit, no broker:

- `compiler/src/test/java/io/aetherstream/compiler/ops/EnvelopeValidatorTest.java` — trusted source position, dead-letter identity for malformed input, strict envelope and payload fields.
- `compiler/src/test/java/io/aetherstream/compiler/ops/ConditionTest.java` — missing fields never match; numbers compare as values, not as JSON text.

```sh
./gradlew :compiler:test --tests io.aetherstream.compiler.ops.PartitionPipelineTest
```

CI: `ci-java`.

### Simulator determinism

`simulator/src/test/java/io/aetherstream/simulator/SimulationTest.java`

`seed42IsByteIdenticalForThirtyMinutesAndTheSameApiCalls` runs two `Simulation(42)` instances for 30 simulated minutes at 1-second steps and requires the same emissions. `coarseAndFineSteppingHaveIdenticalPerTruckEvents` requires that one 30-minute step and many small steps emit the same per-truck records. Other methods check the wire contract, the stuck `truck-007` demo (15 simulated minutes stopped, then resume), that disabling the demo does not stop truck-007, verbatim duplicates, late times with fresh ids, seeded malformed records that keep the partition key, and that an added truck snaps to a street and stops emitting when removed.

`simulator/src/test/java/io/aetherstream/simulator/SimClockTest.java` proves fractional ticks accumulate and that a pause does not catch up on resume.

`simulator/src/test/java/io/aetherstream/simulator/HttpApiTest.java` is an in-process HTTP server (port 0). It checks health, truck add/delete, control changes that do not partially apply, and chaos routes that emit only after validation. It does not talk to Kafka.

```sh
./gradlew :simulator:test
```

CI: `ci-java`. The dev mock server is a different check: `ci-mock` runs `cd web/mock && npm install && node smoke.mjs`. That is not the simulator and not Vitest.

### Coordinator logic with a fake clock

`engine/src/test/java/io/aetherstream/engine/coordinator/CoordinatorTest.java`

`FakeClock` plus `InMemoryExecutionRepository` and `InMemoryCheckpointStore`. No Docker. The clock is advanced explicitly, so failure detection does not wait on wall time.

What the tests prove:

- two workers split partitions 0..5 round-robin and checkpoint 0 carries the looked-up offsets;
- a checkpoint stays `PENDING` until every partition acks, the manifest object exists before the row flips to `COMPLETED`, and a duplicate ack is harmless (`checkpointCompletesOnlyWhenAllPartitionsAckedAndManifestIsWrittenFirst`);
- an ack whose generation or checkpoint id does not match is rejected and leaves the checkpoint `PENDING` (`staleAckIsRejected`);
- a dead worker's partitions are reassigned, generation bumps, and restore uses the last `COMPLETED` checkpoint, not a partial one (`workerFailureRecoversFromLastCompletedCheckpointWithNewGeneration`, `incompleteCheckpointIsNeverChosenForRecovery`);
- a new `Coordinator` on the same in-memory repository, after `loadActiveExecutions()`, reconciles once workers re-register (`coordinatorRestartRecoversAfterWorkersReRegister`);
- a newly registered worker is included at the next completed checkpoint, partitions stay a split of 0..5 (no overlap in the assertions), and a further rebalance inside 30 seconds is rejected (`stableNewWorkerRebalancesAtCompletedCheckpointAndHonorsRecoveryAndRateLimit`).

`CoordinatorHttpServerTest` binds port 0 and checks register, heartbeat, start, state, and Prometheus text on `/coord/metrics`. It is not the Compose coordinator.

```sh
./gradlew :engine:test --tests io.aetherstream.engine.coordinator.CoordinatorTest
```

CI: `ci-java`. These are not the fault integration tests. See below.

### Worker runtime with fakes

`engine/src/test/java/io/aetherstream/engine/worker/TaskRuntimeTest.java`

`WorkerFixtures.FakeSource` and `MemorySink`. No Kafka, no Postgres.

- `outputsEqualDirectPartitionPipeline` — the runtime's sink rows match a direct `PartitionPipeline` on the same records.
- `barrierAckOffsetsAndRestoreEquivalence` — after 700 records a barrier ack's offsets sum to 700, the snapshot object matches those offsets, and a restored runtime writes only the records at or after the cut.
- `fencedSinkStopsRuntimeAndWritesNothingMore` — once the fake sink reports fenced, the runtime stops, one write is rejected, and later records are not emitted or written.
- `sinkDelayFillsQueuesAndBlocksSource` — both queues reach `TaskRuntime.QUEUE_CAPACITY`, `backpressured()` is true, and emitted records stay within a bound derived from processed records plus one queue plus a poll batch. The test does not print a latency percentile.

`engine/src/test/java/io/aetherstream/engine/worker/WorkerTest.java` covers idle status, generation replace/ignore rules, sink-delay and kill commands, and a fenced runtime returning the worker to `IDLE` with an empty sink.

```sh
./gradlew :engine:test --tests io.aetherstream.engine.worker.TaskRuntimeTest
```

CI: `ci-java`.

### control-api WebMvc

REST controllers use `@WebMvcTest` and MockMvc. Upstream coordinator, simulator, and JDBC calls are mocked, so these tests prove the HTTP contract, not a live cluster.

`control-api/src/test/java/io/aetherstream/api/PipelineControllerTest.java`

- an invalid pipeline returns validation errors and is not saved;
- `GET /api/pipelines/demo` is the compiler demo and validates with a 6-partition plan;
- creating the same id returns the next version, and the repository stores that version;
- a missing id is 404;
- start stops a `RUNNING` or `RECOVERING` execution, plans the latest version, and reuses an idempotency key;
- an upstream failure does not attach the key or write a start audit;
- a missing pipeline or missing key does not stop an execution;
- `CoordinatorClient` posts the contract body to a local `HttpServer`.

`control-api/src/test/java/io/aetherstream/api/ControlApiControllerTest.java` — `/api/version` has `commit` and `builtAt`, `/api/health` is process `UP`, chaos proxies the payload, an upstream failure is `502` with `UPSTREAM_DOWN`, and the summary shape matches the contract.

Not WebMvc, still in this module and still run by `ci-java`:

- `control-api/src/test/java/io/aetherstream/api/ws/WebSocketServerTest.java` — `@SpringBootTest` on a random port. A standard client gets a snapshot and live updates; failed upstreams become null snapshot parts and a later poll recovers.
- `ExecutionEventsTest`, `LiveHubTest`, `NewRowsTrackerTest` — event names, a blocked snapshot writer not blocking another browser, and result-row cursors.

```sh
./gradlew :control-api:test
```

CI: `ci-java`.

### Web Vitest

No browser end-to-end runner. Tests render components or call pure functions.

| File | What it checks |
|---|---|
| `web/src/App.test.tsx` | Title renders; the mock badge is omitted when `VITE_USE_MOCK` is unset. |
| `web/src/app/__tests__/dashboard.test.tsx` | Dashboard reducer applies contract WebSocket messages and keeps 200 windows; backpressured and dead workers are labelled; a recovering execution shows the checkpoint id. |
| `web/src/app/chaos/__tests__/panels.test.tsx` | Kill requires confirmation before `fetch`; slow-sink posts the selected delay; compare panel renders a match and lists mismatches. |
| `web/src/app/editor/__tests__/PipelineEditor.test.tsx` | Run stays disabled until validation succeeds. |
| `web/src/app/editor/__tests__/ValidationList.test.tsx` | Compiler message is shown and selects the node. |
| `web/src/app/editor/__tests__/pipelineModel.test.ts` | Pipeline JSON round-trips without editor positions; every edge target is laid out to the right of its source. |
| `web/src/city/__tests__/cityLogic.test.ts` | World fit and origin mapping, shortest-angle heading, stable partition in 0..5, particle pool cap. |

```sh
cd web && npm ci && npm test
```

CI: `ci-web` also runs `npm run lint` and `npm run build`, and uploads `web/dist` as `web-dist`. The workflow's `push` trigger is limited to `web/**` and the workflow file. A pull request to `main` has no path filter.

### Other unit tests on main

These are part of `./gradlew test` / `ci-java`. They are not a separate pyramid layer.

- `common/src/test/java/io/aetherstream/common/JsonRoundTripTest.java` — heartbeat commands round-trip with a `type` discriminator (`TRIGGER_CHECKPOINT`, `KILL`).
- `reference/src/test/java/io/aetherstream/reference/ReferenceExecutorTest.java` — single-process replay is deterministic in offset order, including chaos and an idle partition; comparison is at the replay frontier.
- `reference/src/test/java/io/aetherstream/reference/ResultComparatorTest.java` — window identity is sink + key + start; open engine windows are ignored; aggregation tolerance is absolute.
- `reference/src/test/java/io/aetherstream/reference/KafkaRangeReaderTest.java` — a scripted consumer, not a broker. Empty ranges must not create a consumer. This is not the Kafka integration test.

## Integration (Testcontainers)

`tests/build.gradle.kts` registers `integrationTest`. It includes only tag `integration`, forks a new JVM per test class (`forkEvery = 1`, because classes share topic and table names), and sets system property `aether.schema` to `infra/db/schema.sql`.

`tests/src/test/java/io/aetherstream/tests/support/IntegrationInfrastructure.java` starts one shared set of containers for the JVM:

- `postgres:17-alpine`, database `aether`, user `aether`
- `apache/kafka:4.3.2`, topic `truck-events`, 6 partitions, replication 1
- `pgsty/minio:RELEASE.2026-08-04T00-00-00Z`, bucket `aether-checkpoints`

Docker must be running. CI: `ci-integration`, `./gradlew :tests:integrationTest`, timeout 30 minutes, artifact `integration-test-reports`.

```sh
./gradlew :tests:integrationTest
```

### Postgres repository

`tests/src/test/java/io/aetherstream/tests/PostgresExecutionRepositoryIT.java`

`persistsExecutionGenerationCheckpointsAndAuditRows` inserts an execution, bumps generation 1 to 2, completes checkpoint 2, aborts checkpoint 1, leaves checkpoint 3 pending, and checks that `latestCompleted` stays 2 after `abortPendingCheckpoints`. It also reads the `execution.recovered` audit row. This is the repository against real Postgres. It does not run a worker.

### S3 store

`tests/src/test/java/io/aetherstream/tests/S3CheckpointStoreIT.java`

`roundTripsCheckpointManifestAndReportsObjectExistence` writes a `CheckpointManifest` through `S3CheckpointStore`, reads it back equal, and returns null for a missing key. It does not run a checkpoint protocol.

### Kafka offsets

`tests/src/test/java/io/aetherstream/tests/KafkaOffsetsLookupIT.java`

Produces 10 records on partition 2. `KafkaOffsetsLookup` must return earliest 0 for all 6 partitions and latest 10 on partition 2 only. This is the lookup the coordinator uses for checkpoint 0 and `startFrom`. It does not consume as a worker.

### Worker adapters and fencing

`tests/src/test/java/io/aetherstream/tests/WorkerAdaptersIT.java` (`@Timeout(120)`)

- `kafkaSourceSeeksAssignedPartitionsAndPreservesTheirOffsetOrder` — `KafkaRecordSource` on a private topic seeks and keeps offset order.
- `postgresSinkUpsertsAllOutputKindsAndReplaysIdempotently` — windows, alerts, and dead letters upsert; a second write of the same batch does not change the snapshot.
- `postgresSinkRejectsStaleGenerationWithoutInsertingOrOverwritingRows` — after `generation` is bumped, a write at the old generation returns false and the stored rows are unchanged.
- `generationBumpWaitsForInFlightSinkTransactionAndFencesItsNextBatch` — a sink transaction that holds `FOR SHARE` on `executions` blocks the generation bump; after it commits, the next batch at the old generation writes nothing.

That is the stale-worker fence against real Postgres. It is on `main`. It is not a killed worker process.

### Engine matches reference

`tests/src/test/java/io/aetherstream/tests/EngineMatchesReferenceIT.java`

`engineMatchesReferenceAtCompletedCheckpoint` is the distributed correctness check:

1. `WorkloadGenerator` builds a fixed seed-42 workload: `Simulation(42, 8, 0, true)`, 40 simulated minutes, 20 duplicates at minute 20, 20 late events (3 simulated minutes late), and 5 malformed records. No wall-clock sleep in the generator.
2. Records are produced to the Testcontainers topic.
3. `EngineCluster` starts a real coordinator HTTP server, 3 in-process workers, Kafka sources, fenced Postgres sinks, and the MinIO checkpoint store. Checkpoint interval in this cluster is 2000 ms (the constructor argument), not the Compose default of 10000. The cluster's kill handler throws; this test does not kill a worker.
4. The demo plan is started with `startFrom: earliest`. The test waits up to 120 seconds for a `COMPLETED` checkpoint whose source offsets cover the produced end offsets. Each worker must own 2 partitions.
5. Postgres rows are compared with a single-process replay of the same offset range. The test requires `match == true`, `matched == referenceResults` and `matched > 100`, `recordsReplayed` equal to the workload size, an alert whose key is `truck-007` and whose message is `truck-007 has not moved for 10 min`, and dead-letter counts of at least 5 `REJECTED` and at least 1 `LATE`.

The `> 100` figure is an assertion in the test, not a benchmark result from a recorded run. This document does not claim a particular CI run passed.

## Fault tests

`tests/build.gradle.kts` describes `integrationTest` as "integration and fault tests". On `main` the only tagged classes are the five integration tests above. There is no `CrashRecoveryIT` or `IncompleteCheckpointIT`.

| Scenario | On `main` | Where | Gap |
|---|---|---|---|
| Crash recovery | Unit yes. Compose script yes. Dedicated fault IT **(in progress)** | `CoordinatorTest.workerFailureRecoversFromLastCompletedCheckpointWithNewGeneration` and `workerFailureRecoversAllPartitionsOntoTwoSurvivors` (fake clock, in-memory store). `infra/scripts/demo-e2e.sh` step 5 kills a Compose `worker-N` container. | No Testcontainers test kills a worker process and then checks output. `EngineCluster` throws if `KILL` is requested. `ci-e2e`'s demo job is `continue-on-error: true`, so a red demo does not fail that workflow. |
| Stale worker fencing | Yes | `WorkerAdaptersIT` (Postgres, above). Also `TaskRuntimeTest.fencedSinkStopsRuntimeAndWritesNothingMore`, `WorkerTest.fencedRuntimeMakesWorkerIdle`, `CoordinatorTest.staleAckIsRejected` (acks, not sink rows). | The integration tests fence a sink generation. They do not run a stale worker process after a kill. |
| Incomplete checkpoint | Unit yes. Dedicated fault IT **(in progress)** | `CoordinatorTest.incompleteCheckpointIsNeverChosenForRecovery`: checkpoints 2 and 3 are aborted (partial ack, then worker loss); restore assignment uses checkpoint 1; manifests for 2 and 3 do not exist. | Not run against Postgres or MinIO. `PostgresExecutionRepositoryIT` checks that `latestCompleted` ignores an aborted row, which is the query the coordinator uses, not the recovery protocol. |

## Full-system Compose demo

`infra/scripts/demo-e2e.sh`

Requires `curl`, `jq`, and `docker`. It talks to the control API, default `http://localhost:18080` (`API` overrides it). That is the control-api host port in `docker-compose.yml`, not the nginx port `8088`. The idempotency key is read from `/proc/sys/kernel/random/uuid`. The script comment says that path is for the Linux Compose/CI host. `ci-e2e` runs on `ubuntu-latest`. Windows Git Bash does not provide that path.

Locally, from the repository root, on a Linux host with Docker:

```sh
docker compose up -d --build
bash infra/scripts/demo-e2e.sh
docker compose down -v
```

Set `EXPECT_SHA` to require `GET /api/version` `.commit` to equal that value. CI sets it to `github.sha`.

Steps the script itself checks:

1. Control API ready within 180 seconds.
2. `GET /api/pipelines/demo`, validate (`valid == true`), save, start an execution, state `RUNNING`.
3. At least 8 window results and `lastCompletedCheckpoint >= 2`, within 120 seconds.
4. `POST /api/chaos/late-events` with `count` 20 and `lateByMs` 180000; a `LATE` dead letter appears within 30 seconds.
5. Kill the live Compose service `worker-1`, `worker-2`, or `worker-3` that owns the most partitions. Detection of recovery or a new generation must happen within 20 seconds of the kill. Restore to `RUNNING` from a checkpoint `>= 2`, with a higher generation, must finish within 60 seconds of the kill.
6. After 20 seconds, poll compare for up to 60 seconds. Requires `match == true`, `referenceResults > 0`, and `matched == referenceResults`. The script prints the matched count. This document does not copy a number from a run.
7. `POST /api/chaos/slow-sink` with `delayMs` 200 until workers are backpressured (30 seconds), then `delayMs` 0 until they drain (60 seconds).
8. `GET /api/version`. If `EXPECT_SHA` is set, `.commit` must equal it.

CI: `.github/workflows/ci-e2e.yml`. Job `lint` runs ShellCheck on this script and is blocking. Job `e2e` depends on `lint`, builds the stack with `GIT_SHA` set to `github.sha`, runs the script, uploads logs, and always runs `docker compose down -v`. The `e2e` job is `continue-on-error: true` (comment in the file: temporary until the full backend is green on `main`). A green `ci-e2e` workflow is not proof that the demo passed. Open the `e2e` job log.

`infra/scripts/smoke.sh` is the shorter stack check, not this demo. It waits up to 120 seconds for Kafka, Postgres, and MinIO; requires topic `truck-events` to have 6 partitions, table `executions` to exist, and bucket `aether-checkpoints` to exist; requires coordinator `:18081/coord/health`, simulator `:18070/health`, control-api `:18080/api/health`, web `:8088/`, and nginx `:8088/api/health` to be up (180 seconds); expects 3 alive workers unless worker logs show `ClassNotFoundException` (that path warns and skips); expects the simulator state to list trucks; expects the topic offset sum to be greater than 0. CI: `ci-compose`. It does not kill a worker or compare with the reference.

## Handoff section 10

Source: `handoff.md` section 10, "Verification and acceptance criteria". "Covered" means a test in this tree asserts the observation. It does not mean a recorded run was attached.

| Handoff test | Required observation | What covers it | Gap |
|---|---|---|---|
| Invalid pipeline | Rejected before workers start; actionable validation feedback | `PipelineCompilerTest` (codes, node id, repair text, null plan). `PipelineControllerTest.invalidPipelineReturnsValidationErrorsAndCannotBeSaved` (validate errors, save not called). `ValidationList.test.tsx`, `PipelineEditor.test.tsx` (Run disabled until validation succeeds). | The API test mocks the coordinator. It does not observe a live worker process staying down. |
| Reference correctness | Distributed results match a trusted deterministic executor | `EngineMatchesReferenceIT` (3 workers, Kafka, Postgres, MinIO, compare `match`). `ReferenceExecutorTest` and `ResultComparatorTest` (single process and the comparator rules). `demo-e2e.sh` step 6. | The demo job does not fail `ci-e2e` while `continue-on-error` is set. No committed run log is in the repo. |
| Duplicate source records | Behaviour matches the declared event deduplication policy | `PartitionPipelineTest.closesAtWatermarkEqualityDeduplicatesAndRejectsLateBeforeUpdatingWatermark`. `WorkloadGenerator` inserts 20 duplicates into the workload `EngineMatchesReferenceIT` replays, so a dedup mismatch fails `match`. `SimulationTest` checks the generator emits verbatim duplicates. | No assertion counts suppressed duplicates by itself. The integration test only requires the whole comparison to match. |
| Out-of-order events | Window results match the configured watermark and late-data semantics | `PartitionPipelineTest` late-before-watermark and lexically-first late window. `EngineMatchesReferenceIT` requires at least one `LATE` dead letter. `demo-e2e.sh` step 4 expects a `LATE` dead letter after `lateByMs` 180000. | The integration test does not isolate out-of-order windows as its own assertion beyond the dead-letter count and the overall match. |
| Worker termination | Work restores from a valid checkpoint and completes with the expected output | Unit: `CoordinatorTest.workerFailureRecoversFromLastCompletedCheckpointWithNewGeneration` (restore assignment from the last completed checkpoint, new generation). Script: `demo-e2e.sh` step 5, then step 6 compare. | Dedicated fault IT **(in progress)**. Unit test does not check sink rows. Demo is non-blocking in CI. |
| Interrupted checkpoint | Incomplete checkpoint is never selected as the recovery point | `CoordinatorTest.incompleteCheckpointIsNeverChosenForRecovery` (restore uses checkpoint 1; 2 and 3 aborted; their manifests absent). `PostgresExecutionRepositoryIT` (`latestCompleted` stays on the completed id after an abort). | Dedicated fault IT against real stores **(in progress)**. |
| Stale worker | Old execution generation cannot commit new output | `WorkerAdaptersIT.postgresSinkRejectsStaleGenerationWithoutInsertingOrOverwritingRows` and `generationBumpWaitsForInFlightSinkTransactionAndFencesItsNextBatch`. `TaskRuntimeTest.fencedSinkStopsRuntimeAndWritesNothingMore`. `CoordinatorTest.staleAckIsRejected`. | Integration coverage is the sink fence, not a leftover worker process after `KILL`. |
| Slow sink | Buffers remain bounded and backpressure is observable | `TaskRuntimeTest.sinkDelayFillsQueuesAndBlocksSource` (queues at `QUEUE_CAPACITY`, `backpressured()`, emitted bound). `demo-e2e.sh` step 7 (API shows backpressure, then drain). `CoordinatorTest.chaosKillTargetsBusiestWorkerAndSinkDelayReachesNewWorkers` only checks the command is delivered. | No Testcontainers test delays a real Postgres sink. The unit bound is not a latency percentile. |
| Rescale | No unsupported concurrent partition ownership; results remain correct | `CoordinatorTest.stableNewWorkerRebalancesAtCompletedCheckpointAndHonorsRecoveryAndRateLimit` reassigns the same partitions 0..5 with no overlapping lists in the assertions, at a completed checkpoint, and rejects another rebalance inside 30 seconds. | Not a partition-count change and not a reference comparison. `docs/architecture.md` marks rescaling planned. This criterion is a gap for result correctness. |
| Coordinator restart | Single-coordinator baseline restores metadata and reconciles execution | `CoordinatorTest.coordinatorRestartRecoversAfterWorkersReRegister` (new coordinator object, same in-memory repository, `loadActiveExecutions()`, then re-register). | Not a killed coordinator process and not Postgres. No integration test restarts the coordinator JVM. |
| Cross-organisation access | Authorisation denies another organisation's resources | None. | Gap. `docs/architecture.md` marks organisation roles and login as planned. They are not in the v1 API, and no test denies a cross-organisation call. |
| Deployment | Running version matches the released image and smoke tests pass | The check is defined in `.github/workflows/cd.yml` and `infra/deploy/smoke-deploy.sh`: `/api/health` is `UP` and `/api/version` `.commit` equals the deployed SHA (nginx, port 8088). `ci-images` checks the OCI revision label equals `GITHUB_SHA` on images it built and did not push. | This document does not claim a deploy run passed. See `docs/ci-cd.md`. |
| Incompatible savepoint | Restore is rejected with a useful explanation | None. | Gap. `docs/architecture.md` marks savepoints and the incompatible-restore checker as planned. v1 has periodic checkpoints and checkpoint 0 only. |

Handoff section 10 also asks for recorded input counts, accepted and rejected records, output correctness, throughput, latency percentiles, checkpoint duration, and recovery time. `EngineMatchesReferenceIT` prints one `CompareResult` JSON line when it runs. `demo-e2e.sh` prints the recovery seconds and the matched count when it runs. Neither result is stored as a benchmark artifact in the repo. Do not fill those numbers in from a guess.
