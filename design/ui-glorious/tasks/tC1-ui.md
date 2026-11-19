# Track C1-UI: Settings → Logs & Recovery window + Truck breakdown (web only)

Files: web/src/glorious/recovery/** (yours; NOTE: the folder is `recovery/`, NOT `logs/` — the root .gitignore rule `logs/` silently ignores any folder named logs, which is how the previous attempt's UI was lost). Read only: control-api/src/main/java/io/aetherstream/api/recovery/** and control-api/src/main/java/io/aetherstream/api/ws/LiveHub.java (the server side is DONE and merged), web/src/glorious/analytics/charts/ if present.

## Shared context (read first)
- Branch `ui-glorious`. Mission spec: design/ui-glorious/BOSS_PROMPT.md (sections 0, 2, 3, 6; ignore its swarm/boss instructions). Facts about the code with file:line refs: design/ui-glorious/RECON.md. Shell API for the new UI: web/src/glorious/README.md. Mockups: design/ui-glorious/*.png.
- New UI lives in web/src/glorious/. Edit ONLY the folders/files listed in "Files" above. Shared shell files (web/src/glorious/shell/**, GloriousApp.tsx, main.tsx, tokens) belong to the boss: if you need a shell change, do the smallest local workaround and describe the needed change in your report.
- Never touch the old UI (web/src/app/**, web/src/city/**, web/src/App.tsx) except to IMPORT from it (reusing hooks/helpers is encouraged). Never change the engine's recovery mechanism or the simulator event schema. Do not read or describe swarm/.
- Only real data. No mock data, no invented numbers, no control that does not really work. Honest empty states ("No failed records yet"). Where something is a display mapping, say so in a code comment.
- Look: neutral frosted glass (GlassSurface/GlassWindow from the shell), Inter, WCAG AA text contrast on glass, keyboard accessible, visible focus rings, status as text + colour, prefers-reduced-motion respected.
- Before finishing, from web/: `npm run lint`, `npm test`, `npm run build`, `npm run check:no-mock` must pass (backend tasks: the module's Gradle tests too). Add small vitest tests for your logic. Put a 3-6 sentence summary as your final message (what changed, how checked, what is left).

## Server side (already merged, use it, do not change it)
- `GET /api/recovery/history` → list of entries `{id, event, at, workerId, executionId, generation, restoredFromCheckpoint, actor?, reason?, durationMs?, checkpointId?, partitionChanges?: [{partition, oldOwner, newOwner}]}`; live entries are pushed on the existing WebSocket (find the message type in LiveHub.java). Events include KILL_REQUESTED, worker death/restart, recovery started/finished, assignment changes, CHECKPOINT_COMPLETED — read RecoveryHistory.java/RecoveryObserver.java for the exact names.
- Worker cards data: existing `useDashboard().workers` (RECON.md e). Checkpoints: `/api/executions/:id/checkpoints`.
- `settingsMenu` lives in web/src/glorious/recovery/menu.ts (currently a placeholder) — replace its item and add "Truck breakdown".

## Logs & Recovery window (web/src/glorious/logs/)
Sidebar → Settings shows "Logs & Recovery" (plus "Truck breakdown" — RECON d says it exists via `POST /api/simulator/scenario {"name":"breakdown","truckId"}`: a small picker of the real trucks, button "Simulate breakdown", honest note "resumes automatically after ~15 sim-minutes"). A large strong-glass GlassWindow with:
- 3 worker cards in real time: name, running/dead/recovering/rejoined (text + colour), records/s, assigned partitions P0–P5 as chips, queue info (sourceQueue/sinkQueue size/capacity, backpressured), last heartbeat age.
- Partition map: P0–P5 → owner; on reassignment show old → new owner for a while.
- Chronological activity/failure log from /api/recovery/history (+ live WS), newest first, persistent across page reload (server-side).
- Completed checkpoint history (RECON e: /api/executions/:id/checkpoints) and, for each recovery, WHICH checkpoint was restored (restoredFromCheckpoint/generation).
- An honest "processing paused for N s during recovery" line computed from real timestamps.
- data-testid hooks: `logs-recovery`, `worker-card-<workerId>`, `partition-chip-<n>`.


## Checks
lint, `npm test -- --maxWorkers=2`, build, check:no-mock must pass; add vitest tests for per-worker state derivation (running/dead/recovering/rejoined with the minimum 'dead' hold, using history entries + worker data) and history rendering. Run `git status` at the end and confirm every file you created is tracked (not ignored). Docker is NOT available on this PC tonight; screenshot proof is not required — say so.
