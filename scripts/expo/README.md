# Expo terminal commands

From Bash, in this directory, run `./worker kill 2`, `./worker status`, or `./events late [count]`.
On Windows, run `worker kill 2`, `worker status`, or `events late [count]` from
PowerShell or cmd in this directory. To enable those names for the current cmd
session: `set PATH=%CD%\scripts\expo;%PATH%`; in PowerShell:
`$env:PATH = "$PWD\scripts\expo;$env:PATH"`.

Commands target `http://localhost:8088` by default; set `AETHER_URL` to change
it. Requests time out after 10 seconds. `worker kill` accepts only 1–3 and
queues the existing worker-kill API action; the engine handles recovery.
`events late` defaults to 20 events, each with `lateByMs=180000`; event
classification as late depends on the engine watermark. `worker status` only
reads worker IDs, alive/state, and partitions. API errors are printed and exit
nonzero; keep the classic UI available at `?ui=classic` as fallback.
Run `preflight` (Bash) or `preflight.cmd` (Windows) before the demo. It uses
GET requests only to check the web app, API health/current execution, workers,
simulator, recovery history, Pipelines fields, and AI bridge. Core check failures
print `FAIL` and exit nonzero; a simulator truck-count mismatch or unavailable
AI bridge prints `WARN`. A missing AI bridge warning includes its start command.
Set `AETHER_URL` to check a different app host; the default is
`http://localhost:8088`.
