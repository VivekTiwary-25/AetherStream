# T06: Coordinator: membership, assignment + generation fencing, barrier checkpoint coordination, failure recovery

Lane 1 (engine). Implement the coordinator side of contract 03 (all sections) exactly. This is the hardest core piece:
take care with concurrency and make it testable.

Files (create only these):
- engine/src/main/java/io/aetherstream/engine/coordinator/** (CoordinatorMain and everything coordinator-only)
- engine/src/main/java/io/aetherstream/engine/storage/** (S3 checkpoint store: `CheckpointStore` interface + `S3CheckpointStore`
  using io.minio:minio, + `InMemoryCheckpointStore` for tests; methods putJson(key, Object), getJson(key, Class), exists(key);
  creates the bucket if missing). The worker task will reuse this package.
- engine/src/main/java/io/aetherstream/engine/db/** (Hikari DataSource factory from env PG_URL/PG_USER/PG_PASSWORD; reusable)
- engine/src/main/resources/logback.xml
- engine/src/test/java/io/aetherstream/engine/coordinator/**

Do NOT create engine/src/main/java/io/aetherstream/engine/worker/** (another task). Do not edit engine/build.gradle.kts
(it already has kafka-clients, postgres, HikariCP, minio, slf4j/logback and the `application` plugin with
mainClass io.aetherstream.engine.coordinator.CoordinatorMain). If you need a dependency it lacks, say so in your result.

Design (keep it simple and correct):
- `CoordinatorMain`: reads env (contract 00), starts `com.sun.net.httpserver.HttpServer` on 8081 with the endpoints in
  contract 03 section 2 (JSON via io.aetherstream.common.Json.MAPPER, records from io.aetherstream.common.protocol/api).
- `Coordinator` core class with all logic, depending on interfaces: `ExecutionRepository` (Postgres: tables executions,
  checkpoints, audit_log from infra/db/schema.sql; plus an in-memory implementation for tests), `CheckpointStore`,
  `OffsetsLookup` (KafkaAdmin/Consumer end or beginning offsets of the topic; fake in tests), and a `Clock` (fake in tests).
  One `ScheduledExecutorService` tick every 250 ms drives: failure detection (heartbeat timeout 4000 ms), checkpoint
  trigger every CHECKPOINT_INTERVAL_MS (env, default 10000), checkpoint timeout (30 s -> ABORTED), recovery completion.
  Guard shared state with one lock (synchronized) - simple beats clever.
- Commands are queued per worker and returned (and removed) in the next heartbeat response.
- Start execution (POST /coord/executions): store execution (state RUNNING, generation 1), write checkpoint 0
  (COMPLETED, empty, offsets from OffsetsLookup per plan.startFrom), assign partitions round-robin over live workers sorted by
  workerId, queue ASSIGN commands. If no worker is alive, keep state RUNNING and assign as soon as one registers.
- Checkpoint acks: accept only matching execution + generation + checkpoint PENDING; when acks cover all partitions, write
  manifest.json to the store FIRST, then mark COMPLETED (contract 03 section 4). Duplicate acks are harmless.
- Recovery exactly as contract 03 section 6 (abort pending checkpoints, latest COMPLETED checkpoint, bump generation in the
  DB, CANCEL + ASSIGN with startOffsets from the manifest (or checkpoint 0 offsets), RECOVERING -> RUNNING when all assigned
  workers report RUNNING with the new generation, audit rows with detail JSON incl. recovery duration ms).
- Chaos: KILL to the chosen worker; SET_SINK_DELAY to all workers (remember the value and send it to newly registered workers).
- Coordinator restart: on boot, load executions in RUNNING/RECOVERING and recover them once workers re-register (wait one
  heartbeat timeout).
- GET /coord/state returns ClusterState (contract 03 section 7).

Unit tests (in-memory repo/store, fake clock, no network), at least:
- two workers register; start execution; partitions 0..5 split [0,2,4]/[1,3,5]; ASSIGN carries generation 1 and checkpoint 0 offsets.
- checkpoint 1: TRIGGER sent to both; ack from one only means not COMPLETED and no manifest; second ack means manifest written,
  then COMPLETED with merged offsets (verify ordering: manifest exists when state flips).
- stale ack (old generation) is rejected.
- worker heartbeat stops for 4 s: execution goes RECOVERING, generation becomes 2, pending checkpoint ABORTED, survivor gets
  CANCEL + ASSIGN for all 6 partitions with startOffsets from the last COMPLETED manifest; when it reports RUNNING with
  generation 2 the state is RUNNING and restoredFromCheckpoint = that checkpoint id.
- an incomplete checkpoint (PENDING/ABORTED with some worker files) is never chosen for recovery.
- HTTP smoke test: start the server on a random port with in-memory deps, register + heartbeat via java.net.http.HttpClient.

Acceptance check: `ci-java` workflow green on your branch (`./gradlew build` runs your tests).

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql. If a contract looks wrong, follow it anyway and say so in your result.
- Never open, read or change anything in swarm/.
- Only create/edit the files listed under "Files". If you truly need another file, say so in your result instead of editing it.
- Your PC may not have Java, Node or Docker. The acceptance check runs in GitHub Actions on your task branch (CI runs on push to task/**). Write code carefully so it compiles first time; keep it simple and readable.
- Do not commit secrets, build outputs, node_modules or .gradle/ folders.
- In your result, list what you built, how the acceptance check proves it, and anything left undone.
