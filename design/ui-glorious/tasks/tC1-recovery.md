# Track C1: fix the worker-card bug (server-side) + Settings → Logs & Recovery window

Files: web/src/glorious/logs/** (yours, incl. the Settings sub-item registration file per the shell README), control-api/** (observability only), engine/** ONLY if strictly needed for observability (see rules), plus their tests. You may import charts from web/src/glorious/analytics/charts/ if present.

## Shared context (read first)
- Branch `ui-glorious`. Mission spec: design/ui-glorious/BOSS_PROMPT.md (sections 0, 2, 3, 6; ignore its swarm/boss instructions). Facts about the code with file:line refs: design/ui-glorious/RECON.md. Shell API for the new UI: web/src/glorious/README.md. Mockups: design/ui-glorious/*.png.
- New UI lives in web/src/glorious/. Edit ONLY the folders/files listed in "Files" above. Shared shell files (web/src/glorious/shell/**, GloriousApp.tsx, main.tsx, tokens) belong to the boss: if you need a shell change, do the smallest local workaround and describe the needed change in your report.
- Never touch the old UI (web/src/app/**, web/src/city/**, web/src/App.tsx) except to IMPORT from it (reusing hooks/helpers is encouraged). Never change the engine's recovery mechanism or the simulator event schema. Do not read or describe swarm/.
- Only real data. No mock data, no invented numbers, no control that does not really work. Honest empty states ("No failed records yet"). Where something is a display mapping, say so in a code comment.
- Look: neutral frosted glass (GlassSurface/GlassWindow from the shell), Inter, WCAG AA text contrast on glass, keyboard accessible, visible focus rings, status as text + colour, prefers-reduced-motion respected.
- Before finishing, from web/: `npm run lint`, `npm test`, `npm run build`, `npm run check:no-mock` must pass (backend tasks: the module's Gradle tests too). Add small vitest tests for your logic. Put a 3-6 sentence summary as your final message (what changed, how checked, what is left).


## The bug (read RECON.md f and a first)
A killed worker (`Runtime.halt(137)`) is restarted by Docker (`restart: unless-stopped`) and re-registers with the same WORKER_ID before the 4 s heartbeat timeout, so the coordinator never reports it DEAD; the API samples once per second; the old UI only showed "killed" via a CLIENT-side overlay after its own button click. In the expo, Vivek kills from a TERMINAL (`worker kill 2` → `POST /api/chaos/kill-worker {"workerId":"worker-2"}`), so the client overlay never fires. The new UI must show the death from SERVER evidence.

## Fix (server-side, real evidence, minimal)
1. Reproduce first on this PC with the full stack (docker compose; see RECON k; use project name `aether-c1` and non-conflicting host ports via an override file kept outside the repo, or reuse the running `aether-preview` stack if present — do not stop other stacks). Kill worker-2 via the API and record what /api/workers and the WS show second by second.
2. Make the API record a durable, server-side recovery history (in control-api, in memory ring buffer of the last ~200 entries is fine; Postgres only if trivial): KILL_REQUESTED (from the chaos endpoint: who/which worker, time), WORKER_DEAD / WORKER_RESTARTED (detected from real signals: e.g. coordinator audit of the KILL command, the worker's re-registration/incarnation change, lastHeartbeatMs gap, state IDLE/RESTORING, partitions dropping to [] — use what really exists), RECOVERING / RECOVERED with generation and `restoredFromCheckpoint`, REBALANCED with old→new partition ownership, CHECKPOINT_COMPLETED. Expose `GET /api/recovery/history` and push new entries on the existing WS (new message type, e.g. `recovery`). The kill must be attributed to the specific worker (workerId), never null.
3. Engine rule: do NOT change the recovery mechanism (detection, timeouts, assignment, restore). If the coordinator lacks a fact you need (e.g. a registration counter/incarnation or "previous entry replaced" flag), you may ADD it as a read-only field to the existing state view. All existing engine/correctness tests must pass (`./gradlew test` for touched modules).
4. Per-worker state for the UI: running / dead / recovering / rejoined, decided from server data (+ the history above), with a minimum visible hold for "dead" (e.g. 3 s) so a sub-second restart is still visible — and label it honestly ("killed 14:02:31, restarted after 2.1 s").

## Logs & Recovery window (web/src/glorious/logs/)
Sidebar → Settings shows "Logs & Recovery" (plus "Truck breakdown" — RECON d says it exists via `POST /api/simulator/scenario {"name":"breakdown","truckId"}`: a small picker of the real trucks, button "Simulate breakdown", honest note "resumes automatically after ~15 sim-minutes"). A large strong-glass GlassWindow with:
- 3 worker cards in real time: name, running/dead/recovering/rejoined (text + colour), records/s, assigned partitions P0–P5 as chips, queue info (sourceQueue/sinkQueue size/capacity, backpressured), last heartbeat age.
- Partition map: P0–P5 → owner; on reassignment show old → new owner for a while.
- Chronological activity/failure log from /api/recovery/history (+ live WS), newest first, persistent across page reload (server-side).
- Completed checkpoint history (RECON e: /api/executions/:id/checkpoints) and, for each recovery, WHICH checkpoint was restored (restoredFromCheckpoint/generation).
- An honest "processing paused for N s during recovery" line computed from real timestamps.
- data-testid hooks: `logs-recovery`, `worker-card-<workerId>`, `partition-chip-<n>`.

## Proof
Screenshot sequence (Playwright, against your running stack, new UI): before kill, dead, recovering, recovered — save to design/ui-glorious/proof/c1-01-before.png … c1-04-recovered.png. Add vitest tests for the per-worker state derivation, and a control-api unit test for the history recorder. Report: root cause confirmed or corrected, what changed in API/engine, test results.

## If Docker is not ready on this PC
A human is fixing Docker Desktop on utkarsh-pc right now. Check `docker info` (20 s timeout) at the start and again before the Proof step. If it never works, still build everything (API history + UI) with unit tests, skip the screenshot proof, and say so clearly in the report. Do not touch Docker Desktop settings.
