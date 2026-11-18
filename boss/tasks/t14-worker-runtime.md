# T14: Worker runtime: 3-stage pipelined executor, aligned barrier snapshots, fenced Postgres sink, restore

Lane 1 (engine). Implement the worker side of contract 03 exactly. The coordinator (engine/.../coordinator/**), the S3
store (engine/.../storage/**: CheckpointStore, S3CheckpointStore, InMemoryCheckpointStore) and DataSources
(engine/.../db/DataSources) are already on main: reuse them, do not change them (if you must, say why in your result).
The operator semantics are already on main in `io.aetherstream.compiler.ops` (PartitionPipeline, EnvelopeValidator,
Collector, ListCollector): the worker must run exactly those, never re-implement operators.

Files (create only these):
- engine/src/main/java/io/aetherstream/engine/worker/** (WorkerMain and everything worker-only)
- engine/src/test/java/io/aetherstream/engine/worker/**

Design (contract 03 sections 1, 2, 4, 5):
- `WorkerMain`: env (contract 00, plus `WORKER_HOST` default hostname), registers with the coordinator
  (POST /internal/workers/register; retry every 1 s until it works), then a heartbeat loop every heartbeatIntervalMs
  sending WorkerStatus and executing returned Commands in order. Tiny health server on 8090 (`GET /health` -> {"status":"UP"}).
- `TaskRuntime` (one per ASSIGN): three threads joined by two `ArrayBlockingQueue`s of capacity 1000:
  1. source thread: KafkaConsumer (`assign` the partitions, `seek` to startOffsets, `enable.auto.commit=false`,
     `max.poll.records=500`, String deserializers) puts `RecordItem(partition, offset, value)` into queue 1 (blocking put =
     backpressure). It checks for pending barrier requests between records: on TRIGGER_CHECKPOINT it records for each assigned
     partition the next offset not yet put into the queue (position tracking per partition, NOT consumer.position after a
     poll batch), then puts `BarrierItem(checkpointId, offsets)`.
  2. operator thread: one `PartitionPipeline` per partition (fresh, or `PartitionPipeline.restore(plan, p, state)` from the
     WorkerSnapshot referenced by the manifest of `restoreCheckpointId`; checkpoint 0 = fresh). For each RecordItem calls
     `process(offset, value, collector)` and puts exactly one `OutputBatch(partition, offset, outputs)` into queue 2.
     On a BarrierItem: snapshot every PartitionPipeline, then put `BarrierItem` (with the snapshots) into queue 2.
  3. sink thread: accumulates outputs and writes them in batches (flush at 200 outputs or every 200 ms or on barrier) in ONE
     JDBC transaction: `SELECT generation FROM executions WHERE id = ? FOR SHARE`; if != my generation -> rollback, stop this
     TaskRuntime, report IDLE (fenced). Else upsert window_results / alerts / dead_letters (`INSERT ... ON CONFLICT (pk) DO
     UPDATE SET ...`, values as jsonb) with execution_id, sink_id, generation. Honour SET_SINK_DELAY: sleep delayMs per item
     taken from queue 2. On a BarrierItem: flush+commit, then upload
     `executions/{executionId}/checkpoints/{checkpointId}/worker-{workerId}.json` (WorkerSnapshot record) via CheckpointStore,
     then POST /internal/checkpoints/ack (CheckpointAck with the barrier's offsets).
- CANCEL / new ASSIGN: stop the current TaskRuntime (interrupt threads, close consumer, discard queued items without writing),
  then start the new one. A command with a lower generation than the current one is ignored. ASSIGN with the same
  generation as the running one is a no-op.
- KILL: `Runtime.getRuntime().halt(137)` immediately.
- Status: state IDLE/RESTORING/RUNNING, partitions, queue sizes/capacity, backpressured when either queue >= 90% full,
  processedRecords, recordsPerSec (EWMA over 1 s), lastCheckpointId, lag per partition (consumer endOffsets - position,
  refreshed at most every 2 s).
- Keep Kafka, JDBC, HTTP and S3 behind small interfaces (`RecordSource`, `ResultSink`, `CoordinatorClient`) so the runtime is
  unit-testable without Docker.

Unit tests (no Docker): with a fake in-memory RecordSource (lists of records per partition), in-memory sink and
InMemoryCheckpointStore:
- outputs of the runtime for a generated workload equal the outputs of running PartitionPipeline directly (ListCollector).
- barrier: trigger checkpoint mid-stream; the ack offsets equal the count of records before the barrier; the uploaded
  snapshot restored into a new runtime starting at those offsets produces the same remaining outputs (end-to-end restore
  equivalence).
- fencing: sink returns "generation mismatch" -> runtime stops, nothing written after that.
- backpressure: with a sink delay, queue 2 fills to capacity and status.backpressured becomes true; source blocks (does not
  grow memory).

Acceptance check: `ci-java` workflow green on your branch.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql. If a contract looks wrong, follow it anyway and say so in your result.
- Do not change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only create/edit the files listed under "Files". If you truly need another file, say so in your result instead of editing it.
- Your PC may not have Java, Node or Docker. The acceptance check runs in GitHub Actions on your task branch (CI runs on push to task/**). Write code carefully so it compiles first time; keep it simple and readable.
- Do not commit secrets, build outputs, node_modules or .gradle/ folders.
- In your result, list what you built, how the acceptance check proves it, and anything left undone.
