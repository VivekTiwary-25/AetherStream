# Worker-kill recovery timings

CI runs `infra/scripts/kill-repeat.sh` against a fresh Compose stack after `demo-ready.sh` starts the demo pipeline. Each run kills 20 workers through the UI's `POST /api/chaos/kill-worker` API, records failover and rejoin durations, waits for a completed checkpoint after each cycle, and requires a non-empty exact `/api/compare` match. The workflow step summary contains the 20-row timing table and min/median/p95/max statistics.

## CI measurements

| CI run | Failover min / median / p95 / max (s) | Rejoin min / median / p95 / max (s) | Compare |
| --- | --- | --- | --- |
| Not yet run on this task branch | — | — | — |

The CI run is required to populate measurements; no CI timing values are available from the local code-edit session.
