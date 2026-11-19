# Track B: Analytics windows (5 glass windows on real data)

Files: web/src/glorious/analytics/** (yours; includes the sidebar sub-items registration file the shell README tells you to use for Analytics), web/src/glorious/analytics/charts/** (new: the ONE chart component set all new windows use; Track C may import it).

## Shared context (read first)
- Branch `ui-glorious`. Mission spec: design/ui-glorious/BOSS_PROMPT.md (sections 0, 2, 3, 6; ignore its swarm/boss instructions). Facts about the code with file:line refs: design/ui-glorious/RECON.md. Shell API for the new UI: web/src/glorious/README.md. Mockups: design/ui-glorious/*.png.
- New UI lives in web/src/glorious/. Edit ONLY the folders/files listed in "Files" above. Shared shell files (web/src/glorious/shell/**, GloriousApp.tsx, main.tsx, tokens) belong to the boss: if you need a shell change, do the smallest local workaround and describe the needed change in your report.
- Never touch the old UI (web/src/app/**, web/src/city/**, web/src/App.tsx) except to IMPORT from it (reusing hooks/helpers is encouraged). Never change the engine's recovery mechanism or the simulator event schema. Do not read or describe swarm/.
- Only real data. No mock data, no invented numbers, no control that does not really work. Honest empty states ("No failed records yet"). Where something is a display mapping, say so in a code comment.
- Look: neutral frosted glass (GlassSurface/GlassWindow from the shell), Inter, WCAG AA text contrast on glass, keyboard accessible, visible focus rings, status as text + colour, prefers-reduced-motion respected.
- Before finishing, from web/: `npm run lint`, `npm test`, `npm run build`, `npm run check:no-mock` must pass (backend tasks: the module's Gradle tests too). Add small vitest tests for your logic. Put a 3-6 sentence summary as your final message (what changed, how checked, what is left).


## Build
Sidebar → Analytics shows exactly five options: Truck Speeds, Deliveries, Alerts, Events Processed, Failed Records. Each opens a GlassWindow via the shell WindowManager (`useWindows().open(id, ...)`), to the right of the sidebar; several can be open; clicking an open one brings it forward; the map stays visible underneath. Replace the shell's "Coming soon" placeholders.

REUSE the old data code (RECON.md e): `useDashboard()` from web/src/app/state/store.tsx (windows, alerts, deadLetters, workers, summary), the `trucks` live data, the `flow` WS message for events/s (RECON e: `flow {eventsPerSec, byPartition, rejectedPerSec, latePerSec}` via useAetherMessages). If the DashboardProvider is not mounted in the new UI yet, mount it inside your windows' render tree or ask in the report; do not duplicate fetch logic.

Charts: no chart library exists; build a small shared SVG `LineChart` (+ `Sparkline`, `Bars`) in analytics/charts/ with quiet styling readable on glass (axis labels, units, AA contrast, no animation when reduced motion). Same look in all windows.

Polished:
- Truck Speeds: one row per truck: current speed (live trucks data), average speed of the latest completed window (speedSink `values.avgSpeedKmh`; label it with the real window length, e.g. "1-min avg" only if the window really is 1 minute — read the demo pipeline definition / window start-end to know), reading count (`eventCount`). Rows are buttons (keyboard list of trucks). Clicking a row shows that truck's recent speed history as a line chart IN THE SAME WINDOW (same size/position) with a back arrow: plot the window averages over time (from /api/results/windows?sinkId=speedSink rows for that key) and overlay the live speed samples collected since the window opened (say "live samples since HH:MM:SS" so it is honest).
- Failed Records: Rejected and Late tabs (or a kind column) from deadLetters: time, kind, reason, partition, offset, eventId; click a row to show its technical details in the same window with back arrow. Honest empty state "No failed records yet". This is what Vivek fills during the demo with the `events late` command, so new rows must appear live.
Simple (table + one small trend line):
- Deliveries: per-truck delivery counts (delSink windows `values.deliveries`) + total-per-window trend.
- Alerts: truck, message, time (newest first) + alerts-per-minute trend if derivable from real data.
- Events Processed: real `flow.eventsPerSec` live line (samples since open), plus the breakdowns that exist (rejected/s, late/s, per-partition — label per-partition honestly: RECON e says it is a per-worker rate divided evenly over partitions, so call it "estimated per partition" or omit).

Times: show simulation time where the data is sim-time (alerts eventTime) and say so; do not mix clocks silently.

Acceptance: all five windows open from the sidebar with live data from a running stack; Truck Speeds row → chart → back works without the window moving/resizing. Add vitest tests for the data shaping (grouping by truck, latest window, failed-record mapping). Add data-testid hooks the smoke test can use: `analytics-truck-speeds`, `truck-row-<truckId>`, `truck-speed-chart`.
