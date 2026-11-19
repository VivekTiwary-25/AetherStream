# Expo terminal commands

From Bash, run `./worker kill 2`, `./worker status`, or `./events late [count]`.
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
