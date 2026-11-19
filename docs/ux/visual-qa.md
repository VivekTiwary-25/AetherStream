# AetherStream — visual QA of the live app (T91)

Captured 2026-11-19 (03:20–03:32) from the public deployment with headless Microsoft Edge (Playwright),
at **1440×900 (desktop)** and **390×844 (mobile, touch)**, device scale 1. Each page ran for about 60 s before the main
captures. The recovery state was triggered only through the public API (`POST /api/chaos/kill-worker`, body `{}`).
The app code was not changed.

**Builds seen.** The app was redeployed three times during the session: `7c45ceb` → `59a374f` → `de12694`. Most
screenshots are from **59a374f**. Files with `build-7c45ceb` in the name come from the first build. The recovery
viewport/full-page shots (`desktop-04`, `desktop-05`) come from **de12694**. The layout was the same in all three builds.

**Not mounted in any build:** pipeline editor, chaos panel, compare/reference result. The page has no "Validate",
"Chaos", "Kill" or "Compare" controls, so there are no screenshots of them. Capture them again once they are integrated.

Severity: **Critical** = broken or misleading at first glance · **High** = clearly wrong, hurts trust or readability ·
**Medium** = visible polish problem · **Low** = nit.

---

## Cross-cutting issues (fix these first)

| # | Issue | Severity | Seen in | Suggested fix |
|---|---|---|---|---|
| X1 | **Dashboard panels overlap each other.** `.panel-grid` has a fixed height (~336 px), but panels grow to 830–1120 px. Workers covers the Deliveries caption, Speed windows (when populated) runs under Alerts/Dead letters, and Dead letters runs past the footer. | Critical | desktop-02, mobile-02, all `panel-*` crops | Remove the fixed grid height. Use `grid-auto-rows: minmax(0, auto)` (or masonry columns), and give each panel `max-height` + `overflow:auto` on its body. |
| X2 | **Broken page layout under the city.** The heading card is only 393 px wide on the left. The panel grid starts at x≈656, so a 630×400 px empty void sits bottom-left. The footer floats in that void at y≈1158. | Critical | desktop-02, desktop-06 | Put heading + execution banner in one full-width row. Let `.panel-grid` span all 12 columns below it, with the footer last. |
| X3 | **Recovery state contradicts itself.** The city pill says "Recovering from checkpoint #186", but the banner still says "Checkpointing · Checkpoint #185". The header says "Healthy", the legend says "3/3 workers · healthy", and the Workers panel shows all three workers Healthy right after a worker was killed. | Critical | desktop-04, desktop-05, `*-panel-workers-after-kill` | Drive the city pill, banner, header badge and worker cards from one execution state. Show the killed worker as "Down → Restarting" for at least a few seconds. |
| X4 | **The recovery moment is too short to see.** "Recovering…" was visible for about 1–2 s. One run of the banner went straight to "Recovered in 0.0 s", and "0.0 s" reads as fake. | High | desktop-recovered-banner, poll log below | Hold the Recovering state for at least 3 s (UI-side minimum). Show the measured recovery time with ms precision, or "< 0.1 s". |
| X5 | **Execution banner text runs together.** It renders as "Checkpoint #148Live" and "Checkpoint #130Live connection lost — reconnecting…", with no gap or separator and no panel styling. On mobile "#149Live" wraps onto a new line. | High | `*-execution-banner`, desktop-02b, mobile-recovering-banner | Make the banner a flex row with `gap`. Put "Live" in its own pill. Put connection status in a separate chip with an icon. |
| X6 | **Status pills show two dots** ("● ● Healthy", "● ● Checkpointing"). An icon and a `::before` dot both render. | Medium | heading, banner, worker cards | Keep one dot. Remove the duplicate `::before` or the inline glyph. |
| X7 | Bold label and value run together in lists: "truck-00128.1 km/h", "min3,483 s ago", "payload.speedKmh10:09". | High | speed windows, alerts, dead letters | Use a two-column row (`display:flex; justify-content:space-between`) or add a separator span. |
| X8 | **Mobile: the footer is hidden under panels.** "Deployed version: …" shows faintly behind the Workers card. | High | mobile-04-recovering-panels, mobile-06 | Fixing X1 fixes this. Keep the footer in normal flow after the grid. |

---

## Per-screenshot findings

### desktop-01-app-viewport-60s.png — first screen after 60 s (1440×900)
- **Works well:** the city is rich and readable. Moving trucks, cyan data packets flowing into the stream-engine column, and per-partition rate bars give a strong "live" feel. The glass cards (title, Running pill, Add truck, legend) are consistent. The dark navy palette matches the design system.
- **Medium:** the map is offset right inside the city card. About 300 px of the left side is empty except for the title card. → Centre the map in the area left of the engine column, or let it fill the space at desktop width.
- **Medium:** the stream-engine column text is tiny (~7 px "6.7/s", "Running" under ev/s, ring tick labels "0 1 2") and low contrast. → Use at least 11 px text and at least 4.5:1 contrast for the rate labels. Drop the ring tick labels.
- **Medium:** the hint "scroll to zoom · drag to pan · double-click to reset" is ~10 px grey-on-navy (est. < 3:1) and overlaps the map's top edge. → Move it into the legend bar, or show it as a brief toast on first hover.
- **Low:** truck ID labels ("001", "006") are ~6 px and unreadable at default zoom. → Hide them below a zoom threshold, or show them on hover.
- **High:** nothing below the fold hints that panels exist. Only the panel headers peek in at y≈800. → Fixing X2 and shortening the city card to ~70vh helps.

### desktop-02-app-fullpage-60s.png — full page (1440×1192)
- **Works well:** the Deliveries/min bar chart is clean. The Alerts card has a clear count badge.
- **Critical:** X1 and X2. The panels overlap, and the bottom-left is empty.
- **High:** Speed windows shows "No speed windows yet — start the demo pipeline." while the engine runs at ~40 ev/s and the pipeline is already running (builds 59a374f/de12694, for 60 s+). The empty state blames the user for something they already did. → Say "Waiting for the first 1-minute window…" once the pipeline runs. Check the data feed too, because the panel did populate on build 7c45ceb.
- **Medium:** the Deliveries headline number ("1") is not explained, and its caption "Latest completed simulation minute" is cut off by the Workers card. → Fixing X1 fixes this. Also add a unit: "1 delivery in the last sim minute".

### desktop-02b-fullpage-reconnecting-build-7c45ceb.png — full page, earlier build
- **Works well:** the speed sparklines appeared in this build, so the panel can work.
- **High:** "Live connection lost — reconnecting…" is appended as plain text to the banner (X5). It has no colour, icon or prominence, and the city keeps animating as if live. → Use a warning-coloured chip, and dim or pause the city while disconnected.
- **Critical:** the populated Speed windows panel (1124 px tall) runs under Alerts and Dead letters (X1).

### desktop-03-city-zoomed.png / mobile-03-city-zoomed.png — zoomed city (5 wheel steps)
- **Works well:** zoom is smooth and crisp. Buildings, parks, the depot glyph and the "DEPOT" label scale well. Trucks have clear headlights and an orange "unloading" ring. Overlays (title, Running, Add truck, legend) stay fixed.
- **Medium:** zooming hides the stream-engine column, and nothing marks where the camera is. → Add a mini-map or a "Reset view" chip while zoomed.
- **Low:** while zoomed, data packets float over rooftops away from roads, which reads as noise. → Fade packets at higher zoom, or keep them on the road network.
- **Medium (mobile):** the wheel zoom worked in headless mode, but no touch hint is shown ("pinch to zoom"). The desktop hint text is missing on mobile. → Show a touch-specific hint.

### desktop-04-recovering-viewport.png / desktop-05-recovering-fullpage.png — "Recovering from checkpoint #N" (build de12694)
- **Works well:** the city tells the recovery story well. The purple "Recovering from checkpoint #186" pill appears, packets turn purple, partition rates drop to 0.0/s, a purple scan line sweeps the engine column, and the rate falls to 20.0 ev/s.
- **Critical:** X3. The banner says "Checkpointing #185", the header says "Healthy", and the legend says "3/3 workers · healthy". Checkpoint numbers disagree by one between the city (#186) and the banner (#185). The same off-by-one showed in normal running ("Checkpoint #189 saved" in the city vs "#188" in the banner).
- **High:** X4. The state lasted ~1–2 s (poll log: 4.4 s Recovering → 6.2 s "Recovered in 0.0 s").
- **Medium:** the purple pill text (#a78bfa-ish on navy) is fine, but the engine caption "Recovering from checkpoint #186" under the ring is ~6 px and unreadable. → Remove it (the pill already says it), or enlarge it.

### desktop-06-after-recovery-fullpage.png — ~20 s after recovery
- **Works well:** throughput returned to ~40 ev/s, and partitions were rebalanced across all three workers (0,3 / 1,4 / 2,5).
- **High:** nothing in the dashboard records that a failure happened. There is no event in Alerts and no "last recovery" stat. → Add a recovery event to Alerts ("worker-1 killed · recovered from #172 in 0.9 s").
- **Critical:** X1/X2 are still present (the Workers card hangs to y≈1190, and the left column is empty).

### desktop-execution-banner.png / mobile-execution-banner.png
- **Works well:** the purple "Checkpointing" pill is clearly distinct from the green "Healthy".
- **High:** X5 (the "#148Live" run-on). Also, the banner is a bare row with no glass panel, unlike every other element. → Style it as a slim glass bar matching the heading card.
- **Medium:** X6 double dot.

### desktop-recovered-banner.png / mobile-recovering-banner.png
- **Works well:** the "! Recovering" pill uses an icon as well as colour (good for colour-blind users).
- **High:** "Recovered in 0.0 s" (X4). On mobile, "Recovering from checkpoint" / "#149Live" wraps awkwardly across two lines. → Shorten it to "Recovering · #149" on narrow screens.
- **Low:** the "!" glyph and the dot together are redundant. → Keep only "!".

### desktop-panel-speed-windows.png / mobile-panel-speed-windows.png (empty) and desktop-panel-speed-windows-populated-build-7c45ceb.png
- **Works well (populated):** one sparkline per truck, and the latest speed is shown in bold.
- **High:** misleading empty state while running (see desktop-02).
- **High:** X7, "truck-00128.1 km/h" (the ID and value merge, so it reads as truck 00128).
- **Medium:** the sparklines are 3 px pure white, with no axis, min/max or units, and the time label "11:19" floats 40 px below the line. With 8 trucks the panel becomes 1124 px tall. → Use thinner (1.5 px) cyan lines, a shared y-scale label, and a 2-column compact grid. Cap the height and scroll.
- **Low (mobile):** the empty-state text is clipped at the bottom ("pipeline" cut in half) by the next card (X1).

### desktop-panel-deliveries-min.png / mobile-panel-deliveries-min.png
- **Works well:** a simple, legible bar rhythm.
- **Medium:** the bars are flat pure white (#fff) and are the brightest thing on the page, louder than the city. → Use the accent cyan at ~70% opacity, and highlight the latest bar.
- **Medium:** there are no axis, time labels or tooltips. The headline number is not tied to a bar. → Label the latest bar, and add a "min" x-axis with first/last timestamps.
- **Critical:** the caption is covered by Workers on desktop, and Alerts/Dead letters cover the bars on mobile (X1).

### desktop-panel-alerts.png / mobile-panel-alerts.png
- **Works well:** short, plain-language alert text, and the count badge is in the header.
- **High:** X7, "min3,483 s ago". The relative time is also in raw seconds. → Format it as "58 min ago" and put it on its own muted line.
- **Medium:** the alert "truck-007 has not moved for 10 min" stays for 50+ min with no severity colour or dismiss option. → Add a severity stripe (amber), a "since 10:12" timestamp, and auto-resolve when the truck moves.
- **Medium (mobile):** the alert text is clipped mid-line by the Workers card (X1).

### desktop-panel-dead-letters.png / mobile-panel-dead-letters.png
- **Works well:** it separates REJECTED and LATE, and shows a clear empty message ("No rejected records yet.").
- **High:** the header is cramped at a 179 px column width. "Dead / letters" wraps onto two lines, and "Rejected 1 · Late 199" wraps onto three. → Give the panel a wider column, or move the counts into the filter tabs ("Rejected (1)", "Late (199)").
- **High:** the REJECTED/LATE tabs look like unstyled browser buttons: flat grey blocks, stacked vertically, with no selected state. → Style them as a segmented control with a clear active state.
- **High:** record rows are clipped at the right edge ("payload.speedKmh10:0…"), and the reason and timestamp are merged (X7). → Wrap or ellipsize with a `title`, and put the timestamp on the right in a muted style.
- **Low:** the Late count sits at 199–200 across reloads, so it may be capped. → Show "200+" if the list is truncated.

### desktop-panel-workers.png / mobile-panel-workers.png / desktop-panel-workers-after-kill.png / mobile-panel-workers-after-kill.png
- **Works well:** the per-worker health badge and partition list are useful, and so are the queue fill bars.
- **Critical:** after kill-worker, all three workers still show "Healthy", with the same partitions and "3 alive" (X3).
- **High:** the card is 832 px tall in a 179 px column. Three workers × (badge + partitions + 2 queues) stack vertically. → Use a compact table row per worker (name · state · partitions · rate · two mini bars) in a wider column.
- **Medium:** the queue bars overflow the card's right padding by ~6 px and touch or cross the card edge. → Set `width:100%` inside the padded box (box-sizing).
- **Medium:** idle workers show "Partitions — · 0.0 records/s" (build 7c45ceb had worker-2 owning all 6 partitions while 1 and 3 were idle). → Show "Idle (no partitions)" in muted text.
- **Medium:** X6 double dot in the Healthy badge.

### mobile-01-app-viewport-60s.png — first screen (390×844)
- **Works well:** the overlays stack sensibly. The title and Running pill fit, and Add truck stays a large touch target.
- **High:** the map is only ~230 px wide inside a 608 px tall card, leaving ~250 px of empty black space. The stream-engine column becomes a 50 px unreadable sliver. → On mobile, fit the map to the card width, hide the engine column (show its numbers in a chip), and shorten the card to ~60vh.
- **Medium:** the heading wraps ("STREAM PROCESSING / CONTROL"), and the "Healthy" pill is vertically centred against two lines. → Shorten the eyebrow on narrow screens, or put the pill under the title.

### mobile-02-app-fullpage-60s.png / mobile-06-after-recovery-fullpage.png
- **Critical:** X1 is at its worst on mobile. Deliveries, Alerts, Dead letters and Workers pile on top of each other, so the Deliveries chart and Alerts text are unreadable. Two 171 px columns are too narrow for these panels.
- **High:** X8, the footer is hidden behind Workers.
- → On mobile (< 600 px), use a single-column flow with natural heights.

### mobile-04-recovering-panels.png — mobile while recovering (scrolled to the banner)
- **Works well:** the banner shows the "! Recovering" pill (in this run the banner and city agreed).
- **High:** Workers still shows "Healthy" for every worker (X3), and the panels overlap (X1).

### mobile-04b-recovering-pill-overlap-build-7c45ceb.png — mobile recovery, earlier build
- **High:** the "Recovering from checkpoint #133" pill is wider than the space left of the title card, so it covers "AETHER CITY". Checkpoint toasts ("Checkpoint #149 saved") collide the same way at 390 px. → Below 480 px, move status toasts below the title card, or truncate them to "Recovering · #133".
- **Low:** double-clicking the map (the documented "reset" gesture) also selected the page heading text ("STREAM" is highlighted). → Add `user-select:none` on the city section, or `preventDefault` on `dblclick`/`mousedown`.
- **Note:** the banner showed "Running · RUNNING" (shouty raw enum) while the city showed Recovering. → Map raw states to human labels.

---

## Motion and accessibility notes (not visible in stills)
- The city animates continuously (packets, headlights, scan line). `prefers-reduced-motion` was **not** tested here. → Verify that the canvas honours it (freeze packets, keep the state colours).
- All status states pair text with colour (Healthy/Checkpointing/Recovering), which is good. The legend dots (Stopped vs Rejected, both red/pink) are hard to tell apart. → Use different shapes or hues.
- The Add truck button was the only city control. Its focus ring was not checked.

## Recovery timeline captured (build de12694, 0.5 s poll)
```
0.0 s  POST /api/chaos/kill-worker → {"workerId":"worker-1"}
0.8–3.9 s  banner "Checkpointing #185"   city "Running"  40.0 ev/s
4.4 s  banner "Checkpointing #185"   city "Recovering from checkpoint #186"  20.0 ev/s
6.2 s  banner "Running · Recovered in 0.0 s"   city "Running"  0.0 ev/s
7.7–9.2 s  rate climbs 25.9 → 39.4 ev/s
17.4 s banner "Checkpointing #188"   city "Checkpoint #188 saved"
28.1 s banner "Checkpointing #188"   city "Checkpoint #189 saved"
```

---

## T98 polish pass — status per item

Checked after T92 was merged (the shell mounts OpsPanel, ScenarioPanel, TourOverlay, chaos, compare and the timeline). Verified
against the dev mock server with headless Edge at 1440×900 and 390×844, plus `npm run lint`, `tsc -b`, `npm test` and `npm run build`.
"Not fixed" items are outside T98's files (city scene, `web/src/api/*`, backend) or need data the UI does not get.

### Cross-cutting
- **X1** panels overlap — Fixed in T98: `.panel-grid` has no fixed row heights any more (6 columns, `grid-auto-rows: auto`); each panel has `max-height: 26rem` and scrolls inside.
- **X2** broken layout / empty void — Fixed in T98: the city is now the full-page backdrop (from T92). The panels form one right-aligned bento block (speed · deliveries · alerts / workers · dead letters / chaos · compare / timeline / ops · scenarios) with the footer last, in normal flow. Also fixed a horizontal overflow on mobile (page was ~770 px wide at 390 px) by giving the shell grid a `minmax(0, 1fr)` column.
- **X3** recovery contradicts itself — Partly fixed in T98: the header badge and the execution banner now use one shared `useExecutionStatus()` hook (same state, same recovery hold), and the recovering checkpoint comes from the RECOVERING event / `restoredFromCheckpoint`, not the last completed one. Not fixed: the Workers panel can only show what `workers` messages report ("Dead" is shown when `alive=false`). The city HUD has its own model (lane 5). See also the dropped-message note under X4.
- **X4** recovery too short — Fixed in T98: the UI keeps Recovering visible for at least 3 s (`RECOVERY_HOLD_MS`). The banner shows "Recovered in …" from the server's `durationMs`, and values under 100 ms (including 0) read "< 0.1 s" instead of "0.0 s". **Not fixed (out of scope):** `web/src/api/ws.ts` only exposes `lastMessage`, so when two WS frames arrive in one React batch (for example RECOVERING followed by a 250 ms `trucks` frame) the earlier one never reaches the store. In one of two mock runs the banner skipped Recovering completely. The fix is to let the store subscribe to every message (for example an exported `subscribe(listener)` in ws.ts).
- **X5** banner run-on text — Fixed in T98: the banner is a glass flex bar with `gap`. Connection status is its own chip (green "● Live", amber "Connecting…", red "⚠ Live connection lost — reconnecting…"). The city is desaturated and dimmed while the socket is disconnected.
- **X6** double dots — Fixed in T98: `StatusPill` renders one marker, either a dot or the "!" / "Ⅱ" glyph for recovering/failed/paused, never both.
- **X7** label/value run together — Fixed in T98: speed rows are a 4-column grid (truck · km/h · sparkline · minute), alert time is on its own muted line, and dead-letter reason and time are split left/right.
- **X8** mobile footer hidden — Fixed in T98 (follows from X1): the footer stays in flow after the single-column panel list.

### Per screenshot
- desktop-01 map offset / engine text ~7 px / ring tick labels / truck labels — Not fixed: city scene (`web/src/city/scene/*`, lane 5).
- desktop-01 zoom hint low contrast, overlapping map edge — Fixed in T98: moved to the bottom-left HUD column at 12 px, 82% slate (passes AA on the navy glass).
- desktop-01 nothing hints at panels below the fold — Fixed in T98: the panels now sit beside the city on the first screen (X2).
- desktop-02 speed empty state blames the user — Fixed in T98: "Waiting for the first 1-minute window…" while an execution is running or recovering. Deliveries and alerts have matching wording.
- desktop-02 deliveries number unexplained — Fixed in T98: "N deliveries in the last completed sim minute".
- desktop-02b reconnecting as plain text, city keeps animating — Fixed in T98 (X5 chip + dimmed city). The canvas keeps rendering; pausing it is lane 5.
- desktop-03 zoom hides the engine, no reset chip / packets over rooftops — Not fixed: city scene.
- mobile-03 no touch hint — Fixed in T98: on `pointer: coarse` the hint reads "pinch to zoom · drag to pan · double-tap to reset". It is still hidden below 760 px by `city.css` (lane 5 file) to keep the small screen uncluttered.
- desktop-04/05 engine caption ~6 px — Not fixed: city scene. Off-by-one between city and banner — partly fixed (banner uses the restore checkpoint, see X3).
- desktop-06 no record of the failure — Fixed by T92 + T98: the Checkpoint & recovery timeline panel shows "✕ worker lost" and "✓ Recovered in …" markers, and the banner shows "Recovered in …". Synthetic alerts were not added, because Alerts only shows engine alert rows.
- execution banner bare row — Fixed in T98 (glass bar). Raw "RUNNING" — Fixed in T98: human labels in the banner, timeline and operations headers.
- recovered banner "0.0 s" — Fixed in T98 (X4). Mobile wrap — Fixed in T98: below 700 px the banner shows "Recovering · #N". "!" plus dot — Fixed in T98 (X6).
- speed windows: 3 px white sparklines, time floats below, panel 1124 px tall — Fixed in T98: 1.5 px cyan lines in compact one-line rows, a caption explains units/axis, and the panel height is capped with scrolling. A single window draws a short flat line instead of nothing.
- deliveries: pure white bars, no axis — Fixed in T98: cyan bars at 55% with the latest bar highlighted, plus first/last sim-minute labels.
- alerts: "s ago" raw seconds, no severity — Fixed in T98: "58 min ago" / "2 h 5 min ago", "since HH:MM", amber stripe. Dismiss/auto-resolve — Not fixed: alert lifecycle is engine/API behaviour.
- dead letters: cramped header, unstyled tabs, clipped rows, capped count — Fixed in T98: counts moved into a segmented control ("Rejected (1)", "Late (200+)") with a clear active state, and reasons are ellipsized with a `title`, time on the right. "200+" is shown when the 200-row client buffer is full.
- workers: tall card, overflowing queue bars, idle wording, double dot — Fixed in T98: compact worker blocks in a wider column, grid-based queue bars with `width:100%`, "Idle (no partitions)", one marker per pill, dead workers get a red outline.
- mobile-01 map width / engine sliver — Not fixed: city scene. Heading wrap — already fixed by T92 (eyebrow hidden below 700 px).
- mobile-02/06 pile-up and footer — Fixed in T98 (X1, X8; single column below 1100 px).
- mobile-04b recovering pill covers "AETHER CITY" — Fixed in T98: the city title card moved down above the dock, and the status pill is ellipsized to the viewport width. Double-click selecting heading text — Fixed in T98: `preventDefault` on multi-click mousedown on the canvas host.
- legend Stopped vs Rejected both red dots — Fixed in T98: Rejected/Late use square marks, truck states keep round dots.
- reduced motion — kept: alert and checkpoint flashes are skipped under `prefers-reduced-motion`, and the global rule disables CSS animation/transition (including the new city dimming). Canvas motion is lane 5.

---

## T121 (round 2)
Round-2 findings and their status are in [`visual-qa-2.md`](visual-qa-2.md). Round-1 items still open after T98: X3/X4
(the Workers panel and dropped WS frames, which need `web/src/api/ws.ts`) and the city-scene items — Left, see visual-qa-2 P2-7…P2-9.
