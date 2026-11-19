# T110: Real recovery duration on RECOVERED events (contract 04, additive)  [M3]

Lanes 3 + 6. Contract 04 now says: the WebSocket `execution` message with `"event":"RECOVERED"` also carries `"durationMs"`
(ms from the server detecting WORKER_DEAD to RECOVERED, measured by the server). Today the UI timeline shows "Recovered in -"
because nothing sends it. Find where control-api emits WORKER_DEAD / RECOVERING / RECOVERED (control-api ws/LiveHub and whatever
feeds it, e.g. execution polling or coordinator status), record the WORKER_DEAD detection time per execution, and include
durationMs on RECOVERED (omit it if WORKER_DEAD was never seen, e.g. after a control-api restart). Make sure the checkpoint
timeline (web/src/app/timeline) reads that field for its "Recovered in ..." marker (it already formats a durationMs; check the
mapping from the WS message). Unit tests on both sides: control-api (WORKER_DEAD then RECOVERED -> durationMs = difference
with a fake clock) and web (marker shows "Recovered in 6.0 s" for durationMs 6000).

Files (edit only these): control-api/src/main/java/io/aetherstream/api/ws/**, control-api/src/main/java/io/aetherstream/api/**
(only the class that produces execution events, if it is not in ws/), control-api/src/test/java/**, web/src/app/timeline/**,
web/src/app/state/** (only the WS message mapping), web/src/api/** (types only).

Acceptance check: ci-java and ci-web green on your branch.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql (if a contract looks wrong, say so in your report).
- Do not read or change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only edit the files listed. If you are on vivek-pc: do NOT start Docker containers or kind there (the live demo runs on it). Acceptance runs in GitHub Actions on your task branch.
