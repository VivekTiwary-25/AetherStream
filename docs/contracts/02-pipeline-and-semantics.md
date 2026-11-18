# Contract 02: pipeline definition JSON, compiler, operator semantics, execution plan

## 1. Pipeline definition (JSON, Java record `io.aetherstream.common.pipeline.PipelineDefinition`)
```json
{
  "id": "logistics-demo",
  "version": 1,
  "name": "Truck city: speed, deliveries, stuck trucks",
  "nodes": [
    { "id": "src",      "type": "source", "label": "Truck events", "position": {"x": 0, "y": 0},
      "config": { "topic": "truck-events", "startFrom": "latest" } },
    { "id": "pos",      "type": "filter", "config": { "conditions": [ {"field": "type", "op": "==", "value": "truck.position"} ] } },
    { "id": "kb1",      "type": "keyBy",  "config": { "field": "key" } },
    { "id": "speed1m",  "type": "window", "config": { "kind": "tumbling", "sizeMs": 60000, "outOfOrdernessMs": 5000,
        "aggregations": [ {"name": "avgSpeedKmh", "fn": "avg", "field": "payload.speedKmh"},
                          {"name": "maxSpeedKmh", "fn": "max", "field": "payload.speedKmh"},
                          {"name": "count", "fn": "count"} ] } },
    { "id": "speedSink","type": "sink",   "config": { "kind": "postgres.windows" } },
    { "id": "stuck",    "type": "rule",   "config": { "ruleId": "stuck-truck",
        "condition": {"field": "avgSpeedKmh", "op": "<", "value": 1.0}, "forWindows": 10,
        "message": "{key} has not moved for {minutes} min" } },
    { "id": "alertSink","type": "sink",   "config": { "kind": "postgres.alerts" } },
    { "id": "del",      "type": "filter", "config": { "conditions": [ {"field": "type", "op": "==", "value": "truck.delivery"} ] } },
    { "id": "kb2",      "type": "keyBy",  "config": { "field": "key" } },
    { "id": "del1m",    "type": "window", "config": { "kind": "tumbling", "sizeMs": 60000, "outOfOrdernessMs": 5000,
        "aggregations": [ {"name": "deliveries", "fn": "count"}, {"name": "parcels", "fn": "sum", "field": "payload.parcels"} ] } },
    { "id": "delSink",  "type": "sink",   "config": { "kind": "postgres.windows" } }
  ],
  "edges": [
    {"from": "src", "to": "pos"}, {"from": "pos", "to": "kb1"}, {"from": "kb1", "to": "speed1m"},
    {"from": "speed1m", "to": "speedSink"}, {"from": "speed1m", "to": "stuck"}, {"from": "stuck", "to": "alertSink"},
    {"from": "src", "to": "del"}, {"from": "del", "to": "kb2"}, {"from": "kb2", "to": "del1m"}, {"from": "del1m", "to": "delSink"}
  ]
}
```
This exact pipeline is the **demo pipeline**; store it as `compiler/src/main/resources/pipelines/logistics-demo.json`.
`label` and `position` are optional, for the editor only; the compiler ignores them.

## 2. Node types and config
| type | input | output | config |
|---|---|---|---|
| source | - | EVENT | `topic` (string, required), `startFrom` `"latest"` (default) or `"earliest"` |
| filter | EVENT | EVENT | `conditions`: non-empty list, all must hold (AND). Condition `{field, op, value}`; op one of `== != > >= < <=`; `value` string, number or boolean |
| map | EVENT | EVENT | `ops`: non-empty list of `{op:"scale", field, factor, as}` (as = field*factor), `{op:"rename", field, as}`, `{op:"drop", field}`, `{op:"set", as, value}`. Targets (`as`, dropped `field`) must start with `payload.` |
| keyBy | EVENT | KEYED | `field`: must be `key` or `payload.truckId` in v1 (equals the Kafka partition key, so no repartitioning is needed) |
| window | KEYED | WINDOW_RESULT | `kind:"tumbling"`, `sizeMs` (1000..3600000), `outOfOrdernessMs` (0..600000, default 5000), `aggregations` non-empty list of `{name, fn, field?}`; fn one of `count sum avg min max`; `field` required unless fn is `count`; names unique |
| rule | WINDOW_RESULT | ALERT | `ruleId` string, `condition {field, op, value}` where field is an aggregation name of the upstream window, `forWindows` int >= 1, `message` template with `{key}` and `{minutes}` |
| sink | WINDOW_RESULT or ALERT | - | `kind`: `postgres.windows` (needs WINDOW_RESULT) or `postgres.alerts` (needs ALERT) |

Field paths: `type`, `key`, `eventTime`, `eventId`, or `payload.<name>` (one level). A missing field makes a filter
condition false, and is skipped by scale/rename/aggregations (count still counts the record).
Comparisons: numbers compare numerically; strings/booleans only support `==` and `!=`.

## 3. Compiler (`io.aetherstream.compiler.PipelineCompiler`)
`ValidationResult validate(PipelineDefinition)` and `ExecutionPlan plan(PipelineDefinition, int partitions)` (plan throws
if invalid). Errors: `{ "nodeId": "speed1m" | null, "code": "TYPE_MISMATCH", "message": "..." }`. All errors are collected
(do not stop at the first). Messages are plain English and say how to fix it. Codes:
`NO_SOURCE`, `MULTIPLE_SOURCES`, `NO_SINK`, `DUPLICATE_NODE_ID`, `UNKNOWN_NODE_TYPE`, `EDGE_UNKNOWN_NODE`, `CYCLE`,
`MULTIPLE_INPUTS` (v1: every non-source node has exactly one input), `UNREACHABLE_NODE` (not reachable from the source),
`DANGLING_NODE` (non-sink node with no outgoing edge), `TYPE_MISMATCH` (e.g. "Window 'speed1m' needs a Key-by before it",
"Sink 'alertSink' of kind postgres.alerts needs a Rule before it"), `INVALID_CONFIG` (message names the config field),
`UNSUPPORTED_KEY` ("Key-by 'kb1': only 'key' or 'payload.truckId' is supported in v1 because it matches the Kafka partition key").

## 4. Operator semantics (normative; engine and reference both use `io.aetherstream.compiler.ops`)
Processing is **per source partition**, in offset order. For each Kafka record (partition p, offset o):
1. Validate with `EnvelopeValidator` (contract 01 §2). Invalid -> emit DeadLetter(REJECTED). Stop.
2. Route the envelope from the source along the DAG. Fan-out: children in ascending node-id order.
   filter/map/keyBy transform or drop; window assigns it to window `[floor(t/size)*size, +size)` for its key.
   - **Late check** (at each window): let `W = maxTs_p - outOfOrdernessMs` be the watermark *before* this record.
     If `windowEnd <= W` the record is LATE: emit DeadLetter(LATE, reason `"window [start,end) already closed (watermark W)"`)
     once per event (if several windows find it late, only the first in node-id order emits). Not added.
   - Duplicate eventId in the same key-window: ignored (not a dead letter).
3. Then set `maxTs_p = max(maxTs_p, eventTime)` (initially Long.MIN_VALUE) and, for every window node, fire all open windows
   of partition p with `windowEnd <= maxTs_p - outOfOrdernessMs`, ordered by (windowEnd, key). Firing emits
   `WindowResult(nodeId, key, windowStart, windowEnd, values, eventCount)` downstream and discards that window's state.
   `values`: map aggregation name -> double (count as double). avg = sum/count of records that had the field; if no record had
   the field the value is omitted. eventCount = number of distinct eventIds absorbed.
4. Rule: per key, a streak counter of consecutive results (a result is consecutive if its windowStart == previous windowEnd
   for that key) satisfying the condition. When the counter becomes exactly `forWindows`, emit
   `Alert(alertId = ruleNodeId + "|" + key + "|" + windowStart, key, ruleId, message, eventTime = windowEnd)`.
   `{minutes}` = forWindows * sizeMs / 60000. A failing or non-consecutive result resets the counter (a non-consecutive
   satisfying result restarts it at 1).
5. Sinks receive WindowResult / Alert and write them (contract 03 §5) with `sink_id` = sink node id.

Watermarks are per partition, so results never depend on how partitions interleave or which worker owns them. A partition
that receives no records never advances its watermark (its last windows stay open); that is the declared idle policy.

`PartitionPipeline` (in ops) is the unit both executors run: `new PartitionPipeline(plan, partition)`,
`void process(long offset, String rawValue, Collector out)`, `JsonNode snapshot()`, `static PartitionPipeline restore(plan, partition, JsonNode)`,
`long maxTs()`. Snapshot captures all window state (per node, per key, per window: aggregates + eventId set), rule streaks and maxTs.
`snapshot()` -> `restore()` -> continue must produce exactly the same outputs as never stopping (unit-tested).

## 5. Execution plan (`io.aetherstream.common.pipeline.ExecutionPlan`)
```json
{ "pipelineId": "logistics-demo", "pipelineVersion": 1, "topic": "truck-events", "partitions": 6, "startFrom": "latest",
  "operators": [ { "id": "src", "type": "source", "config": {...}, "inputs": [], "outputType": "EVENT" } ] }
```
`operators` are in topological order, ties broken by node id (deterministic). `outputType` is EVENT, KEYED, WINDOW_RESULT, ALERT or NONE.
