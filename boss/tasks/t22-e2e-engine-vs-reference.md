# T22: End-to-end CI test: real Kafka/Postgres/MinIO + coordinator + 3 workers == reference executor (M1)

Lane 8 (tests). Prove the whole engine computes exactly what the reference computes. Everything it needs is on main:
- tests/src/test/java/io/aetherstream/tests/support/IntegrationInfrastructure.java (Testcontainers Kafka/Postgres/MinIO singletons, schema, topic, bucket)
- engine coordinator (`io.aetherstream.engine.coordinator.*`: Coordinator, CoordinatorHttpServer, PostgresExecutionRepository,
  KafkaOffsetsLookup), storage (`S3CheckpointStore`), worker (`io.aetherstream.engine.worker.*`)
- `io.aetherstream.reference.*` (ReferenceExecutor, KafkaRangeReader, ResultComparator) - this may NOT be on main yet when you start.
  If it is missing, compute the expected result yourself in the test: read the Kafka range per partition with a plain
  KafkaConsumer, run one `io.aetherstream.compiler.ops.PartitionPipeline` per partition with a `ListCollector`, and compare
  window rows/alerts to Postgres (MISSING/DIFFERENT/EXTRA as contract 04 step 3). That IS the reference semantics.
- `io.aetherstream.simulator.Simulation` (pure deterministic event generator)
- `io.aetherstream.compiler.PipelineCompiler.demoPipeline()`
Read their public APIs first. If something you need is missing (e.g. a way to start a worker in-process with explicit config),
do NOT edit engine/; write the test against what exists, and describe the missing hook in your result.

Files (create/edit only these):
- tests/src/test/java/io/aetherstream/tests/EngineMatchesReferenceIT.java
- tests/src/test/java/io/aetherstream/tests/support/EngineCluster.java (starts the coordinator HTTP server on a random port and
  N workers in-process in the same JVM against the Testcontainers services; `stop()`, `worker(i)`, `coordinatorUrl()`)
- tests/src/test/java/io/aetherstream/tests/support/WorkloadGenerator.java (uses Simulation with seed 42 to produce a fixed
  workload: 8 trucks, 40 sim-minutes, plus 20 late, 20 duplicate, 5 malformed records)
- tests/build.gradle.kts: add `testImplementation(project(":simulator"))` and anything else needed

Test (tag "integration"):
1. Produce the whole workload to `truck-events` (key = truckId) BEFORE starting; pipeline = demo pipeline but with source
   `startFrom: "earliest"` (copy the definition and change it).
2. Start the coordinator (CHECKPOINT_INTERVAL_MS = 2000) + 3 workers; start the execution through the coordinator HTTP API.
3. Wait (max 120 s) until a COMPLETED checkpoint's source_offsets equal the end offsets of all 6 partitions (all input consumed
   and committed).
4. Load window_results and alerts for the execution from Postgres; run KafkaRangeReader from checkpoint 0 offsets to that
   checkpoint's offsets; ReferenceExecutor; ResultComparator.
5. Assert `match == true`, matched == referenceResults > 100, at least one alert "truck-007 has not moved for 10 min",
   dead_letters has REJECTED >= 5 and LATE >= 1. Print the CompareResult JSON to stdout (it shows in the CI log).

Acceptance check: `ci-integration` workflow green on your branch, log shows EngineMatchesReferenceIT PASSED and the
CompareResult with match=true.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql. If a contract looks wrong, follow it anyway and say so in your result.
- Do not change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only create/edit the files listed under "Files". If you truly need another file, say so in your result instead of editing it.
- Your PC may not have Java, Node or Docker. The acceptance check runs in GitHub Actions on your task branch (CI runs on push to task/**). Write code carefully so it compiles first time; keep it simple and readable.
- Do not commit secrets, build outputs, node_modules or .gradle/ folders.
- In your result, list what you built, how the acceptance check proves it, and anything left undone.
