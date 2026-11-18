# Contracts (read all before any task)

| File | What |
|---|---|
| 00-stack-and-layout.md | versions, modules, folders, ports, env vars, CI workflows |
| 01-event-envelope-and-simulator.md | Kafka event envelope, simulator events, simulator HTTP API |
| 02-pipeline-and-semantics.md | pipeline JSON, compiler errors, exact operator semantics, execution plan |
| 03-coordinator-worker-protocol.md | registration, heartbeats, assignment + generation fencing, barrier checkpoints, recovery, sinks |
| 04-control-api-rest-ws.md | REST + WebSocket for the UI, compare-with-reference semantics |
| ../../infra/db/schema.sql | Postgres schema |

Java records for these contracts live in `:common` (`io.aetherstream.common`, sub-packages `pipeline`, `protocol`, `api`).
Field names in JSON = record component names (Jackson, camelCase). Unknown JSON fields are ignored when reading.
Contracts change only by the boss. If one looks wrong, implement it as written and say so in your task result.
