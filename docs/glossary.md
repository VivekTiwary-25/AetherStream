# Glossary

One-line definitions for terms an examiner will hear, and the file in this repo where that idea is specified or implemented. A path is a place to open. It is not a claim that a test run passed. **Planned** means the thing is not built in this tree.

| Term | Definition | Where it appears |
|---|---|---|
| Event time | When the event happened in simulation time, as epoch milliseconds, not the wall clock. | `docs/contracts/01-event-envelope-and-simulator.md` |
| Processing time | Wall-clock time when the system handles a record or a heartbeat; windows do not close on it. | `docs/report/project-report.md` |
| Watermark | Per partition, the highest event time seen so far minus allowed lateness; a progress policy, not a proof that nothing older will arrive. | `docs/contracts/02-pipeline-and-semantics.md` |
| Tumbling window | A fixed, non-overlapping event-time bucket `[floor(t / size) * size, start + size)`. | `docs/contracts/02-pipeline-and-semantics.md` |
| Allowed lateness | `outOfOrdernessMs`, how far behind the partition max an event may be and still enter an open window (5000 ms on the demo). | `docs/contracts/02-pipeline-and-semantics.md` |
| Keyed state | Per-key window aggregates, the set of absorbed event ids, and rule streaks, stored in the partition snapshot. | `compiler/src/main/java/io/aetherstream/compiler/ops/PartitionPipeline.java` |
| Partition | One of the six logs of topic `truck-events`; ownership, watermark, and replay are all per partition. | `docs/contracts/00-stack-and-layout.md` |
| Offset | The Kafka position of a record; a checkpoint stores the next offset the source has not yet queued. | `docs/contracts/03-coordinator-worker-protocol.md` |
| Consumer group | Kafka's own rebalance group, which this engine does not use because workers are manually assigned by the coordinator. | `docs/architecture.md` |
| Barrier | A checkpoint mark that flows through the same queues as records, so everything already queued is before the cut. | `docs/contracts/03-coordinator-worker-protocol.md` |
| Aligned checkpoint | A cut taken only when the barrier has passed source, operator, and sink on every partition, not a snapshot of memory at a random moment. | `docs/architecture.md` |
| Snapshot | The operator state at a barrier: window aggregates, event-id sets, rule streaks, and `maxTs`. | `docs/contracts/02-pipeline-and-semantics.md` |
| Manifest | The JSON restore handle, uploaded only after every partition has acked; `COMPLETED` is set only after that upload succeeds. | `docs/contracts/03-coordinator-worker-protocol.md` |
| Savepoint | A retained copy of a completed checkpoint manifest for a compatible restart, implemented on the coordinator and not exposed by the browser API. | `engine/src/main/java/io/aetherstream/engine/coordinator/SavepointInfo.java` |
| Generation / fencing token | The `generation` column on `executions`, bumped before reassignment so a stale worker cannot commit or complete a checkpoint. | `docs/contracts/03-coordinator-worker-protocol.md` |
| Idempotent upsert | A result write keyed by the table primary key, so a replay replaces the same row instead of inserting a second one. | `infra/db/schema.sql` |
| At-least-once | After a failure the source seeks to the checkpoint offset and may process later records more than once. | `docs/contracts/03-coordinator-worker-protocol.md` |
| Effectively-once | Postgres results match one application of each input because they are deterministic and upserted by key, which is not a general exactly-once sink. | `docs/contracts/03-coordinator-worker-protocol.md` |
| Backpressure | A full queue blocks the upstream stage; reported when any queue is at least 90 percent full. | `engine/src/main/java/io/aetherstream/engine/worker/TaskRuntime.java` |
| Bounded queue | The source queue and the sink queue, each of capacity 1000, which block instead of growing without limit. | `engine/src/main/java/io/aetherstream/engine/worker/TaskRuntime.java` |
| Reference executor | A single-process replay of the same operators over one checkpoint's offset range, used by "Compare with reference". | `reference/src/main/java/io/aetherstream/reference/ReferenceExecutor.java` |
| DAG | The pipeline graph: one source, fan-out allowed, cycles and unreachable nodes rejected. | `docs/contracts/02-pipeline-and-semantics.md` |
| Operator chaining | Operators of one partition run inside one `PartitionPipeline` on the operator thread, not as separate networked tasks. | `compiler/src/main/java/io/aetherstream/compiler/ops/PartitionPipeline.java` |
| Heartbeat | A worker POST every 1000 ms that carries commands back on the response; no heartbeat for 4000 ms and the worker is dead. | `docs/contracts/03-coordinator-worker-protocol.md` |
| KRaft | Kafka's built-in metadata mode; the Compose broker is one KRaft node and does not use ZooKeeper. | `docs/contracts/00-stack-and-layout.md` |
| S3 / MinIO | The checkpoint object store: the S3 API against community MinIO, bucket `aether-checkpoints`. | `docs/contracts/00-stack-and-layout.md` |
| GHCR | GitHub Container Registry, where `cd.yml` publishes the four application images tagged with the commit SHA. | `docs/ci-cd.md` |
| Self-hosted runner | The demo-PC runner labelled `aether-deploy` that pulls those images and starts Compose, and that does not run on pull requests. | `.github/workflows/cd.yml` |
| IaC | Infrastructure as code: Compose files and workflow YAML exist here, while Terraform is **planned** and is not in this tree. | `docs/ci-cd.md` |
| Smoke test | A short readiness check: `infra/scripts/smoke.sh` for the stack, and deploy smoke that requires `/api/health` UP plus `/api/version` equal to the SHA. | `infra/deploy/smoke-deploy.sh` |
| Dead letter | A `REJECTED` or `LATE` row that is stored rather than silently dropped, and that does not rewrite a fired window. | `docs/contracts/01-event-envelope-and-simulator.md` |
| Checkpoint 0 | The completed empty-state checkpoint written at execution start, which is the left edge of a reference comparison. | `docs/contracts/03-coordinator-worker-protocol.md` |
| Execution plan | The deterministic operator list, topological order with ties broken by node id, handed to workers on `ASSIGN`. | `docs/contracts/02-pipeline-and-semantics.md` |
| Protocol version | The integer 1 on every coordinator request body and in the checkpoint manifest; a mismatch is not a compatible restore. | `docs/contracts/03-coordinator-worker-protocol.md` |
| Idempotency key | The client key on start; the same key returns the same execution instead of starting a second one. | `docs/contracts/04-control-api-rest-ws.md` |
| Chandy-Lamport snapshot | The consistent-cut idea behind the aligned barrier: the mark travels with the records, so in-flight queue items are not missed. | `docs/architecture.md` |
| Idle partition | A partition that receives no records, so its watermark never advances and its open windows stay open. | `docs/contracts/02-pipeline-and-semantics.md` |
| Schema version | The envelope field that must be 1; any other value is `REJECTED` and does not move the watermark. | `docs/contracts/01-event-envelope-and-simulator.md` |
