# Track D1: pipeline schema, validator and live executor in the API layer

Files: schemas/glorious-pipeline.schema.json (new), schemas/examples/*.json (new: 3 examples), control-api/src/main/java/io/aetherstream/api/glorious/** (new package), control-api/src/test/java/io/aetherstream/api/glorious/** (new), control-api/build.gradle.kts (only if a dependency is needed), docker-compose.yml (ONLY if control-api needs a new env var), infra/docker/nginx.conf (ONLY the new /api/glorious/ai/ location described below).

## Shared context (read first)
- Branch `ui-glorious`. Mission spec: design/ui-glorious/BOSS_PROMPT.md (sections 0, 2, 3, 6; ignore its swarm/boss instructions). Facts about the code with file:line refs: design/ui-glorious/RECON.md. Shell API for the new UI: web/src/glorious/README.md. Mockups: design/ui-glorious/*.png.
- New UI lives in web/src/glorious/. Edit ONLY the folders/files listed in "Files" above. Shared shell files (web/src/glorious/shell/**, GloriousApp.tsx, main.tsx, tokens) belong to the boss: if you need a shell change, do the smallest local workaround and describe the needed change in your report.
- Never touch the old UI (web/src/app/**, web/src/city/**, web/src/App.tsx) except to IMPORT from it (reusing hooks/helpers is encouraged). Never change the engine's recovery mechanism or the simulator event schema. Do not read or describe swarm/.
- Only real data. No mock data, no invented numbers, no control that does not really work. Honest empty states ("No failed records yet"). Where something is a display mapping, say so in a code comment.
- Look: neutral frosted glass (GlassSurface/GlassWindow from the shell), Inter, WCAG AA text contrast on glass, keyboard accessible, visible focus rings, status as text + colour, prefers-reduced-motion respected.
- Before finishing, from web/: `npm run lint`, `npm test`, `npm run build`, `npm run check:no-mock` must pass (backend tasks: the module's Gradle tests too). Add small vitest tests for your logic. Put a 3-6 sentence summary as your final message (what changed, how checked, what is left).


## Track D shared contract (D1 backend, D2 frontend and D3 AI bridge are built in parallel against this)
Pipeline JSON (schema file: schemas/glorious-pipeline.schema.json, owned by D1):
```json
{"name":"Speeding alert","nodes":[
  {"id":"n1","type":"filter","params":{"field":"speedKmh","op":">","value":60}},
  {"id":"n2","type":"thresholdAlert","params":{"field":"speedKmh","op":">","value":60,"message":"Truck over 60 km/h"}},
  {"id":"n3","type":"output","params":{"name":"Speeding trucks"}}],
 "edges":[{"from":"n1","to":"n2"},{"from":"n2","to":"n3"}]}
```
- Exactly five node types: `filter {field, op, value}`, `pickFields {fields: string[]}`, `windowAverage {field, windowSec, groupBy?}`, `thresholdAlert {field, op, value, message}`, `output {name}`. Display names: Filter, Pick Field, Window Average, Threshold Alert, Output.
- op ∈ `>`, `>=`, `<`, `<=`, `==`, `!=`. Graph: acyclic, every path ends in an `output`, at least one output, each non-first node has exactly one input, max 12 nodes. The implicit source is the live truck position stream (no source node).
- Fields (the real truck position fields, RECON.md i): `truckId` (string), `speedKmh` (number), `heading` (number), `x` (number), `y` (number), `routeId` (string), `status` (string: DRIVING/UNLOADING/IDLE/STOPPED), `partition` (number 0-5, the REAL Kafka partition), `eventTime` (number, epoch ms). Numeric ops only on numeric fields; `==`/`!=` allowed on strings. groupBy ∈ {truckId, routeId, status, partition}.
REST (control-api, same origin under /api, D1 implements):
- `GET  /api/glorious/pipelines/fields` → `{fields:[{name,type,description}], nodeTypes:[...]}`
- `POST /api/glorious/pipelines/validate` body = pipeline → `{valid:boolean, errors:[{nodeId?:string, message:string}]}`
- `POST /api/glorious/pipelines/runs` body = pipeline → `201 {runId}` or `400 {valid:false, errors}`; starting a run stops the previous run (one run at a time).
- `GET  /api/glorious/pipelines/runs/{runId}/results?after=<seq>` → `{runId, state:"RUNNING"|"STOPPED"|"FAILED", error?:string, startedAt, eventsIn:number, results:[{seq, ts, output, kind:"row"|"alert"|"average", data:{...}}]}` (keep last 500 results)
- `POST /api/glorious/pipelines/runs/{runId}/stop` → `{state:"STOPPED"}`
AI (host-side bridge, D3 implements; reached by the browser through nginx at `/api/glorious/ai/`):
- `POST /api/glorious/ai/generate` body `{prompt:string}` → `200 {pipeline, source:"ai"|"example", note?:string, model?:string, ms:number}`; `429 {error:"busy"}` if another request is running. source "example" means fallback: note = "Showing a saved example: AI was unavailable".
- `GET /api/glorious/ai/health` → `{ok, model, configDir?:boolean}`


## Build
1. JSON Schema (draft 2020-12) for the pipeline in schemas/glorious-pipeline.schema.json exactly matching the contract (node params per type via oneOf/discriminator on `type`). Graph rules (acyclic, outputs, one input) are checked in code.
2. Java validator in control-api (package io.aetherstream.api.glorious): schema-equivalent checks + graph rules + field/op type rules, with clear per-node error messages ("n2: windowSec must be between 5 and 300"). Limits: windowSec 5..300, value finite, message ≤ 120 chars, name ≤ 60 chars.
3. Executor ("next to the engine, reading the same stream"): do NOT modify the engine, simulator or the existing pipeline/execution endpoints. Consume the real truck topic from Kafka with the control-api's own NEW consumer group (e.g. `glorious-pipelines-<random>`), starting at latest, only `truck.position` events; partition = the Kafka record's partition. Check RECON.md and control-api's dependencies: if kafka-clients is not on control-api's classpath, add it (it is used elsewhere in the repo — use the same version). Bootstrap servers from the existing config/env used by other services (docker-compose). If Kafka is unreachable, the run state is FAILED with an honest error.
   - Semantics: records flow along edges; `filter` passes matching records; `pickFields` projects (always keep truckId); `windowAverage` = tumbling windows by event time (eventTime) per groupBy (or global), emits `{kind:"average", data:{group, field, avg, count, windowStart, windowEnd}}` when a window closes (close on wall-clock grace of 2 s after windowEnd is fine; say so in a comment); `thresholdAlert` passes records/averages and emits `{kind:"alert", data:{truckId?, field, value, message}}` when the condition holds (de-duplicate: max one alert per truck per 10 s); `output` collects into results with `output` = its name; plain rows are `kind:"row"`. Rate-limit rows to ≤ 20/s per output (drop extras, count dropped and expose `droppedRows`).
   - One run at a time; stop the consumer cleanly on stop/new run/shutdown. Thread-safe result buffer (last 500).
4. REST endpoints exactly as in the contract (Spring style like the existing controllers). Bound request size (64 KB).
5. Three rehearsal pipelines, validated by your validator in a unit test, saved as schemas/examples/: `speeding.json` ("Alert me when any truck goes over 60 km/h"), `avg-speed-30s.json` ("Average speed per truck over the last 30 seconds"), `partition-2.json` ("Show only trucks on partition 2 with their speed"). Each file: `{"prompt": "...", "pipeline": {...}}`.
6. nginx: add `location /api/glorious/ai/ { proxy_pass http://host.docker.internal:18090/; proxy_read_timeout 120s; ... }` BEFORE the generic /api/ location in infra/docker/nginx.conf, and add `extra_hosts: ["host.docker.internal:host-gateway"]` to the web service in docker-compose.yml. (The AI bridge runs on the host, port 18090; another worker builds it.) If the bridge is down nginx returns 502 — fine, the UI handles it.
7. Tests: validator (valid examples, each error class), executor semantics with an in-memory record feed (no Kafka needed for unit tests), controller tests. `./gradlew :control-api:test` and the full `./gradlew test` must pass (existing correctness tests included). If you can, run the stack and do one real run of speeding.json, and report a few real results.

Report: endpoints, how the consumer is configured, test results, anything the UI must know.
