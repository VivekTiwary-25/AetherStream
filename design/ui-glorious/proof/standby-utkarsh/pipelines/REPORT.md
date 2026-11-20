# Pipelines live test

Endpoint readiness: `GET /api/glorious/pipelines/fields` returned HTTP 200. For all three example pipelines, validation returned HTTP 200 with `valid: true`; run returned HTTP 201; the final results poll was HTTP 200; stop returned HTTP 200 with `state: STOPPED`.

| Pipeline | State at final poll → after stop | eventsIn | Results in 40 s | Errors |
|---|---|---:|---|---|
| `speeding` | `RUNNING` → `STOPPED` | 1102 | 0; no kinds or samples | none |
| `avg-speed-30s` | `RUNNING` → `STOPPED` | 1155 | 0; no kinds or samples | none |
| `partition-2` | `RUNNING` → `STOPPED` | 1151 | 190 `row` results | none |

Three real `partition-2` samples (output `Partition 2 speeds`): seq 1, `truck-002`, 34.672611800177364 km/h; seq 2, `truck-002`, 35.744393751091174 km/h; seq 3, `truck-002`, 41.44446990389692 km/h. The other pipelines produced no results, so they have no samples.

Claude Code is installed (`2.1.314`) but `claude -p "Reply OK" --model claude-haiku-5-5 --tools ""` returned `Not logged in · Please run /login`; the bridge was not started. On `/pipelines`, selecting the speeding chip and clicking Generate showed `AI service unavailable`; the request `POST /api/glorious/ai/generate` returned HTTP 502. No review/Run control appeared, so no AI results-window screenshot could be produced. `ai-01.png` captures the honest unavailable state; `ai-02.png` is omitted.

The editor opened in a separate tab. The average-speed example validated (`Valid. This pipeline can run.`; `ed-01.png`). Run opened the results window, but it remained `Disconnected`, `Events in: -`, `Results: 0`, `The pipeline is not valid`, and `Waiting for the first update...` throughout the 40-second wait (`ed-02.png`). Setting `Window (seconds)` to 0 and validating displayed `Error: windowSec must be an integer between 5 and 300` on node `n1` (`ed-03.png`). Browser automation used the managed Chromium browser; Playwright was not installed in the checkout or Python environment.

Raw validate, run, results-poll, and stop responses are saved as `*-validate.json`, `*-run.json`, `*-results.json`, and `*-stop.json` for each API pipeline. Screenshots are saved alongside this report. The requested task report under `swarm/reports/` was not written because repository instructions prohibit this worker from modifying `swarm/`.
