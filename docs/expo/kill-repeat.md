# Worker-kill recovery timings

CI runs `infra/scripts/kill-repeat.sh` against a fresh Compose stack after `demo-ready.sh` starts the demo pipeline. Each run performs 20 worker-kill cycles via `POST /api/chaos/kill-worker` (not 20 worker ids; `scripts/expo/worker.cmd` `kill` accepts only 1-3 and posts that API directly), records failover and rejoin durations, waits for a completed checkpoint after each cycle, and requires a non-empty exact `/api/compare` match. The workflow step summary contains the 20-row timing table and min/median/p95/max statistics.

## CI measurements

| CI run | Failover min / median / p95 / max (s) | Rejoin min / median / p95 / max (s) | Compare |
| --- | --- | --- | --- |
| Not yet run on this task branch (check) | — | — | — |

The CI run is required to populate measurements; no CI timing values are available from the local code-edit session (check).
