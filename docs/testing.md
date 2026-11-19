# Testing

This catalogue describes assertions present in the checkout; it is not a test-run log and does not report throughput, latency, or checkpoint times. A workflow file describes a check. It does not, by itself, prove that a run passed.

Java is 21. The wrapper is `./gradlew` (CI) or `gradlew.bat` (Windows). Web unit tests use Vitest (`web/package.json` script `test` is `vitest run`); `tests/ui` contains a real-stack Playwright scenario.

`./gradlew build` compiles every module and runs each module's `test` task. In `:tests`, that task excludes JUnit tag `integration`, so it does not start Testcontainers. The integration suite is a separate task.

## Test catalogue

Counts below are source declarations: literal `@Test` occurrences in Java and `it(` / `test(` occurrences in web and Playwright sources. Parameterized JUnit cases count once here, not once per generated invocation. Script checks are counted by declared workflow step/scenario, not by grep annotations. Workflow names are the YAML `name`; paths are included so the wiring is auditable.

| Module | Class/file | Count | What it proves |
|---|---|---:|---|
| engine | `CoordinatorHardeningTest` | 8 | Coordinator retries/command delivery, heartbeat incarnation handling, recovery and fencing edge cases. |
| engine | `CoordinatorHttpServerTest` | 2 | HTTP registration, heartbeat/start/state routes, and Prometheus metrics response. |
| engine | `CoordinatorStressTest` | 1 | Coordinator invariants survive 200 simulated kill/rejoin/checkpoint cycles. |
| engine | `CoordinatorTest` | 13 | Partition assignment, checkpoint completion ordering, stale ack rejection, recovery from last completed checkpoint, restart reconciliation, and rebalance limits. |
| engine | `SavepointTest` | 13 | Savepoint creation, compatibility rejection, durable restore, checkpoint lineage, and stopped/timeout handling. |
| engine | `TaskRuntimeTest` | 9 | Workload replay parity, barrier offsets and restore, stale sink fencing, bounded queues/backpressure, and checkpoint admission. |
| engine | `WorkerCycleLeakTest` | 1 | Repeated assign/cancel cycles release worker threads and sources. |
| engine | `WorkerMetricsTest` | 2 | Worker metrics remain live and preserve counters through cancellation/fencing. |
| engine | `WorkerShutdownTest` | 3 | Shutdown flushes buffered output without waiting for injected sink delay. |
| engine | `WorkerTest` | 4 | Idle/generation behavior, sink delay/kill commands, and fenced-runtime return to idle. |
| compiler | `PipelineCompilerTest` | 9 | Demo-plan determinism/JSON round-trip and validation error collection, type/config hints, and unsupported node/key handling. |
| compiler | `PipelineLibraryTest` | 4 | Example catalog ordering, runnable example validity/layout, and the deliberately invalid demo's three errors. |
| compiler | `ConditionTest` | 2 | Missing fields do not match; numeric comparison uses numeric values without coercion. |
| compiler | `EnvelopeValidatorTest` | 5 | Envelope/payload rules, trusted source position, malformed-record dead-letter identity, and unknown-field behavior. |
| compiler | `PartitionPipelineTest` | 8 | Watermarks, late records, deduplication, ordering, rules, map isolation, and snapshot/restore equivalence. |
| reference | `KafkaRangeReaderTest` | 7 | Reads only requested partition tails, handles malformed/tombstone tails, and skips empty retained partitions. |
| reference | `ReferenceExecutorTest` | 5 | Deterministic partition replay and frontiers; seeded interleavings and an independent batch oracle exercise property-style semantic cases. |
| reference | `ResultComparatorTest` | 10 | Window/alert identity, numeric tolerance, closed-window extras, open-window exclusion, and stable mismatch summaries. |
| control-api | `ControlApiControllerTest` | 9 | Version/health, chaos proxy/error handling, and API response contract shapes. |
| control-api | `PipelineControllerTest` | 11 | Validation/save behavior, demo plan, versions, lookup, start/idempotency, and upstream failure handling. |
| control-api | `CompareServiceTest` | 8 | Reference comparison uses the correct checkpoint range and caller execution, with serialization, locking, and failure handling. |
| control-api | `ExtrasControllerTest` | 5 | Scenario proxy/error handling, coordinator metrics, and scenario listing. |
| control-api | `LibraryControllerTest` | 3 | Example listing/routing, retrieval, and unknown-example response. |
| control-api | `OpsControllerTest` | 15 | Savepoint lifecycle, ownership checks, restore/rescale and authorization-sensitive operational routes. |
| control-api | `ExecutionEventsTest` | 16 | Execution transitions and checkpoint changes produce the expected independent event messages. |
| control-api | `LiveHubTest` | 1 | A blocked browser snapshot writer does not block another browser or truck polling. |
| control-api | `NewRowsTrackerTest` | 8 | Database row cursor/deduplication across ordering, composite keys, and resets. |
| control-api | `WebSocketServerTest` | 2 | Real random-port WebSocket client gets a snapshot/live updates and recovers from failed upstream polling. |
| simulator | `HttpApiTest` | 14 | HTTP health/state, trucks, controls, scenario mutation, and chaos validation without Kafka. |
| simulator | `KafkaEventTimeTest` | 3 | Kafka partition-tail consumption and safe handling of malformed/tombstone events. |
| simulator | `SimClockTest` | 2 | Fractional ticks accumulate and pause/resume or invalid scale does not rewind/corrupt time. |
| simulator | `SimulationTest` | 15 | Seed determinism, wire contract, stuck truck, chaos events, movement, and equivalent coarse/fine stepping. |
| simulator | `SimulatorRuntimeTest` | 3 | Scenario lifecycle, restart-safe event identity/time, and runtime clock re-anchoring. |
| tests (Testcontainers) | `EngineMatchesReferenceIT` | 1 | Distributed 3-worker Kafka/Postgres/MinIO run matches reference results at a completed checkpoint, with workload, alert, and dead-letter assertions. |
| tests (Testcontainers) | `IncompleteCheckpointIT` | 1 | Interrupted pending checkpoint is aborted and recovery restores the prior completed manifest/generation. |
| tests (Testcontainers) | `KafkaOffsetsLookupIT` | 1 | Real Kafka returns earliest offsets for every partition and latest offset for the populated partition. |
| tests (Testcontainers) | `OrgAccessIT` | 1 | Organization-scoped API access prevents a caller from operating on another organization's execution/resources. |
| tests (Testcontainers) | `PostgresExecutionRepositoryIT` | 1 | Real Postgres persists execution/generation/checkpoint/audit state and retains the latest completed checkpoint after abort. |
| tests (Testcontainers) | `S3CheckpointStoreIT` | 1 | MinIO checkpoint manifest round-trips and missing object lookup returns absent. |
| tests (Testcontainers) | `StaleWorkerFencingIT` | 1 | A stale worker's next nonempty sink transaction is rejected after generation changes and the worker stops. |
| tests (Testcontainers) | `WorkerAdaptersIT` | 4 | Kafka source offset order; Postgres upsert/idempotent replay; stale-generation rejection and in-flight transaction fencing. |
| tests (Testcontainers) | `WorkerCrashRecoveryIT` | 1 | Real worker process crash restores a completed checkpoint on survivors, then recovered output matches reference. |
| tests (Testcontainers benchmark) | `EngineBenchmarkIT` | 1 | Testcontainers engine benchmark/recovery workload records its benchmark report. |
| web (Vitest) | `App.test.tsx` | 1 | App title renders and mock badge is absent when mock mode is unset. |
| web (Vitest) | `app/__tests__/appShell.test.tsx` | 5 | Shell composes app navigation, panels and visible shell states. |
| web (Vitest) | `app/__tests__/dashboard.test.tsx` | 3 | Dashboard reducer applies contract WebSocket messages, bounds windows, and labels workers/recovery. |
| web (Vitest) | `app/chaos/__tests__/panels.test.tsx` | 4 | Kill confirmation precedes request, slow-sink selection posts, and compare panel renders match/mismatches. |
| web (Vitest) | `app/editor/__tests__/PipelineEditor.test.tsx` | 3 | Editor run remains disabled until validation succeeds. |
| web (Vitest) | `app/editor/__tests__/ValidationList.test.tsx` | 1 | Compiler error is rendered and selects its node. |
| web (Vitest) | `app/editor/__tests__/pipelineModel.test.ts` | 2 | Pipeline JSON round-trips without editor positions and layout respects edge direction. |
| web (Vitest) | `app/ops/__tests__/OpsPanel.test.tsx` | 4 | Operational actions and displayed operational state follow the UI contract. |
| web (Vitest) | `app/scenarios/__tests__/ScenarioPanel.test.tsx` | 4 | Scenario choices and API interaction render correctly. |
| web (Vitest) | `app/timeline/__tests__/CheckpointTimeline.test.tsx` | 1 | Checkpoint timeline component renders its timeline state. |
| web (Vitest) | `app/timeline/__tests__/timelineModel.test.ts` | 3 | Checkpoint timeline model orders and formats checkpoint state. |
| web (Vitest) | `app/tour/__tests__/tour.test.tsx` | 9 | Tour overlay steps, storage/progress, and user navigation behave as expected. |
| web (Vitest) | `city/__tests__/camera.test.ts` | 6 | Camera fitting, mapping, and viewport transforms are stable. |
| web (Vitest) | `city/__tests__/cityLogic.test.ts` | 9 | World fit, origin mapping, heading, partition assignment, and particle cap. |
| web (Vitest) | `city/__tests__/cityModel.test.ts` | 9 | City model produces consistent scene entities and derived model state. |
| web (Vitest) | `city/__tests__/motion.test.ts` | 11 | Truck motion interpolation and timing behave consistently across updates. |
| tests/ui (Playwright) | `demo.spec.ts` | 4 | Real-stack browser demo checks deployed UI/version, persisted engine speed, editor validation, rendered recovery, and nonempty reference parity. |
| script | `infra/scripts/demo-e2e.sh` steps 1–11 | 11 | Compose readiness, start/results/late events, kill/recovery, reference compare, backpressure, SHA, savepoint restore/compare, rescale, and scenario reset; CI sets `E2E_EXTENDED=1`. |
| script | `infra/scripts/demo-ready.sh` | 1 | Readiness/version plus running execution with new windows/checkpoint and nonempty exact reference compare. |
| script | `infra/scripts/soak.sh` | 0 (not present) | No file with this path exists in the checkout; no soak check/workflow can be attributed. |
| workflow check | `ci-k8s` (`.github/workflows/ci-k8s.yml`) | 1 job | Renders/validates Kubernetes manifests, deploys kind, verifies readiness/results, deletes a worker pod, and checks checkpoint recovery and subsequent progress. |
| workflow check | `ci-terraform` (`.github/workflows/ci-terraform.yml`) | 1 job | Formats/validates Terraform, applies real Docker infrastructure, checks API/web/workers and backing services, then destroys and verifies cleanup. |

Module totals (literal source occurrences): engine 56; compiler 28; reference 22; control-api 78; simulator 37; tests 13; web/Vitest 75; tests/ui 4. Overall: **313 test declarations**. Script/workflow checks are catalogued separately and are not included in this total.

Workflows: engine/compiler/reference/control-api/simulator unit tests run under `ci-java` (`./gradlew build`); `tests` integration-tagged ITs run under `ci-integration` (`./gradlew :tests:integrationTest`); `EngineBenchmarkIT` runs under `bench` (`./gradlew :tests:benchmarkTest`); Vitest runs under `ci-web`; Playwright runs under advisory `ci-ui-e2e`; `demo-e2e.sh` and its final `demo-ready.sh` run under `ci-e2e` (the e2e job is `continue-on-error`); `ci-k8s` and `ci-terraform` are workflow checks above. `ci-mock` runs only the separate mock-server smoke command; it does not run these catalogued tests. The Compose worker-crash-and-compare check is also present in `ci-e2e`; it is not a Testcontainers IT.

Reproduce counts (from repository root):

```sh
for d in engine/src/test compiler/src/test reference/src/test control-api/src/test simulator/src/test tests/src/test web/src tests/ui; do
  printf '%-24s ' "$d"
  rg -o '@Test|\bit\(|\btest\(' "$d" --glob '*.java' --glob '*.ts' --glob '*.tsx' --glob '*.js' | wc -l
done
rg -o '@Test|\bit\(|\btest\(' engine/src/test compiler/src/test reference/src/test control-api/src/test simulator/src/test tests/src/test web/src tests/ui --glob '*.java' --glob '*.ts' --glob '*.tsx' --glob '*.js' | wc -l
```

Headline proofs: `EngineMatchesReferenceIT` compares 3-worker Kafka/Postgres/MinIO output with reference at a completed checkpoint; `WorkerCrashRecoveryIT` kills a worker process and compares recovered output; `StaleWorkerFencingIT` proves Postgres rejects a stale worker's next nonempty sink transaction; `IncompleteCheckpointIT` aborts pending state and restores the previous completed manifest; `CoordinatorHardeningTest` exercises coordinator edge cases and `CoordinatorStressTest` checks invariants through 200 cycles. `ReferenceExecutorTest` has 32 seeded partition-preserving interleavings and 240 seeded independent-oracle cases. `demo-e2e.sh` step 9 restores from a savepoint and compares the resumed execution with reference. These are source assertions, not claims about a particular CI run.

## Pyramid

| Layer | What runs | Local command | CI workflow |
|---|---|---|---|
| Unit | JUnit 5 in each module, no Docker | `./gradlew test` or `./gradlew build` | `ci-java` (`.github/workflows/ci-java.yml`), job `java`, `./gradlew build` |
| Web unit | Vitest | `cd web && npm ci && npm test` | `ci-web`, job `web`, `npm run test` |
| Integration | JUnit tag `integration`, Testcontainers | `./gradlew :tests:integrationTest` (Docker required) | `ci-integration`, job `integration` |
| Fault | Postgres fencing, worker crash recovery, and incomplete-checkpoint recovery have dedicated Testcontainers ITs in `tests`. | `./gradlew :tests:integrationTest` (Docker required) | `ci-integration`; the separate Compose fault/demo checks run in `ci-e2e`. |
| Full system | `infra/scripts/demo-e2e.sh` against Compose | see below | `ci-e2e`, job `e2e`; consult that job's result and logs. |
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

Vitest tests render components or call pure functions; the real-stack browser scenario is the separate Playwright project in `tests/ui` (catalogued above).

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
3. `EngineCluster` starts a real coordinator HTTP server, 3 in-process workers, Kafka sources, fenced Postgres sinks, and the MinIO checkpoint store. Checkpoint interval in this cluster is 2000 ms (the constructor argument), not the Compose default of 10000. This particular class does not kill a worker; that is covered by `WorkerCrashRecoveryIT`.
4. The demo plan is started with `startFrom: earliest`. The test waits up to 120 seconds for a `COMPLETED` checkpoint whose source offsets cover the produced end offsets. Each worker must own 2 partitions.
5. Postgres rows are compared with a single-process replay of the same offset range. The test requires `match == true`, `matched == referenceResults` and `matched > 100`, `recordsReplayed` equal to the workload size, an alert whose key is `truck-007` and whose message is `truck-007 has not moved for 10 min`, and dead-letter counts of at least 5 `REJECTED` and at least 1 `LATE`.

The `> 100` figure is an assertion in the test, not a benchmark result from a recorded run. This document does not claim a particular CI run passed.

## Fault tests

`tests/build.gradle.kts` includes all classes tagged `integration` in `integrationTest`; the current checkout has dedicated `WorkerCrashRecoveryIT`, `StaleWorkerFencingIT`, and `IncompleteCheckpointIT` classes in addition to the other integration classes.

| Scenario | Coverage | What it asserts |
|---|---|---|
| Crash recovery | `WorkerCrashRecoveryIT`, `EngineMatchesReferenceIT`, `CoordinatorTest`, `demo-e2e.sh` step 5 | The IT kills a worker process, restores a completed checkpoint on survivors, and compares the resulting output with reference. The Compose script also kills a live worker and checks recovery and parity. |
| Stale worker fencing | `StaleWorkerFencingIT`, `WorkerAdaptersIT`, `TaskRuntimeTest`, `WorkerTest`, `CoordinatorTest` | The IT uses a real Postgres generation change to reject the stale worker's next nonempty sink batch and verifies it stops. |
| Incomplete checkpoint | `IncompleteCheckpointIT`, `CoordinatorTest`, `PostgresExecutionRepositoryIT` | The IT aborts a pending checkpoint after failure and restores the previous completed manifest; unit coverage checks the coordinator avoids incomplete checkpoints. |

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
9. Save a savepoint, restore it as a new execution, wait for windows/checkpoints, and require restored results to match the reference.
10. Rejoin the worker killed in step 5 and require rescale to assign all six partitions exactly once at a higher generation.
11. Apply the rush-hour simulator scenario, verify it, reset, and verify `scenario == "none"`.
The CI workflow sets `E2E_EXTENDED=1`, so it runs all eleven steps.

CI: `.github/workflows/ci-e2e.yml`. `lint` runs ShellCheck; `e2e` depends on it, builds the stack, runs the extended demo, collects logs, tears down Compose, then reruns `demo-ready.sh`. The workflow does not set `continue-on-error`; examine job logs for the observed result.

`infra/scripts/smoke.sh` is the shorter stack check, not this demo. It waits up to 120 seconds for Kafka, Postgres, and MinIO; requires topic `truck-events` to have 6 partitions, table `executions` to exist, and bucket `aether-checkpoints` to exist; requires coordinator `:18081/coord/health`, simulator `:18070/health`, control-api `:18080/api/health`, web `:8088/`, and nginx `:8088/api/health` to be up (180 seconds); expects 3 alive workers unless worker logs show `ClassNotFoundException` (that path warns and skips); expects the simulator state to list trucks; expects the topic offset sum to be greater than 0. CI: `ci-compose`. It does not kill a worker or compare with the reference.

## Handoff section 10

Source: `handoff.md` section 10, "Verification and acceptance criteria". "Covered" means a test in this tree asserts the observation. It does not mean a recorded run was attached.

| Handoff test | Required observation | What covers it | Remaining boundary |
|---|---|---|---|
| Invalid pipeline | Rejected with actionable validation feedback | `PipelineCompilerTest`, `PipelineControllerTest.invalidPipelineReturnsValidationErrorsAndCannotBeSaved`, and editor validation tests. | The API test mocks the coordinator; it does not observe a live worker process. |
| Reference correctness | Distributed results match the deterministic executor | `EngineMatchesReferenceIT`, `ReferenceExecutorTest`, `ResultComparatorTest`, and `demo-e2e.sh` steps 6 and 9. | The browser Playwright suite also checks nonempty parity; `ci-ui-e2e` is advisory. |
| Duplicate source records | Duplicate event behavior matches the declared policy | `PartitionPipelineTest`, seeded reference-oracle cases, `WorkloadGenerator`, and `SimulationTest`. | No assertion independently reports a suppressed-duplicate total. |
| Out-of-order events | Windows and late-data behavior match watermark semantics | `PartitionPipelineTest`, reference seeded cases, `EngineMatchesReferenceIT`, and `demo-e2e.sh` step 4. | Distributed IT asserts the aggregate match and dead-letter floor, not an isolated late-window output. |
| Worker termination | Work restores from a valid checkpoint and resumes correct output | `WorkerCrashRecoveryIT`, `CoordinatorTest`, `demo-e2e.sh` steps 5–6, and `ci-k8s` recovery smoke. | Compose and Kubernetes workflows exercise different deployment paths. |
| Interrupted checkpoint | Recovery never selects the incomplete checkpoint | `IncompleteCheckpointIT`, `CoordinatorTest.incompleteCheckpointIsNeverChosenForRecovery`, and `PostgresExecutionRepositoryIT`. | The unit test uses in-memory stores; the IT uses real Postgres/MinIO. |
| Stale worker | Old generation cannot commit output | `StaleWorkerFencingIT`, `WorkerAdaptersIT`, `TaskRuntimeTest`, and `CoordinatorTest.staleAckIsRejected`. | IT checks the worker's next nonempty sink transaction; it is not a process left running after the crash scenario. |
| Slow sink | Buffers remain bounded and backpressure is observable | `TaskRuntimeTest.sinkDelayFillsQueuesAndBlocksSource` and `demo-e2e.sh` step 7. | No percentile/latency benchmark is asserted by this test. |
| Rescale | Ownership remains unique and generation advances | `CoordinatorTest.stableNewWorkerRebalancesAtCompletedCheckpointAndHonorsRecoveryAndRateLimit`, `demo-e2e.sh` step 10. | It does not change partition count or compare the rescaled output with reference. |
| Coordinator restart | Single-coordinator baseline reloads metadata and reconciles workers | `CoordinatorTest.coordinatorRestartRecoversAfterWorkersReRegister`. | In-memory repository; no killed coordinator JVM integration test is listed. |
| Cross-organisation access | Another organization's resources are denied | `OrgAccessIT` and ownership checks in `OpsControllerTest`. | Contract coverage is limited to the exercised API routes. |
| Deployment | Deployed stack is ready and serves the demo surface | `ci-k8s`, `ci-terraform`, and deployment smoke checks in `cd.yml`. | This document does not claim a deployment run passed. |
| Savepoint restore | Restore uses the savepoint and produces matching output | `SavepointTest` and `demo-e2e.sh` step 9 (savepoint restore followed by reference comparison). | Compatibility rejection cases are unit-tested in `SavepointTest`. |

The tests print benchmark/comparison details only when run; this document does not claim an observed run, input/output rates, throughput, latency percentiles, checkpoint duration, or recovery time.
