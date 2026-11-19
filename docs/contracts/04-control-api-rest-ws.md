# Contract 04: control-api REST + WebSocket (what the UI talks to)

Spring Boot, port 8080. All JSON. All paths below are under `/api` except `/ws`. Errors:
`{ "error": "CODE", "message": "plain English" }` with a 4xx/5xx status. CORS open in dev.
The web container (nginx) proxies `/api/*` and `/ws` to control-api, so the browser uses same-origin relative URLs.

## REST
| Method/path | Request | Response 200 |
|---|---|---|
| GET /api/health | - | `{"status":"UP"}` (process only) |
| GET /api/ready | - | `{"status":"UP","checks":{"postgres":"UP","kafka":"UP","coordinator":"UP","simulator":"UP"}}`; 503 + same shape if any DOWN |
| GET /api/version | - | `{"commit":"<GIT_SHA env>","builtAt":"<BUILT_AT env or empty>"}` |
| POST /api/pipelines/validate | PipelineDefinition | `{"valid":true,"errors":[],"plan":ExecutionPlan|null}` (always 200; errors per contract 02 §3) |
| POST /api/pipelines | PipelineDefinition | `{"id","version","name","createdAt"}`; 400 `INVALID_PIPELINE` with `errors` array if invalid. Same id -> next version |
| GET /api/pipelines | - | `[{"id","version","name","createdAt"}]` latest version of each |
| GET /api/pipelines/{id} | - | PipelineDefinition (latest version); 404 |
| GET /api/pipelines/demo | - | the demo pipeline (contract 02 §1) |
| POST /api/pipelines/{id}/executions | `{"idempotencyKey":"uuid"}` | ExecutionInfo. Only one execution RUNNING at a time: starting a new one stops the old one. Same idempotencyKey -> same execution |
| GET /api/executions/current | - | ExecutionInfo or 204 |
| GET /api/executions/{id} | - | ExecutionInfo |
| POST /api/executions/{id}/stop | - | ExecutionInfo |
| GET /api/executions/{id}/checkpoints?limit=20 | - | `[{"checkpointId","state","generation","durationMs","completedAt","sourceOffsets"}]` newest first |
| GET /api/workers | - | `ClusterState.workers` (contract 03 §7) |
| GET /api/results/windows?sinkId=speedSink&limit=200 | - | `[{"sinkId","key","windowStart","windowEnd","values":{},"eventCount"}]` newest windowEnd first, current execution |
| GET /api/results/alerts?limit=50 | - | `[{"alertId","key","ruleId","message","eventTime"}]` newest first |
| GET /api/results/dead-letters?kind=LATE|REJECTED&limit=50 | - | `[{"eventId","kind","reason","partition","offset","eventTime"}]` newest first |
| GET /api/results/summary | - | `{"executionId","windowsWritten","alerts","rejected","late","deliveriesLastMinute","avgSpeedLastMinute","eventsPerSec"}` |
| POST /api/chaos/late-events | `{"count":20,"lateByMs":180000}` | proxied to simulator `/chaos/late` |
| POST /api/chaos/duplicates | `{"count":20}` | proxied to simulator |
| POST /api/chaos/malformed | `{"count":5}` | proxied to simulator |
| POST /api/chaos/slow-sink | `{"delayMs":50}` (0 = off) | proxied to coordinator `/coord/chaos/sink-delay` |
| POST /api/chaos/kill-worker | `{"workerId":null}` | proxied to coordinator `/coord/chaos/kill` -> `{"workerId"}` |
| GET /api/simulator/state | - | proxied simulator `/state` |
| POST /api/simulator/trucks | `{"x","y"}` | proxied simulator `POST /trucks` |
| POST /api/compare | `{}` | CompareResult (below) |

`ExecutionInfo`: `{"id","pipelineId","pipelineVersion","state":"CREATED|RUNNING|RECOVERING|STOPPED|FAILED","generation",
"restoredFromCheckpoint":null|N,"lastCompletedCheckpoint":N,"createdAt"}`

### Compare with reference (POST /api/compare)
1. Take the latest COMPLETED checkpoint C of the current execution and checkpoint 0. For each partition p read Kafka records
   with offsets in `[cp0.offset[p], C.offset[p])`.
2. Run `io.aetherstream.reference.ReferenceExecutor` (one PartitionPipeline per partition, contract 02 §4) over them,
   collecting window results (per sink), alerts and final watermark `W_p` per window node and partition.
3. Every reference row must exist in Postgres with equal `eventCount` and values (|diff| <= 1e-6) -> matched, else mismatch.
   Every Postgres window row for a key seen in partition p with `window_end <= W_p` (for its window node) that the reference
   did not produce is an `EXTRA` mismatch.
4. Response: `{"checkpointId":C,"referenceResults":4812,"engineResults":4812,"matched":4812,"mismatches":[{"type":"MISSING|DIFFERENT|EXTRA","sinkId","key","windowStart","detail"}],"recordsReplayed":123456,"durationMs":900,"match":true}`
   (`mismatches` capped at 50; `match` = no mismatches).

## WebSocket `/ws`
Plain WebSocket (no STOMP). Server -> client messages only, each `{"type": "...", "ts": epochMs, "data": ...}`.
The client reconnects with backoff 1 s -> 10 s. On connect the server sends one `snapshot`.
| type | when | data |
|---|---|---|
| `snapshot` | on connect | `{"execution":ExecutionInfo|null,"workers":[...],"summary":{...},"simulator":{simulator /state}}` |
| `trucks` | every 250 ms | `{"simTime", "trucks":[{"truckId","x","y","speedKmh","heading","status"}]}` (from simulator /state) |
| `flow` | every 1 s | `{"eventsPerSec": 120.5, "byPartition": {"0": 20.1, ...}, "rejectedPerSec": 0.2, "latePerSec": 0}` |
| `windows` | new rows | `[{"sinkId","key","windowStart","windowEnd","values","eventCount"}]` (poll Postgres every 1 s for new rows) |
| `alerts` | new rows | `[{"alertId","key","ruleId","message","eventTime"}]` |
| `deadLetters` | new rows | `[{"eventId","kind","reason","partition","offset","eventTime"}]` |
| `workers` | every 1 s | ClusterState.workers |
| `execution` | on any change | ExecutionInfo plus `"event": "STARTED|CHECKPOINT_COMPLETED|WORKER_DEAD|RECOVERING|RECOVERED|STOPPED"`, `"checkpointId"`, `"workerId"` when relevant; on `RECOVERED` also `"durationMs"` (ms from the WORKER_DEAD detection to RECOVERED, measured by the server) |

UI states (must be shown with text + colour): running, backpressured (any worker `backpressured`), checkpointing,
recovering (execution RECOVERING: "Recovering from checkpoint #N"), paused/stopped, failed.
