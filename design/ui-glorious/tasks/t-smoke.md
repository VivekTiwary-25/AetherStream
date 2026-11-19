# New-UI Playwright smoke tests (3 tests, fast, no AI)

Files: tests/ui/glorious.spec.ts (new), tests/ui/playwright.glorious.config.ts (new), tests/ui/package.json (add a script `test:glorious` only), tests/ui/README.md (add 5 lines). Do NOT edit the existing specs or tests/ui/playwright.config.ts. Do NOT read or describe swarm/.

Read web/src/glorious/** to find selectors (prefer roles/aria-labels and data-testids that exist: Truck markers have aria-label "<Truck T-0n>, <status>, <speed> kilometers per hour"; analytics: `analytics-truck-speeds`, `truck-row-<truckId>`, `truck-speed-chart`; Logs & Recovery (being built in web/src/glorious/recovery/, testids `logs-recovery`, `worker-card-<workerId>`, `partition-chip-<n>`)). The sidebar items are Home, Live World, Analytics, Pipelines, Settings.

Config: baseURL from env `GLORIOUS_URL` (default `http://localhost:8089`), Chromium, 1440x900, NO storageState (new UI is the default; the classic suite uses a localStorage flag aether.ui=classic which must NOT be set here), reducedMotion 'reduce' to keep it fast/stable, retries 1, test timeout 60 s.
Tests:
1. Home loads (video or poster visible) → click "Live World" → the map is visible and exactly 6 truck markers are present (wait up to 20 s for live data).
2. Open Analytics → Truck Speeds → click the first truck row → `truck-speed-chart` appears in the SAME window (window bounding box unchanged within 2 px) → back arrow returns to the list.
3. Settings → Logs & Recovery → it lists 3 worker cards, each showing at least one partition chip P0–P5, and all six partitions appear across the cards.
Keep each test independent (fresh page). No waits on fixed sleeps; use expect polling.
Check: `npx playwright test -c playwright.glorious.config.ts --list` lists 3 tests and `npx tsc --noEmit` (or a quick type check) passes. If a stack happens to be reachable at GLORIOUS_URL, run them and report; otherwise say "not run: no stack".
Node: if not on PATH prepend %USERPROFILE%\tools\node.
