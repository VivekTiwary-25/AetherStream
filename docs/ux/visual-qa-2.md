# AetherStream — visual QA round 2 on the live app (T115)

Captured 2026-11-19, 08:00–08:50 IST, from the public link (`trance-cover-acrobat-submissions.trycloudflare.com`). Tool: headless
Microsoft Edge driven by Playwright, at **1440×900** and **390×844** (touch, mobile). I walked the DEMO.md script: city, panels, editor
(examples, Validate with errors, Run), chaos (late events, slow output, kill worker → recovering → recovered), compare, ops
(savepoint / restore / rescale), scenarios, tour, footer. No code was changed. Screenshots are in `screenshots/round2/` (80 PNGs).

**The app was redeployed four times while I tested.** The footer and `/api/version` went `44dfe07` → `8868725` → `372c09d` → `bba970a`.
- `372c09d` has the same `web/` as main after T102 (city polish) and T110 (RECOVERED `durationMs`). Most of the walk-through was captured on this build. Files with no suffix are from 372c09d.
- `bba970a` is the T98 shell polish merge. It landed near the end, so I re-shot the shell, the dead letters, the kill/recover sequence, the editor Run and mobile on it. Those files end in `-bba970a`.
- Two files are kept on purpose as evidence: `desktop-90-during-redeploy-stopped-reconnecting.png` (mid-deploy) and `desktop-91-no-webgl-blank-city-fullpage.png` (a browser without WebGL).

**Headless WebGL note.** In my first run, headless Edge had no GPU, so the city canvas was blank (`desktop-91`). Every later run used
`--enable-unsafe-swiftshader`, and there the city renders. Treat the blank city in desktop-91 as a "no WebGL" case (P3-8), not as the normal look.

Severity: **P1** breaks the demo · **P2** looks wrong · **P3** nice to have.

## Fixed since round 1 (checked live, bba970a)
- Panels no longer overlap or clip each other. The grid uses natural heights (`desktop-51`). In 372c09d, slots had fixed heights of 180 and 336 px and their content was cut off (`desktop-panel-*`).
- The banner has proper spacing and a separate **Live** chip, and the status pills have a single dot (`desktop-50`).
- Speed rows are a clean table, "truck-001 25.6 km/h" with teal sparklines and a caption. Deliveries has a unit sentence and axis times.
- Dead-letter tabs are a segmented control, "Rejected (15) / Late (20)". Rows are monospace with the time on the right.
- Mobile is a single column, 390 px wide. In 372c09d the page was **782 px wide**, so it scrolled sideways (`mobile-03-fullpage`).
- Recovery shows the same state in the header, banner, city pill and timeline. Rescale's refusal message and Restore's confirmation text match DEMO.md.

---

## P1 — breaks the demo

### P1-1 The city and the stream engine are hidden behind the dashboard (desktop and mobile, every build)
- **Screenshots:** `desktop-50-first-screen-bba970a`, `desktop-02-first-screen`, `desktop-tour-step2`, `mobile-20-first-screen-bba970a`.
- **Where:** `web/src/app/shell/AppShell.tsx`. `.app-city` is `position: fixed; z-index: -1; 1440×900` behind everything. `.panel-grid` starts at x = 460 (bba970a) or 500 (372c09d), y = 181, and covers it. Styles are in `web/src/styles/global.css` and `web/src/app/panels/panels.css`.
- **What is wrong:** at 1440×900 the panels cover about 65% of the city. That includes the whole **STREAM ENGINE** column (ring, ev/s, P0–P5 lanes) and the right half of the map. Only the caption "STREAM ENGINE" peeks out above Alerts, and lane bars show through the 12 px gaps between cards. DEMO.md 0:45 says "point at … the particle stream into the engine core". The tour's step 2, "The stream into the engine", spotlights something you cannot see. On mobile the city is not visible at all: the first screen is the top bar, the banner and Speed windows. When I hid `.panel-grid` the full city was there underneath, with trucks, packets, the engine ring and lane rates.
- **Fix:** make the city a real section in the page flow, not a fixed background.
  - Desktop (≥ 1100 px): `.app-shell { display:grid; grid-template-columns: minmax(0,1fr) 480px; }`. Put `.app-city` in column 1 as `position: sticky; top: 0; height: 100vh`. Put the banner, panel grid and footer in column 2, scrolling. That keeps the map and the engine column (≈ 1010–1260 px wide at full zoom) on screen while you scroll the panels. Fit the city camera to the column width.
  - Below 1100 px: `.app-city { position: relative; height: 62vh; }` above the panels.
  - Point the tour targets `city` / `stream` at the visible city section.

### P1-2 Late events never show up live in Dead letters
- **Screenshots:** `desktop-20-chaos-late-sent` ("20 late events sent"), `desktop-21-dead-letters-late-tab-empty-after-send` ("No dead letters", Late 0), `desktop-21b-dead-letters-late-rows-after-reload-bba970a` (the rows appear only after a page reload).
- **Where:** `web/src/app/panels/LivePanels.tsx` → `DeadLettersPanel`. The counts are `deadLetters.filter(kind==='LATE').length`, so they come from the client list. The list is fed only by WebSocket `deadLetters` frames, which come from `control-api/.../ws/IncrementalResultRepository.deadLetters` (`written_at >= since`) via `LiveHub`.
- **What is wrong:** on bba970a I pressed **Send late events** and watched for 20 s. The panel stayed at "Late (0)", but `GET /api/results/summary` returned `"late":20`, and `GET /api/results/dead-letters?kind=LATE` returned the rows. They appear only after a reload, from the REST snapshot. The Rejected count was also off: 15 in the panel against 9 in the summary for the same execution. The panel seems to keep rows from earlier executions. DEMO.md 3:30 ("Late rows in **Dead letters**") fails live.
- **Fix:**
  1. Take the header counts from `summary.rejected` / `summary.late` (already in the snapshot). Clear the list when `execution.id` changes.
  2. When the LATE tab is selected, and 2 s and 6 s after a successful **Send late events**, refetch `GET /api/results/dead-letters?kind=LATE&limit=50` and merge.
  3. Server: check why the `NewRowsTracker` `since` misses LATE rows. Likely cause: `written_at` is the worker's transaction start time, which can be older than the tracker's last `since`. Track by `(written_at, event_id)` with a small overlap window, or poll by a sequence id.

### P1-3 One kill gives two recoveries, and the timeline ends on "Recovered in -"
- **Screenshots:** `desktop-28-recovered-banner-bba970a`, `desktop-29-timeline-recovered-in-dash-bba970a`, `desktop-27b-recovering-timeline` (372c09d).
- **WebSocket `execution` frames captured for one Confirm kill (bba970a):**
  ```
  +0.0 s WORKER_DEAD worker-1   gen 2  RECOVERING
  +0.0 s RECOVERING  cp 14      gen 2
  +2.0 s RECOVERED   cp 14      durationMs 1993     RUNNING
  +7.2 s RECOVERING  cp 16      gen 3               (no WORKER_DEAD; the killed worker rejoined)
  +7.2 s CHECKPOINT_COMPLETED 16 while RECOVERING
  +9.0 s RECOVERED   cp 16      (no durationMs)
  ```
- **Where:** `web/src/app/timeline/CheckpointTimeline.tsx:138` (`Recovered in {formatApiDuration(recoveredMessage.durationMs)}`), `timelineModel.ts`, `control-api/.../ws/ExecutionEvents.java` (durationMs only when a WORKER_DEAD started the incident), and the coordinator recovery on worker rejoin.
- **What is wrong:** the audience sees **Recovered in -**. The real 2.0 s figure flashes by and is then replaced by the second, duration-less recovery. On the right edge of the timeline the labels "✓ Recover…" and "✕ worker-1 lost" are clipped and overlap the band. On 372c09d the banner stayed "Recovering from checkpoint #64" for about 12 s after the timeline said RUNNING. The header changed to Running at 16 s while the banner still said Recovering, and "Recovered" was never shown.
- **Fix:**
  - UI: show the newest RECOVERED that has a number. If the latest one has none, keep the previous incident's "Recovered in 2.0 s". Never print "-". Name the second band "Rebalanced after worker-1 rejoined (gen 2 → 3)".
  - Clamp marker labels inside the track: `transform: translateX(-100%)` near the right edge, or move labels to a legend row under the track.
  - Server: keep the incident start until the assignment is stable, so every RECOVERED in the incident gets a `durationMs`. Or emit the rejoin as a separate `REBALANCED` event.

### P1-4 The killed worker never shows **Dead**
- **Screenshots:** `desktop-26-recovering-viewport` ("2 alive", the worker-1 card is gone and worker-2 is now first), `desktop-25b-kill-immediate-workers`, `desktop-27-recovering-workers`.
- **Where:** `LivePanels.tsx` → `WorkersPanel`, which renders only workers in the current `workers` list or shows them as Idle.
- **What is wrong:** DEMO.md 3:30 promises "that row **Dead** (red, `#f87171`)". Live, the worker-1 card disappears ("2 alive"). Then it comes back as **Idle** with "0.0 records/s", and is Running again within about 3 s. Nothing red appears in the Workers panel.
- **Fix:** keep a card for any worker that was seen in the last 10 s but is now `alive:false` or missing. Show it with a red "Dead" pill and "lost at hh:mm:ss". Switch to "Rejoining" when it re-registers. Hold "Dead" for at least 3 s, the same way the banner holds the Recovering state.

---

## P2 — looks wrong

| # | Screenshot | Where | What is wrong | Fix |
|---|---|---|---|---|
| P2-1 | `desktop-12-editor-validation-errors` | `web/src/app/editor/editor.css`, `.pipeline-editor__feedback` | At 1440×900 only 2 of the 3 validation rows fit. The third (`Window 'speed1m' needs a Key-by`) and its **Show me** are below the dialog edge, and nothing scrolls. | Dialog `display:grid; grid-template-rows:auto minmax(0,1fr) auto`. Feedback `max-height:32vh; overflow:auto`. Or shrink the canvas when errors show. |
| P2-2 | `mobile-10c-editor-validate-offscreen-bba970a`, `mobile-10-editor-open` | `PipelineEditor.tsx` header and `editor.css` | At 390 px the editor dialog is wider than the screen. **Validate** sits at x = 405 and the canvas is cut off. You cannot use the editor on a phone. | Below 700 px: wrap the header (`flex-wrap`), stack palette / canvas / inspector, `width:100vw`, and hide the minimap. |
| P2-3 | `desktop-22-slow-output-viewport`, `desktop-23-slow-output-workers`, `desktop-23b-slow-output-chaos` | `ChaosPanel.tsx` hint and `WorkersPanel` | With **200 ms per record** the **Backpressured** pill never showed in 75 s. 372c09d: sink queues stayed 0/1000. 44dfe07: the queue peaked at 612/1000. The hint promises that "the engine pushes back". | Show live fill per queue ("Sink 61%") and an amber "Filling" state at ≥ 50%. Check that slow-sink is applied after a redeploy, since the queue was 0. Or ship a 500 ms option for the demo. |
| P2-4 | `desktop-02-first-screen`, `desktop-15-after-run` (372c09d) | `SpeedWindowsPanel` | After a restore or Run, Speed windows says "No speed windows yet — start the demo pipeline." while the banner says Running. This is still round-1's issue. | When the execution is RUNNING: "Waiting for the first 1-minute window…". |
| P2-5 | `desktop-90-during-redeploy-stopped-reconnecting`, `desktop-10-editor-open` (8868725) | `PipelineEditor.tsx` (`getExamples` once on open), `Footer.tsx` | During a deploy the page shows Stopped / No execution / "Live connection lost". The editor examples stay empty and Validate shows a raw **"Request failed (502)"**. The footer says "Deployed version: unknown". Nothing recovers until you reload. | Retry examples when the socket reconnects. Map 502/503 to "Server restarting — try again in a few seconds". Refetch `/api/version` on reconnect. |
| P2-6 | `desktop-50-first-screen-bba970a` | `panels.css` | City canvas text and lane bars show through the 12 px gaps between cards ("ng" at x ≈ 945, y ≈ 865, and P2 bars at x ≈ 1110). It reads as rendering noise. | Fixed by P1-1. Until then, give `.panel-grid` an opaque backdrop. |
| P2-7 | `desktop-21b-dead-letters-late-rows-after-reload-bba970a` | `DeadLettersPanel` | LATE reasons show raw epoch ms: "window [1795428720000,1795428780000) already closed (watermark …)", cut off with "…". | Format as sim time: "Window 10:12–10:13 already closed (watermark 10:15)". Keep the raw text in `title`. |

## P3 — nice to have

| # | Screenshot | Where | What / fix |
|---|---|---|---|
| P3-1 | `desktop-15-after-run`, `desktop-01-load-0.9s-no-intro-splash` | `ExecutionBanner` | The banner repeats the state: "Running · Running" (bba970a) and "Running · RUNNING" (372c09d after Run/Restore). → Leave out the text when it matches the pill, or show "Started hh:mm". |
| P3-2 | `desktop-35-ops-restore` | `OpsPanel.tsx:47` | Restore uses a native `window.confirm`. It is unstyled and blocks automation. → Use an inline confirm like **Confirm kill**. |
| P3-3 | `desktop-51-fullpage-bba970a` | `OpsPanel` | Savepoint IDs (`sp-exec-0a80adad-2ebf-…-72`) wrap onto 2 lines. → Truncate in the middle, keep the full ID in `title`, and lead with "checkpoint #72 · 08:14". |
| P3-4 | `desktop-51-fullpage-bba970a` | `ScenarioPanel` | Descriptions are cut off ("A truck breaks down during its ro…"). → Allow 2 lines. |
| P3-5 | `desktop-37-scenario-rush-hour`, `desktop-38-city-rush-hour` | simulator / city | After **Rush hour** the panel says "Active: Rush hour", but the city still shows 8 trucks and ~40 ev/s. Nothing visible changes. → Show the active scenario in the city status pill, and make rush hour visibly change the speed or truck count. |
| P3-6 | `desktop-01-load-0.9s-no-intro-splash`, `mobile-01-…` | `IntroSplash.tsx` | The intro card was not on screen 0.9 s after load in a fresh context, on either viewport. → Check `shouldShow` on a slow tunnel. Start the 2 s timer at first paint, not at mount. |
| P3-7 | `desktop-29-timeline-…`, `mobile-22-timeline-bba970a` | `CheckpointTimeline.tsx` | "Last checkpoint #16" has no duration after recovery, and checkpoint tick labels are 9 px. → Show "(— ms)" only when known. Use 11 px labels. |
| P3-8 | `desktop-91-no-webgl-blank-city-fullpage` | `web/src/city/CityView.tsx` | Without WebGL the city is a blank navy area with no message. → Catch the Pixi init failure and show "The city view needs WebGL" plus the ev/s readout. |
| P3-9 | — | city | Truck hover (Speed / Route / Partition) was not checked. Headless hover could not reliably land on a moving truck. Check it by hand. |

---

## DEMO.md does not match the live app (bba970a)
1. **"AppShell … does not import `OpsPanel`, `ScenarioPanel`, `TourOverlay`, or `IntroSplash`"** is out of date. All four are mounted. **Operations** (Take savepoint / Restore / Rescale), **City scenarios** (Rush hour / Breakdown truck / Breakdown / Surge / Reset) and **Tour** (8 steps, Close / Back / Next / Finish, "STEP N OF 8") all work. Steps 6:15, 7:15 and 8:00 should click the buttons and keep the curls as fallback. Remove "There is no Next button" and "Do not look for **Take savepoint**". Remove Q12 ("not mounted").
2. **Legend:** the live legend is Driving, Unloading, **Idle**, Stopped, **Event**, Rejected, Late. DEMO.md lists five.
3. **Deliveries caption:** the live text is "N deliveries in the last completed sim minute" plus "sim minute · latest highlighted". "Latest completed simulation minute" is gone.
4. **Dead letters:** the tabs are **Rejected (N)** / **Late (N)**, not "REJECTED / LATE" with "Rejected N · Late N". Also, live late rows do not appear until reload (P1-2).
5. **Kill:** no row turns **Dead**. The worker card disappears, then shows Idle, then Running (P1-4). The timeline ends on **Recovered in -** after the second recovery (P1-3). The banner does show **Recovered** on bba970a.
6. **Backpressured** did not appear within 75 s at 200 ms per record. DEMO's "do not wait" fallback is needed in practice.
7. **Alerts:** the empty text is now "No alerts — every truck is moving." **Run** and **Restore** both start a new execution, so the stuck-truck alert needs about 90 s again. Say so before clicking either.
8. **Step 4 (30 min before) and 0:45:** the city stream and engine are not visible on the page (P1-1). Until that is fixed, open round-1 `desktop-03-city-zoomed.png` for the engine view. I did not check whether collapsing panels with **−** uncovers enough of it.
9. These are correct as written: examples (Broken logistics demo / Truck city…), "This pipeline has 3 mistakes - press Validate", the three error codes, **Run** disabled until valid, `Valid - 11 operators, 6 partitions`, **Plan order:** (src → del → … → alertSink), "Kill worker worker-1? It will crash without warning.", "Busiest worker (worker-1)", "Worker worker-1 killed.", "Recovering from checkpoint #64 (generation 6 → 7)", "1,286 / 1,286 results match" and "Checked up to checkpoint #70 - replayed 762,625 events in 8,545 ms", "Restart the pipeline from this savepoint?", "execution exec-… does not need a rebalance", and "Deployed version: bba970a".

## How this was captured
Playwright 1.x Python with `channel="msedge"` and the args `--enable-unsafe-swiftshader --ignore-gpu-blocklist`. Viewports were 1440×900 and 390×844
(`is_mobile`, `has_touch`). Every control was clicked by its accessible name. Recovery was polled every 0.4 s, and WebSocket `execution` frames
were recorded through `page.on("websocket")`. I ran 2 kills on 44dfe07/372c09d and 1 on bba970a, plus 1 editor Run, 2 savepoints, 2 restores, 2 rescales (both 409) and 1 rush-hour then reset on the shared live demo.
