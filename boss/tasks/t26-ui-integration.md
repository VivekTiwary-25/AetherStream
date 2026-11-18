# T26: UI integration: one shell with city, panels, editor, chaos, compare on the shared API client

Lane 6 (INTEGRATION task - runs alone). On main there are now: the shell (web/src/app/shell/**), shared API client and store
(web/src/api/**, web/src/app/state/**), panels (web/src/app/panels/**), the city (web/src/city/**, with its own
citySocket.ts/cityTypes.ts), the pipeline editor (web/src/app/editor/**), and chaos + compare (web/src/app/chaos/**).
Wire them into one polished app. Read all of them first.

Files (edit only these):
- web/src/app/shell/** , web/src/App.tsx, web/src/styles/global.css
- web/src/city/citySocket.ts, web/src/city/cityTypes.ts, web/src/city/CityView.tsx (only to switch the city to the shared
  `useAetherSocket()` / store from web/src/api/ws.ts so there is ONE WebSocket connection; keep a fallback-free code path)
- web/src/app/__tests__/** (add an integration test)

Layout (bento, glass, dark, readable; motion only on state change):
- City fills the background. Top-left: title "AetherStream" + StatusPill (execution state) + connection status dot.
- Top-centre: ExecutionBanner.
- Right column bento: SpeedWindowsPanel (tall), DeliveriesPanel, AlertsPanel; bottom row: WorkersPanel (wide),
  DeadLettersPanel, ChaosPanel, ComparePanel. Panels collapsible to a small chip (remember state in localStorage, try/catch).
- Top-right: "Pipeline" clay button opens the PipelineEditor overlay; onStarted closes it and shows the banner.
- Keyboard: `E` opens the editor, `Esc` closes overlays. Footer (version + MOCK badge) stays.
- At widths < 1100 px panels stack in a scrollable side sheet over the city.
- No mock code paths in production: everything talks to relative `/api` and `/ws`.

Test: render App with fetch/WebSocket mocked; assert all panel titles, the editor opens on the Pipeline button, and only one
WebSocket is constructed.

Acceptance check: `ci-web` green on your branch.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql. If a contract looks wrong, follow it anyway and say so in your result.
- Do not change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only create/edit the files listed under "Files". If you truly need another file, say so in your result instead of editing it.
- Your PC may not have Java, Node or Docker. The acceptance check runs in GitHub Actions on your task branch (CI runs on push to task/**). Write code carefully so it compiles first time; keep it simple and readable.
- Do not commit secrets, build outputs, node_modules or .gradle/ folders.
- In your result, list what you built, how the acceptance check proves it, and anything left undone.
