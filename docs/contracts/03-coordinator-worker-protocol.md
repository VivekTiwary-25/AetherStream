# Contract 03: coordinator <-> worker protocol, checkpoints, recovery, fencing, sinks

Protocol version: `"protocolVersion": 1` in every request body. HTTP/JSON. Coordinator listens on 8081.
Workers never accept inbound connections for the protocol: all commands are piggybacked on heartbeat responses.
Records in `io.aetherstream.common.protocol`.

## 1. Roles
- **Coordinator** (single active instance, state in Postgres tables `executions`, `checkpoints`): worker membership,
  partition assignment, execution generation (fencing token), checkpoint triggering and completion, failure detection, recovery.
- **Worker**: runs one `PartitionPipeline` (contract 02 §4) per assigned partition inside a 3-stage pipelined runtime:
  `source thread` (KafkaConsumer, manual `assign`) -> bounded queue (capacity 1000) -> `operator thread` -> bounded queue
  (capacity 1000) -> `sink thread` (Postgres, batched). The operator thread puts exactly one `OutputBatch` item per input record
  (its 0..n outputs + source position) or one Barrier into the sink queue. Full queues block the upstream stage: that is backpressure.

## 2. Endpoints (coordinator)
| Method/path | Request | Response |
|---|---|---|
| POST /internal/workers/register | `{protocolVersion, workerId?, host}` | `{workerId, heartbeatIntervalMs: 1000, heartbeatTimeoutMs: 4000}` |
| POST /internal/workers/{workerId}/heartbeat | `WorkerStatus` (below) | `{commands: [Command...]}` |
| POST /internal/checkpoints/ack | `CheckpointAck` | `{accepted: bool, reason?}` |
| GET /coord/health | - | `{"status":"UP"}` |
| GET /coord/state | - | `ClusterState` (below; used by control-api) |
| POST /coord/executions | `{executionId, plan}` | `ExecutionInfo` — start: generation 1, writes checkpoint 0 |
| POST /coord/executions/{id}/stop | - | `ExecutionInfo` |
| POST /coord/executions/{id}/checkpoint | - | `{checkpointId}` — trigger one now |
| POST /coord/chaos/kill | `{workerId?}` (default: worker owning the most partitions) | `{workerId}` — sends KILL |
| POST /coord/chaos/sink-delay | `{delayMs}` (0 = off) | `{delayMs}` — sent to all workers as SET_SINK_DELAY |

`WorkerStatus`: `{protocolVersion, workerId, executionId?, generation, state: IDLE|RESTORING|RUNNING, partitions:[int],
 sourceQueue: {size, capacity}, sinkQueue: {size, capacity}, backpressured: bool (any queue >= 90% full),
 processedRecords: long, recordsPerSec: double, lastCheckpointId: long, lag: {partition: long}}`

`Command` (`type` discriminator):
- `ASSIGN {executionId, generation, plan, partitions:[int], restoreCheckpointId, manifestKey?, startOffsets:{p:offset}}`
  Worker: stop current work, for each partition restore state from the manifest (or empty if checkpoint 0), seek to
  `startOffsets[p]`, start running, report state RUNNING with this generation. Idempotent for the same generation.
- `TRIGGER_CHECKPOINT {executionId, generation, checkpointId}`
- `CANCEL {executionId, generation}` — stop processing (used before reassignment and on stop).
- `KILL {}` — chaos: worker calls `Runtime.getRuntime().halt(137)` immediately (a real crash: no cleanup, no ack).
- `SET_SINK_DELAY {delayMs}` — sink thread sleeps delayMs per item it takes from the sink queue (simulates a slow output; queues fill up, upstream blocks, lag grows).

Any command whose `generation` is lower than the worker's current generation is ignored.

## 3. Assignment
Partitions 0..N-1 are spread round-robin over live workers sorted by workerId. Assignment happens on execution start
and on every recovery. Every (re)assignment increments `executions.generation` first (fencing token), then sends
CANCEL to all workers for the old generation and ASSIGN with the new one.

## 4. Checkpoints (aligned barriers, Chandy–Lamport style)
- Coordinator triggers every `CHECKPOINT_INTERVAL_MS` (default 10000 real ms) while RUNNING, checkpoint ids 1,2,3...
  Inserts `checkpoints(state=PENDING, generation)`.
- Worker on TRIGGER_CHECKPOINT: the **source thread** records, for each partition, the next offset it has not yet put in
  the queue, then enqueues `Barrier(checkpointId)` behind all records already queued. The **operator thread**, when the
  barrier reaches it, takes `snapshot()` of every PartitionPipeline and forwards the barrier. The **sink thread**, when the
  barrier reaches it, flushes and commits every output that came before the barrier (fenced, §5), then uploads
  `executions/{executionId}/checkpoints/{checkpointId}/worker-{workerId}.json` =
  `{checkpointId, generation, workerId, partitions: {"<p>": {"nextOffset": o, "state": <snapshot JSON>}}}` to S3, then acks.
- `CheckpointAck`: `{protocolVersion, workerId, executionId, generation, checkpointId, partitions: {"<p>": nextOffset}, objectKey}`.
- The coordinator accepts an ack only if generation == current generation. When acks cover **all** partitions, it writes
  `executions/{executionId}/checkpoints/{checkpointId}/manifest.json` =
  `{executionId, checkpointId, generation, pipelineId, pipelineVersion, protocolVersion, createdAt,
    partitions: {"<p>": {"nextOffset": o, "objectKey": "..."}}}`, and only **after** the manifest upload succeeds sets
  `checkpoints.state = COMPLETED`, `manifest_key`, `source_offsets`, `completed_at`, `duration_ms`.
- A checkpoint not COMPLETED within 30 s, or interrupted by a failure, is set ABORTED. Restore only ever uses the latest
  COMPLETED checkpoint (never PENDING/ABORTED, even if some worker files exist).
- Checkpoint 0 is written at execution start: COMPLETED, empty state, `source_offsets` = end offsets of the topic
  (`startFrom: latest`) or beginning offsets (`earliest`). This is also the start point for reference comparison.

## 5. Sinks, output guarantee and fencing
- Sinks upsert by primary key (`infra/db/schema.sql`): `window_results (execution_id, sink_id, key, window_start)`,
  `alerts (execution_id, sink_id, alert_id)`, `dead_letters (execution_id, event_id, kind)`.
- Every sink batch is one DB transaction that starts with
  `SELECT generation FROM executions WHERE id = ? FOR SHARE` and **aborts without writing** if it differs from the worker's
  generation (the stale worker then stops and reports IDLE). The coordinator bumps generation with `UPDATE ... SET generation = generation + 1`
  (takes a row lock, so it serialises with in-flight sink transactions).
- Declared guarantee: input is replayed at-least-once after failure; operator state is exactly-once per input because state
  and source offsets are restored together from one consistent checkpoint; the Postgres result tables are effectively
  exactly-once because outputs are deterministic and upserted by key. No other sink gets this claim.

## 6. Failure detection and recovery
- A worker missing heartbeats for `heartbeatTimeoutMs` (4000) is declared DEAD.
- If it owned partitions of a RUNNING execution: state -> RECOVERING; abort pending checkpoints; load latest COMPLETED
  checkpoint N; bump generation; set `restored_from_checkpoint = N`; reassign all partitions over live workers with
  `ASSIGN {restoreCheckpointId: N, manifestKey, startOffsets = manifest offsets}`; when every assigned worker reports
  RUNNING with the new generation -> state RUNNING. Audit log rows `worker.dead`, `execution.recovering`, `execution.recovered`
  (detail includes checkpointId, generation, recovery duration ms).
- A restarted worker registers with a new workerId and becomes available for the next assignment.
- Coordinator restart: reads `executions` with state RUNNING/RECOVERING, waits one heartbeat timeout for workers to
  re-register, then performs recovery as above (single-coordinator baseline).

## 7. ClusterState (GET /coord/state)
```json
{ "execution": { "id": "...", "pipelineId": "...", "state": "RUNNING", "generation": 3, "restoredFromCheckpoint": 12,
                 "lastCompletedCheckpoint": 15, "lastCheckpointDurationMs": 84 },
  "workers": [ { "workerId": "w-1a2b", "alive": true, "state": "RUNNING", "partitions": [0,3],
                 "sourceQueue": {"size": 3, "capacity": 1000}, "sinkQueue": {"size": 0, "capacity": 1000},
                 "backpressured": false, "recordsPerSec": 41.5, "lastHeartbeatMs": 1795251600123 } ],
  "sinkDelayMs": 0 }
```
