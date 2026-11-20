# Fake-data audit A — Live World, Analytics, shell

Scope: `web/src/glorious/map/**`, `analytics/**`, `shell/**`. `LiveWorld.tsx`, `LiveWorld.css`, and `LiveWorld.test.tsx` are deliberately unchanged, including heading, truck photo, and mapping note, per the task override. Static UI labels, units, icons, and layout constants are not telemetry. Test fixtures are isolated test inputs, not production data.

The requested `origin/task/t261120-1049-3463` revision was unavailable: the ref was missing locally and `git fetch` returned “couldn't find remote ref”. Changes were recreated from this checkout rather than claiming to restore that revision.

## Shell

| Visible item | Real source / derivation | Result |
| --- | --- | --- |
| Running / Recovering / Stopped / Failed | Dashboard `execution.state` from `/ws` `snapshot.execution` or `execution.data`; recovery also uses store `recovering` | FIXED — no execution means Unavailable, not an invented Stopped; explicit FAILED means Failed. |
| Degraded | `/ws` `workers.data` / `snapshot.workers`, or GET `/api/workers`; any worker not alive, backpressured, or RESTORING | OK — direct reported worker state, not a fabricated health percentage. |
| Disconnected / Last reported: X | Store `socketStatus` from the shared `/ws` subscription; retained execution state when disconnected/connecting | FIXED — retained states explicitly last reported; no event rate while offline. |
| Connected steady-state status | `web/src/app/state/store.tsx` has no last-received-message timestamp | FIXED — connected uses plain label even with identical execution/workers for 30 seconds; no identity-based clock and no startup-rate-based staleness. |
| Event rate | `/ws` `flow.data.eventsPerSec`, falling back to `snapshot.summary.eventsPerSec` / GET `/api/results/summary`; finite, nonnegative values only | OK — missing/invalid/offline is an em dash with Event rate unavailable accessible label. |
| Server-side age of shell rate | Store exposes values and socket status, but no source timestamp or upstream success timestamp | QUESTION — connected alone cannot prove upstream freshness; task explicitly requires plain label when no message-age signal exists. |
| AetherStream, navigation/menu labels, window titles, back/close, geometry and animation | Local UI definitions in Sidebar, router, analytics/recovery menus, WindowManager, GlassWindow, Icon | OK — navigation, presentation, and user-selected window state; no fabricated metrics. |

## Live World

| Visible item | Real source / derivation | Result |
| --- | --- | --- |
| Truck IDs, display labels T-xx, speed and status | GET `/api/simulator/state.trucks`, `/ws` `snapshot.data.simulator.trucks`, or `trucks.data.trucks`; label abbreviates the actual truckId | OK — malformed trucks filtered; sorted first six are the display fleet, not sample rows. |
| Simulator running/timeScale | GET `/api/simulator/state`, or `/ws` `snapshot.data.simulator`; trucks-only frames omit both fields | FIXED — require real boolean running and finite nonnegative scale; expose independent `controlsFresh`, and truck-only messages cannot clear unavailable-controls notice. Field retained for future LiveWorld consumer work. |
| Last-known fleet / error notice | HTTP failure and local 4.5-second receipt-age expiry in useLiveFleet | FIXED — keep reported trucks, surface failure/expiry; obsolete HTTP failure does not invalidate newer reconnect controls. |
| Display route name, from/to, road geometry | `/glorious/map/routes.geojson` feature properties/geometry; roads sourced via OSRM/OpenStreetMap per map LICENSES.txt | OK — explicitly described as a drawn display path, not real GPS or simulator route assignment. |
| Route ID | Optional real `truck.routeId`; missing displays not provided | OK. |
| Basemap and landmark search results | `/glorious/map/style.json`, local `mysuru.pmtiles`, and `landmarks.json` (OSM feature IDs, names, longitude/latitude) | OK — static geographic reference data, not live fleet telemetry. |
| Marker position, heading, separation | Client integration of reported speed/status and simulator timeScale along a display route; no GPS claim in mapping note | OK — intentional geographic illustration; motion implementation left untouched by instruction. |
| Marker amber/grey/green | Reported stopped/idle/unloading status; speed below 10 km/h; or `/ws`/REST alerts within 60,000 simulated milliseconds using store simTime | OK — deterministic presentation policy, not an invented backend status. |
| Partition and worker assignment | `partitionForTruck` Kafka murmur2 hash with default six partitions; live `workers[].alive/partitions/workerId` | QUESTION — six-partition topology is assumed; missing owner says reassigning, and retained ownership is not freshness-qualified. LiveWorld edits expressly prohibited. |
| Card/marker speed and status after stale telemetry | Retained last-known truck objects; global notice marks missing live updates | QUESTION — individual cards/accessible marker labels are not last-reported-qualified; LiveWorld edits expressly prohibited. |
| Unknown/stale control state during animation | Existing LiveWorld consumes `fresh`, `running`, `timeScale`, not the new `controlsFresh` | QUESTION — consumer intentionally unchanged for the separate truck-motion task. |
| Live World heading, truck image/photo, mapping note | Static presentation assets and text | OK — retained exactly as requested; not individual-truck photographic evidence. |
| Breakdown duration and requested/failed messages | POST `/api/simulator/scenario` with actual selected truckId; simulator `Simulation.java` sets scenarioEnd = now + 900,000 ms | OK — about 15 simulated minutes is backed by implementation; HTTP success says requested/waiting rather than claiming the truck already stopped. |
| Loading routes / no matches / API and map errors | Actual fetch outcomes, local search state, MapLibre load/error events | OK — no fabricated rows or alternate sample fleet. |

## Analytics

| Visible item | Real source / derivation | Result |
| --- | --- | --- |
| Truck rows, “now” speed, speed history | `/ws` `snapshot.data.simulator.trucks` and `trucks.data.trucks`; local receipt times identify observations since panel opened | FIXED — disconnected, expired, missing, and removed readings cannot be shown as current; unavailable intervals remain chart gaps. |
| Completed-window average, duration, reading count | GET `/api/results/windows?sinkId=speedSink&limit=200` and `/ws` `windows.data`: key, windowStart/windowEnd, values.avgSpeedKmh, eventCount | FIXED — missing averages remain gaps; unavailable reading count is explicit; overlapping hydration/live windows deduplicated by sink/key/windowStart. |
| Per-truck deliveries, simulated window end, summed delivery bars | GET `/api/results/windows?sinkId=delSink&limit=200` and `/ws` `windows.data`: key, values.deliveries, windowEnd | FIXED — absent/invalid counts are unavailable, never manufactured zero; incomplete reported totals leave chart gaps; caption limits totals to reported trucks. |
| Alert rows, alert times, per-minute bars | GET `/api/results/alerts?limit=50` and `/ws` `alerts.data`: alertId, key, message, eventTime; count distinct loaded alerts by simulated minute | FIXED — duplicate REST/live alerts cannot inflate counts; caption says loaded alerts and only reported minutes. |
| Events/rejected/late chart samples | `/ws` `flow.data.eventsPerSec/rejectedPerSec/latePerSec`; client receipt times explicitly local clock | FIXED — invalid/missing rates are gaps, not zero; history retained explicitly as history, reconnect/expiry inserts unavailable gaps. |
| Estimated partition rows | `/ws` `flow.data.byPartition` (server worker-rate split, labelled estimated) | FIXED — missing/invalid rates unavailable; disconnect or 4.5-second receipt expiry hides current estimates; reconnect requires a fresh frame. |
| Upstream age of throughput estimates | LiveHub.pollResults can reuse cached latestCluster; Flow contract lacks coordinator-success timestamp | QUESTION — receipt freshness cannot prove backend measurement freshness; needs an upstream age signal outside this task. |
| Failed-record row, kind filter, exact detail values | GET `/api/results/dead-letters?kind=REJECTED/LATE&limit=50` and `/ws` `deadLetters.data`: eventId, kind, reason, partition, offset, eventTime | OK — deduplicated by eventId/kind, sorted by actual simulation eventTime, selected details remain the exact reported record. |
| Chart points, bars, numeric axes, oldest/newest/local-time labels | Supplied real samples and derived scale; original sample positions retained | FIXED — nonfinite/missing bars omitted, line/fill paths split across gaps; all-unavailable differs from empty; axes are derived scale bounds, not measurements. |
| Empty/unavailable/loading captions, titles, column names, tabs, units | Local UI definitions plus connection/execution/selection/data-presence state | OK — descriptive UI, not fabricated telemetry or sample rows. |

## Verification

- `npx vitest run src/glorious/map src/glorious/analytics src/glorious/shell`: **7 files, 62 tests passed**. Invoked npx via Node's installed `npx-cli.js` because this shell's npm shim resolves its install directory incorrectly.
- `node node_modules/typescript/bin/tsc --noEmit --incremental false`: **passed**, exit 0. Initial `tsc -b` exceeded its 120-second timeout; the completed direct project typecheck took 34 seconds.
- Actual browser smoke on isolated Vite at `127.0.0.1:5197`, with no API/WS proxy: shell displayed **Disconnected / — ev/s**; Events Processed displayed three **No samples yet** chart states and **Disconnected — live throughput unavailable**. No invented fleet or counters appeared. Preview and managed browser tab were closed afterward.
- No Docker, port 8088, live-demo access, commits, pushes, branch changes, or swarm control operations.
