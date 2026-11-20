# Bug review: ui-glorious changes since 06:41 (`61c7d7a..24c4bd34`)

Scope: `web/src`, `control-api/src`, `scripts`, `infra/scripts` (46 files). `web/src/glorious/recovery/**` was read but not changed, because another task owns it.
Ranked by how much each one would affect the demo. CONFIRMED = traced in the code (and tested where a fix was made). PLAUSIBLE = depends on runtime timing.

## Findings

### 1. CONFIRMED, FIXED: every truck label on the Live World map sits off-centre
`web/src/glorious/map/LiveWorld.tsx:226` (was ``label.style.transform = `translateY(${offset}px)` ``).
The stylesheet centres the label with `transform: translateX(-50%)` (`LiveWorld.css:29`). The new inline style replaces the whole `transform`, so the `-50%` is lost. Every frame, each "Truck T-0x" label moves right by half its own width, so it no longer sits under its sprite. This shows on the main demo screen all the time.
**Fix:** new helper `labelTransform(offsetY)` in `mapLogic.ts` returns `translate(-50%, Ypx)`, and LiveWorld uses it. Test added in `mapLogic.test.ts` ("keeps the label centred under the truck ...").

### 2. CONFIRMED, already there before today: one truck's newer row closes the window for all trucks (`windowAverage` has no allowed lateness)
`control-api/src/main/java/io/aetherstream/api/glorious/PipelineEvaluator.java:137-140,160`. `node.watermark` is the largest eventTime seen at the node across all groups and partitions. `closeWindows` sets `finalizedThrough = watermark`. So the first row with `eventTime >= windowEnd` closes that window for every truck. Any later row for an older window is dropped silently: it is not counted in `droppedRows`.
Scenario (avg-speed-30s, grouped by truck, all partitions): one poll returns partition A's batch, with truck-001 at event 30 000, before partition B's batch, with truck-004 at 29 000. truck-004's window [0, 30 000) is emitted early with fewer readings, and its 29 000 row is thrown away. The simulator runs at 10x (one row = 2 s of event time per 200 ms of wall time), so a 300 ms skew between partitions is about 3 s of event time. That gives wrong averages and counts on window boundaries.
Not fixed: it changes window semantics and existing tests. Suggested fix: finalise at `watermark - allowedLateness` (for example 2-4 s of event time, which is still under 1 s of wall time at 10x), and count rows dropped as late.

### 3. CONFIRMED, new today: a consumer stall longer than 2 s closes windows early, and the backlog after it is dropped
`PipelineEvaluator.java:61-67,74`. On the idle clock, `eventNow = sourceWatermark + (wall time elapsed)` moves at 1x. Real simulator event time moves at 10x. That mismatch is harmless while rows keep flowing. During a stall, `PipelineRuns.consume` (`PipelineRuns.java:144-154`) keeps calling `tick()` after every 250 ms poll, so `finalizedThrough` keeps moving forward on wall time.
Scenario: a Kafka hiccup, rebalance or long GC holds rows for 10 s. `finalizedThrough` reaches `E0 + 8 s`. The backlog then arrives, but it starts at `E0 + 0.2 s`. Rows in any window ending at or before `E0 + 8 s` are dropped, and that window has already been emitted as a partial average.
Answers to the task's questions:
- Emit too early? Not in steady state: `eventNow - 2 s` is always behind the row watermark. Yes after a stall (above).
- Drop late rows wrongly? Yes after a stall of 2 s or more (above), plus finding 2.
- Never close when events stop? No, it always closes: `tick` runs on every poll loop. But because the idle clock moves at 1x while event time moves at 10x, the last open 30 s window can take up to about 32 s of wall time to close after the simulator stops. The presenter should expect the last average to arrive late, not "2 s after".

Not fixed (design choice, Java). Options: stop moving the idle clock forward while `poll` returns nothing, or cap the idle advance.

### 4. PLAUSIBLE, already there before today: the fleet REST poll can stop for good
`web/src/glorious/map/useLiveFleet.ts:65`. `fetch('/api/simulator/state')` has no timeout. The next poll is only scheduled after it settles (`:89`). If the request hangs (simulator container killed mid-request, or a proxy holding the socket), polling stops until the browser gives up. Meanwhile `controlsFresh` turns false and the map shows "Simulator controls unavailable" even after the simulator is back, while WS `trucks` frames keep flowing. This tab never receives another `snapshot`, which is the only other source of controls.
Suggested fix (not applied, about 2 lines): `signal: AbortSignal.any([controller.signal, AbortSignal.timeout(4000)])`. This needs a fake-timer test, so I left it for the owner of `useLiveFleet`.

### 5. PLAUSIBLE, new today: after the AI generate timeout, a quick retry can hit "AI is busy"
`web/src/glorious/pipelines/api.ts:430-433`. The client now aborts after 60 s, and `AbortSignal.timeout` gives a `TimeoutError`, which becomes `ApiError(0)`, then the offline fallback. The server-side generation is not cancelled. A retry while the server is still generating can get a 429 ("The AI is busy ..."), and the exact-match example fallback is not used for 429 (`PipelinesPanel.tsx:363`). This is a demo risk only if the model is slow (>60 s).

### 6. LOW, new today: brief "Live throughput unavailable" after a WS reconnect
`web/src/glorious/analytics/AnalyticsPanel.tsx:40-43`. On reconnect, `LiveHub` replays retained `flow` frames *before* it sends the `snapshot` (`LiveHub.java:122-137`). The snapshot handler then resets `flowFresh=false` and adds a gap sample, so the partition table says unavailable until the next live flow frame. This is cosmetic and recovers by itself.

## Checked and found fine
- `results.ts` `after: -1 -> 0`: correct. Server seqs start at 1 (`sequence.incrementAndGet()`), and the controller rejects `after < 0`.
- `scripts/preview/*.ps1`: the `$null = $p.Handle` caching is the correct PS 5.1 fix for `ExitCode` being `$null` after `WaitForExit(ms)`. Splitting `preview-refresh.ps1` into build + `up --no-build` with a `deployed-revision.txt` baseline correctly rebuilds control-api on the next run after a failed build. Arguments contain no paths with spaces, so `Start-Process` joining them unquoted is safe here. Small leftovers: `Kill()` does not kill the compose child process tree, and an empty `deployed-revision.txt` would make `.Trim()` on `$null` throw.
- `infra/scripts/expo-proofs.sh` `MSYS_NO_PATHCONV=1`: no host paths are passed to native tools, so nothing breaks.
- `useLiveFleet` revision guards, `status.ts` (`lastReported`), `Charts.tsx` gaps and bar scaling (`max - min >= 1`, so no divide by zero), `uniqueAlerts`, `EditorPage` error precedence (`run()` clears `error`), the `GloriousApp` prefetch (idle callback and timer are both cleared).

## Fixes made
| File | Change | Test |
|---|---|---|
| `web/src/glorious/map/mapLogic.ts`, `web/src/glorious/map/LiveWorld.tsx` | Label transform keeps `translateX(-50%)` | New case in `mapLogic.test.ts`. `npx vitest run src/glorious/map`: 3 files, 37 tests pass. `tsc --noEmit` clean. |
