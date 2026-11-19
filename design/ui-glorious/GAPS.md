# Glorious UI gap review
Scope: BOSS_PROMPT sections 0–3 and 5–7, both mockups, and web/src/glorious/ source. Report only; no runtime, backend, scripts, public assets, or classic tests inspected.
DONE means implemented in reviewed source, NOT proved on a live preview. PARTIAL includes explicitly unverified dependencies; it does not mean those dependencies are absent.
Code references below are relative to web/src/glorious/. Requirements are BOSS_PROMPT section/item numbers. Mockup comparison is source-based, not a rendered screenshot comparison.

## Section 1 — shell rules
- DONE — Track folders and shared shell exist; logs use recovery/ rather than the specified logs/: README.md:3–4; GloriousApp.tsx:1–11.
- PARTIAL — Default/classic selection, ?city=classic, old docs and unchanged Playwright compatibility are outside reviewed code; README explicitly records a classic test-selection limitation: README.md:47–58.
- DONE — Neutral white glass, blur, inner highlight, wide shadow and 24px radius match the specified foundation: shell/tokens.css:8–13; shell/shell.css:1–2.
- DONE — GlassWindow has drag, resize, close, front-on-focus, keyboard geometry and drill-down back; content updates retain frame geometry: shell/GlassWindow.tsx:20–48; shell/WindowManager.tsx:14–45.
- DONE — ClayButton styling provides tactile cream treatment and minimum 44px targets: shell/shell.css:33–35.
- PARTIAL — Local Inter and stronger text glass are implemented; actual WCAG AA contrast against rendered backgrounds is not measured: shell/tokens.css:1–16,29–34.
- DONE — One left sidebar, five destinations, Home icon rail, cream active pill, Analytics/Settings fades and back navigation: shell/Sidebar.tsx:10–38; shell/shell.css:11–24.
- DONE — Top-right HTML status text, dot and event rate use dashboard data, including honest disconnection: GloriousApp.tsx:24; shell/status.ts:8–15; shell/StatusPill.tsx:6–9.
- PARTIAL — Home→Live slide, sidebar and window motion exist with reduced-motion handling, but map fly/zoom and looping thinking dots exceed the shell's strict motion list (thinking is separately requested by Track D): shell/router.ts:17; shell/shell.css:61–64; map/LiveWorld.tsx:239,298–305; pipelines/pipelines.css:21–24.
- PARTIAL — Keyboard controls, focus rings and textual states exist; simulator breakdown actions submit without destructive confirmation: shell/GlassWindow.tsx:36–48; shell/tokens.css:34; map/LiveWorld.tsx:353; recovery/TruckBreakdown.tsx:43–52.
- PARTIAL — Source removes palace art, extra main-nav categories and bottom statistics; actual asset/mockup fidelity is unverified: map/LiveWorld.tsx:275–360; shell/Sidebar.tsx:10–16.

## Section 2 — seven demo steps
- PARTIAL — 2.1 Home looping video and glass pill are wired; playback, first-frame poster and loop seam are not observed: home/Home.tsx:5–8; GloriousApp.tsx:24.
- DONE — 2.2 Live World navigation mounts the map with the requested slide-up: shell/router.ts:15–21; GloriousApp.tsx:19–20.
- DONE — 2.3 Hover/focus/tap opens a glass truck card with live telemetry and worker lookup: map/LiveWorld.tsx:268–270,314–318,339–351.
- DONE — 2.4 Analytics→Truck Speeds→truck opens history in the same window with back navigation: analytics/menu.ts:5–13; analytics/AnalyticsPanel.tsx:64–66,80–82,121–128.
- DONE — 2.5 Failed Records exposes late/rejected filters, timestamps, reasons and details: analytics/AnalyticsPanel.tsx:106–111,132–135.
- PARTIAL — 2.6 Generate→review→new-tab editor→Run→results UI exists; genuine AI/API execution and stream delivery are unverified: pipelines/PipelinesPanel.tsx:36–49,77–86; pipelines/links.ts:4–5; pipelines/useRunPipeline.tsx:19–25.
- PARTIAL — 2.7 Logs renders worker loss, restore, ownership, rejoin and retained history; terminal command and real kill/recovery sequence were not exercised: recovery/LogsRecovery.tsx:31–80; recovery/model.ts:48–62; recovery/useRecoveryFeed.ts:38–49,70–74.

## Section 3 — Home
- PARTIAL — Muted/autoplay/loop/playsInline and poster URLs exist; first-frame identity, compression and seam quality are unverified: home/Home.tsx:8.
- DONE — Full-screen cover treatment accepts the media's resolution without stretching: shell/shell.css:3–4.
- DONE — Reduced motion renders only the poster image, not a video element: home/Home.tsx:4–8.
- DONE — Rail and status pill are HTML overlays, not painted controls: GloriousApp.tsx:18–24; shell/Sidebar.tsx:29–38.

## Section 3 — Track A: map
- PARTIAL — MapLibre+PMTiles protocol and local style are wired; archive existence, city coverage and offline glyph/style requests are unverified: map/LiveWorld.tsx:15,84–101.
- PARTIAL — Neutral map UI is coded; warm road/park/water basemap colours live in an unreviewed public style: map/LiveWorld.tsx:87; map/LiveWorld.css:1–8.
- DONE — No decorative palace/castle component is rendered: map/LiveWorld.tsx:275–360.
- PARTIAL — Static GeoJSON routes are loaded with no runtime routing; six-route count and real-road provenance are unverified: map/LiveWorld.tsx:107–115,165.
- PARTIAL — Real speed/status/timeScale drive motion; honest geography mapping and overlap offsets exist. UI truncates to six, which does not prove simulator config is six; routeId may be unavailable: map/LiveWorld.tsx:171–184,347; map/useLiveFleet.ts:9–12.
- PARTIAL — One PNG, horizontal flip and green/amber/grey glow are wired; transparent 3/4 sprite quality is unverified: map/LiveWorld.tsx:189,309–334; map/LiveWorld.css:22–29.
- DONE — Hover, keyboard focus and tap show photo, ID, status, speed, place names and worker ownership; partition helper correctness is external to this review: map/LiveWorld.tsx:268–270,314–318,339–351.
- PARTIAL — Search, recenter and zoom work at UI level; landmark count/OSM coordinates are unverified: map/LiveWorld.tsx:109–116,272–305.
- DONE — No bottom statistics strip in the rendered component: map/LiveWorld.tsx:275–360.
- PARTIAL — Right-click/long-press/keyboard menu submits a real breakdown request; backend support is unverified and action is one-shot, not a toggle: map/LiveWorld.tsx:242–255,317–326,352–354.

## Section 3 — Track B: analytics
- DONE — Exactly five requested Analytics options: analytics/menu.ts:5–10.
- PARTIAL — Multiple stable-ID GlassWindows and refocus exist; opening Analytics from Pipelines leaves the opaque Pipelines page underneath, not the map: analytics/menu.ts:13; shell/WindowManager.tsx:26–42; shell/Sidebar.tsx:38; GloriousApp.tsx:20.
- DONE — Reuses dashboard windows/alerts/dead letters and shared WebSocket data rather than fabricated results: analytics/AnalyticsPanel.tsx:2–4,17,25–39.
- PARTIAL — Truck rows, current speed, average/count, same-window graph/back and keyboard buttons exist; latest speed window is used without requiring a 60-second interval: analytics/AnalyticsPanel.tsx:64–66,75–84,121–128; analytics/data.ts:17–19,41–46.
- DONE — Failed Records displays rejected/late reason, time, partition, offset and event ID: analytics/AnalyticsPanel.tsx:106–111,132–135.
- DONE — Deliveries and Alerts have per-truck/message/time information plus trend; Events Processed has real-rate trends and explicitly labelled partition estimates: analytics/AnalyticsPanel.tsx:86–104.
- PARTIAL — Charts consistently reuse one quiet SVG implementation, not the requested chart library: analytics/charts/Charts.tsx:8–47.
- PARTIAL — Honest empty wording exists, but Failed Records/Deliveries/Alerts bypass the disconnected-state helper; their empty state can imply no records when data is unavailable: analytics/AnalyticsPanel.tsx:88,94,111,142–143.

## Section 3 — Track C: recovery and commands
- PARTIAL — Settings contains Logs & Recovery and Truck breakdown only; backend support for breakdown and existing start/pause/reset controls is outside review: recovery/menu.ts:6–28.
- DONE — Real worker IDs, states, rate, P0–P5 ownership, queue and backpressure information are rendered; count follows reported workers, not a fabricated three: recovery/LogsRecovery.tsx:21–64.
- DONE — Chronological activity/failure feed, newest first: recovery/RecoveryHistory.tsx:6–12; recovery/model.ts:30–36.
- DONE — Completed checkpoints plus per-recovery restored checkpoint are displayed: recovery/LogsRecovery.tsx:66–78; recovery/RecoveryHistory.tsx:21–29.
- DONE — Explicit death facts are held visibly, reassignment shown, survivors retain live ownership/rates, rejoin labelled and failures retained up to 200 events: recovery/model.ts:26–36,48–62; recovery/LogsRecovery.tsx:35–62.
- DONE — Recovery pause is visible during and after recovery, with honest unavailable timing: recovery/LogsRecovery.tsx:31–32; recovery/RecoveryHistory.tsx:25–27.
- PARTIAL — Worker-card fix is present in state derivation; real-kill reproduction and before/dead/recovering/recovered screenshots are unverified. Existing integration tests mock transport: recovery/model.ts:48–61; recovery/recovery.integration.test.tsx:8–17.
- PARTIAL — `worker kill <n>` wrapper is outside reviewed files; UI expects terminal failures through the recovery feed: recovery/useRecoveryFeed.ts:38–49,70; recovery/RecoveryHistory.tsx:6.
- PARTIAL — `events late` wrapper is outside reviewed files; Failed Records supports LATE messages once supplied: analytics/AnalyticsPanel.tsx:108–111.
- PARTIAL — Windows/bash wrappers, clear one-line results and new-UI CHEATSHEET instructions cannot be established from UI source: recovery/RecoveryHistory.tsx:6; README.md:53–58.

## Section 3 — Track D: pipelines
- DONE — Glass Ask AI panel has prompt, Generate, calm waiting indicator, review list, Run and editor handoff: pipelines/PipelinesPanel.tsx:52–86.
- DONE — Visible external-icon Open Editor opens /pipelines/editor in a new tab: pipelines/PipelinesPanel.tsx:57–58; pipelines/links.ts:1–5.
- DONE — JSON model uses nodes and edges: pipelines/model.ts:14–22.
- DONE — Filter has field/op/value: pipelines/model.ts:8,15.
- DONE — Pick Field has fields[]: pipelines/model.ts:9,16.
- DONE — Window Average has field/windowSec/optional groupBy: pipelines/model.ts:10,17.
- DONE — Threshold Alert has field/op/value/message: pipelines/model.ts:11,18.
- DONE — Output has name; exactly five types: pipelines/model.ts:12,19,26–28.
- PARTIAL — Field API and validation API are used, but frontend AI/storage check is deliberately loose; authoritative JSON schema and validation of every server run are unverified: pipelines/EditorPage.tsx:47–53,126–132; pipelines/model.ts:96; pipelines/api.ts:33–43.
- PARTIAL — API run/results/stop integration, GlassWindow results and errors exist; same-stream API execution and unchanged engine/simulator are unverified: pipelines/api.ts:35–38; pipelines/useRunPipeline.tsx:19–25; pipelines/ResultsView.tsx:25–37,59–63.
- DONE — React Flow canvas, five-type drag/click palette, connections, parameter inspector, node validation errors and Run exist: pipelines/EditorPage.tsx:63–69,119–132,167–209.
- PARTIAL — AI POST and honest saved-example notice exist; headless CLI, stdin/argv safety, disabled tools/files, system schema, timeout and single-flight behavior are backend-unverified: pipelines/api.ts:40–43; pipelines/PipelinesPanel.tsx:78.
- PARTIAL — AI_CMD/AI_MODEL/CLAUDE_CONFIG_DIR, fast-model latency and account choice cannot be verified in UI; response model/elapsed time are displayed: pipelines/PipelinesPanel.tsx:81.
- PARTIAL — Invalid-JSON retry and closest-example selection are backend-unverified; malformed response is rejected and server-provided fallback is labelled: pipelines/api.ts:40–43,54; pipelines/PipelinesPanel.tsx:78–81.
- PARTIAL — One real vivek-pc subscription/auth check has no evidence in reviewed source: pipelines/api.ts:41.
- PARTIAL — All three exact rehearsal prompts have hand-written pipeline examples; server-schema validation and use as backend fallbacks are unverified: pipelines/examples.ts:3–44.
- PARTIAL — Mandatory 06:00 keep-AI/manual-only/hide-Pipelines decision is not encoded in the shell; Pipelines and Ask AI are always offered, and checkpoint report is outside review: shell/Sidebar.tsx:10–16; pipelines/PipelinesPanel.tsx:62–73.

## Top 10 demo gaps — highest risk first
1. Real kill/recovery proof is unverified, not absent. Smallest fix: rehearse `worker kill 2`, capture all four states, restored checkpoint, reassignment and retained failure; verify both terminal wrappers and cheatsheet. Owners: recovery/, backend/terminal/docs owners. Evidence: recovery/recovery.integration.test.tsx:8–17; recovery/LogsRecovery.tsx:31–80.
2. Genuine AI→editor→Run→results and subscription auth remain unverified. Smallest fix: one real login check and one complete rehearsal; verify schema, timeout and saved-example fallback at the service. Owners: pipelines/, API/AI service owners. Evidence: pipelines/api.ts:33–43; pipelines/PipelinesPanel.tsx:78–86.
3. Offline map and media delivery are unverified. Smallest fix: load Home and Live World with internet disabled but local API available; check PMTiles/glyphs/routes/sprite/photo and video loop/poster. Owners: public/glorious/, map/, home/. Evidence: home/Home.tsx:7–8; map/LiveWorld.tsx:87,107–109,328,342.
4. Six markers are enforced by slicing, not simulator configuration. Smallest fix: verify/set the existing simulator count to six through config; confirm six real trucks and six road routes. Owners: simulator config, map/. Evidence: map/useLiveFleet.ts:9–12; map/LiveWorld.tsx:165.
5. Pipelines is always exposed regardless of rehearsal outcome. Smallest fix: apply the checkpoint decision before demo: retain full flow only if runnable; otherwise manual editor only, or hide Pipelines if editor fails. Owners: shell/, pipelines/. Evidence: shell/Sidebar.tsx:14; pipelines/PipelinesPanel.tsx:62–73.
6. Analytics opened from Pipelines has no map underneath. Smallest fix: navigate to Live World whenever Analytics is selected from any non-live screen, preserving the same sidebar/windows. Owner: shell/. Evidence: shell/Sidebar.tsx:38; GloriousApp.tsx:20.
7. Required one-minute average is not guaranteed. Smallest fix: confirm the existing speed stream is 60 seconds; if not, derive a genuine one-minute average/count rather than relabel another duration. Owner: analytics/. Evidence: analytics/data.ts:17–19,41–46; analytics/AnalyticsPanel.tsx:82.
8. Failed Records can say “No failed records yet” while disconnected. Smallest fix: use the existing connection/execution-aware empty helper for failed records, deliveries and alerts. Owner: analytics/. Evidence: analytics/AnalyticsPanel.tsx:88,94,111,142–143.
9. Live speed/flow charts start collecting only after their window opens; truck current speed ignores snapshot hydration. Smallest fix: hydrate real snapshot trucks/current flow and clearly label unavailable pre-open history; do not invent samples. Owner: analytics/. Evidence: analytics/AnalyticsPanel.tsx:18–20,25–35,126–128.
10. Breakdown changes real simulator state without confirmation; map action is not a toggle. Smallest fix: add confirmation in both entry points; use a reversible toggle only if the existing API supports it, otherwise label the one-shot action honestly. Owners: map/, recovery/. Evidence: map/LiveWorld.tsx:242–255,353–354; recovery/TruckBreakdown.tsx:43–52.

Checks: static requirement-to-source review and both supplied mockups only; no tests/builds/live commands run. Sections 5/7 smoke tests, preview screenshots, classic compatibility and end-to-end definition of done remain unverified, not declared failing.
Delivery: only this file was written. No swarm/ report was created because the repository instructions prohibit this agent from opening or modifying swarm/.
