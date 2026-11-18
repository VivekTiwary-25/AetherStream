# T03: Shared operator runtime: EnvelopeValidator + PartitionPipeline (the semantics core)

Lane 2 (compiler). This is the correctness heart of AetherStream: the exact operator semantics that BOTH the distributed
engine and the reference executor run. Implement contract 01 sections 2-3 and contract 02 sections 2 + 4 exactly.

Files (create only these):
- compiler/src/main/java/io/aetherstream/compiler/ops/** (new package; any classes you need, e.g. EnvelopeValidator,
  FieldPath, Condition, MapOp, WindowState, RuleState, Collector, PartitionPipeline)
- compiler/src/test/java/io/aetherstream/compiler/ops/** (tests)
- compiler/src/test/resources/ops/** (test fixtures, optional)

Do NOT touch compiler/src/main/java/io/aetherstream/compiler/PipelineCompiler.java or other files outside ops/ (another task
is writing the validator/planner at the same time). Use the records in :common (io.aetherstream.common.*) as they are.

What to build:
1. `EnvelopeValidator.parse(String topic, int partition, long offset, String raw)` returning either an `Envelope` or a
   `DeadLetter(REJECTED)` (e.g. a small sealed result type) with the short reasons from contract 01 section 2.
   Use `io.aetherstream.common.Json.MAPPER`.
2. `Collector` interface: `windowResult(String sinkNodeId, WindowResult r)`, `alert(String sinkNodeId, Alert a)`, `deadLetter(DeadLetter d)`.
   Sinks are plan operators of type `sink`; the collector receives outputs addressed to each sink node id.
   Also provide `ListCollector` that records every output in order (used by tests, the engine and the reference).
3. `PartitionPipeline` exactly as contract 02 section 4: `new PartitionPipeline(ExecutionPlan plan, int partition)`,
   `void process(long offset, String rawValue, Collector out)`, `JsonNode snapshot()`,
   `static PartitionPipeline restore(ExecutionPlan plan, int partition, JsonNode snapshot)`, `long maxTs()`,
   and `Map<String, Long> watermarks()` (window node id -> current watermark, used by compare).
   It walks `plan.operators()` (topological order; `inputs` give the edges). Implement filter, map, keyBy, tumbling window
   (count/sum/avg/min/max, eventId dedup per key-window, late check BEFORE the watermark update, firing AFTER the update,
   ordered by (windowEnd, key)), rule (consecutive streak, alert exactly when the streak reaches forWindows, alertId format),
   and routing to sinks. Fan-out children in ascending node-id order. Use sorted maps (TreeMap) for all state so iteration
   is deterministic.
4. Snapshot JSON must be plain Jackson JSON (objects/arrays/numbers/strings), fully deterministic (same state gives
   byte-identical JSON).
5. Tests (JUnit 5 + AssertJ). Build ExecutionPlan objects by hand (or from JSON fixtures in src/test/resources/ops) that mirror
   the demo pipeline in contract 02 section 1:
   - validator: valid record, unparseable JSON, missing field, wrong schemaVersion, key != payload.truckId, negative speed.
   - window: avg/max/count over a minute, firing only when the watermark passes the end, late record gives a LATE dead letter,
     duplicate eventId ignored.
   - rule: 10 consecutive slow windows give exactly one alert with the correct alertId and the message
     "truck-007 has not moved for 10 min"; a gap resets the streak.
   - snapshot equivalence: feed 5,000 generated records (several trucks, out-of-order within 5 s, some duplicates, late and
     malformed ones) through pipeline A without stopping; feed the same records to pipeline B but at 3 points replace B with
     `PartitionPipeline.restore(plan, p, Json.MAPPER.readTree(Json.MAPPER.writeValueAsString(B.snapshot())))`.
     Assert both produced exactly the same ordered list of outputs.
   - determinism: snapshot() of A serialised twice gives byte-identical strings.

Acceptance check: `ci-java` workflow green on your branch (`./gradlew build` runs your tests).

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql. If a contract looks wrong, follow it anyway and say so in your result.
- Never open, read or change anything in swarm/.
- Only create/edit the files listed under "Files". If you truly need another file, say so in your result instead of editing it.
- Your PC may not have Java, Node or Docker. The acceptance check runs in GitHub Actions on your task branch (CI runs on push to task/**). Write code carefully so it compiles first time; keep it simple and readable.
- Do not commit secrets, build outputs, node_modules or .gradle/ folders.
- In your result, list what you built, how the acceptance check proves it, and anything left undone.
