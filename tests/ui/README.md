# Real-stack browser acceptance

Chromium drives the production UI at `http://localhost:8088`, using its real
same-origin `/api` proxy and WebSocket feed. No mocks, network interception,
application edits, or added test IDs. Run only against a disposable demo stack:
the scenario starts an execution and deliberately crashes a worker through the UI.

## Run locally

From the repository root (Docker Compose and Node 22 required):

```bash
export GIT_SHA="$(git rev-parse HEAD)"
docker compose up -d --build --wait
cd tests/ui
npm install --package-lock=false --no-audit --no-fund
npx playwright install --with-deps chromium
EXPECT_SHA="$GIT_SHA" npm test
npm run report
```

PowerShell: set `$env:GIT_SHA` and `$env:EXPECT_SHA` instead of the Bash exports.
Use the same commit for the Compose build and `EXPECT_SHA`; the test never learns
its expected version from the server. After collecting evidence, run
`docker compose logs --no-color` and `docker compose down -v` from the root.
Do not tear down another developer's stack.

## Six independently reported steps

1. Browser title and visible AetherStream heading; footer contains the first seven
   characters of `EXPECT_SHA`; no `MOCK DATA` badge.
2. Click **Start demo pipeline** when available; otherwise use real browser
   `fetch` calls to load, save, and start the demo with a fresh idempotency key.
   Wait up to 60 seconds for a truck speed and verify exactly three live workers.
3. Capture a speed-panel truck, displayed minute, and km/h value in one DOM read.
   Fetch `/api/results/windows?sinkId=speedSink&limit=200` afterward; require a
   unique matching truck/window minute and compare `avgSpeedKmh.toFixed(1)`.
   This tests persisted engine windows, not the simulator's instantaneous speeds.
   The current UI exposes only HH:mm, so ambiguous matches fail rather than
   guessing a window; the exact matching API window boundaries are attached.
4. Open **Pipeline editor**, **Load demo**, wait for its source handle, then
   **Validate** and require the real compiler's success message. If a
   **broken-for-demo** button is offered, validate it and require three errors;
   its absence is annotated, not counted as a failure.
5. Click **Kill worker**, wait for a completed checkpoint, and **Confirm kill**.
   A DOM observer installed before confirmation records the rendered execution
   banner entering checkpoint recovery within 20 seconds, then returning to
   Running within another 60 seconds. API/WS recovery events alone cannot pass.
6. Click **Compare with reference**; within 60 seconds require the success heading
   `N / N results match`, with equal, nonzero counts (not a vacuous `0 / 0`).

Assertions are soft, action failures become named soft failures, and every step
attempts a full-page screenshot even on failure. A failed editor step does not
skip recovery or comparison. Missing UI entry points fail rather than receiving
API fallbacks (only starting the demo permits a fallback).

## Evidence and CI

`playwright.config.ts` uses one Chromium worker, no retries, always-on screenshots,
and video/trace retention on failure. Each step screenshot and the engine sample
are attached to the HTML report; `test-results/**/step-results.json` gives individual
PASS/FAIL outcomes. `results.json` contains the complete Playwright report.
GitHub Actions also receives a six-row step summary.

`.github/workflows/ci-ui-e2e.yml` builds and waits for the entire Compose stack,
installs Chromium with system dependencies, and supplies the workflow commit as
`EXPECT_SHA`. Logs, screenshots, HTML/JSON reports, failure videos, and traces are
uploaded with `always()`, after unconditional log collection and volume cleanup.
The job is temporarily advisory (`continue-on-error: true`), but assertions remain
strict and browser failures remain visible in reports. The inspected shell does
not yet mount the existing editor, chaos, or comparison components; these are
integration failures to fix in the UI lane, not reasons to weaken this scenario.

## Run the new-UI suites against a preview host

From `tests/ui` in Windows PowerShell, install dependencies and run the Chromium
suites against a disposable preview at port 8089:

```powershell
npm install --package-lock=false --no-audit --no-fund
npx playwright install chromium
$env:GLORIOUS_URL='http://localhost:8089'
npx playwright test -c playwright.glorious.config.ts
```

The recovery spec skips unless `$env:ALLOW_KILL='1'`; enabling it kills `worker-2`.
Only enable it on a disposable preview with three live workers and an active
execution. The new-UI tests do not check `EXPECT_SHA`. For the classic suite,
`EXPECT_SHA` must match the preview build; the test does not infer it from the
server. Its committed config hard-codes port 8088, so use a temporary config to
target 8089 without changing that file:

```powershell
@'
import { defineConfig } from '@playwright/test'
import base from './playwright.config.ts'
export default defineConfig({
  ...base,
  use: {
    ...base.use,
    baseURL: 'http://localhost:8089',
    storageState: {
      cookies: [],
      origins: [{ origin: 'http://localhost:8089', localStorage: [{ name: 'aether.ui', value: 'classic' }] }],
    },
  },
})
'@ | Set-Content -Encoding utf8 playwright.preview.config.ts
$env:CLASSIC_ALL_SPECS='1'
npx playwright test -c playwright.preview.config.ts
Remove-Item playwright.preview.config.ts
```

Without `CLASSIC_ALL_SPECS`, the classic config runs only `demo.spec.ts`; setting it
to `1` also runs `features.spec.ts`. Node 26 may require
`$env:NODE_OPTIONS='--no-experimental-webstorage'` when running web unit tests.

The new-UI config uses fresh contexts, no storage state, reduced motion, and no
mocked data. The demo-path spec records video with motion enabled and saves six
named screenshots under `test-results/demo-path/`; it does not use storage state
or destructive actions. It requires a write-enabled AI/pipeline backend to
complete Generate and Run; an unavailable AI service remains a failure, not a
fabricated successful recording.

Discover the new-UI tests without contacting a server:

```bash
npx playwright test -c playwright.glorious.config.ts --list
```
