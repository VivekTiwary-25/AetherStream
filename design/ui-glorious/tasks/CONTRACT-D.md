
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
