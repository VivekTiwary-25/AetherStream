# Read-only preview Playwright results

Target: `http://127.0.0.1:8089`; Chromium; 1440 × 900.
Run started: `2026-11-20T00:41:55.854Z`; runner duration: 96.275 s; exit code: 1.

## Execution

From `tests/ui/`:

```bash
npm install --package-lock=false --no-audit --no-fund
npx playwright install chromium
unset ALLOW_KILL
export GLORIOUS_URL=http://127.0.0.1:8089
export PLAYWRIGHT_HTML_OPEN=never
export PLAYWRIGHT_HTML_OUTPUT_DIR=../../design/ui-glorious/proof/preview-e2e/html-report
export PLAYWRIGHT_JSON_OUTPUT_NAME=../../design/ui-glorious/proof/preview-e2e/results.json
npx playwright test -c playwright.glorious.config.ts glorious.spec.ts glorious-demo-path.spec.ts --reporter=list,html,json
```

**2 passed, 2 failed, 0 flaky.** Each failing test exhausted the configured single retry. The destructive recovery spec was excluded by the requested file selection, not executed as a skipped test (JSON reports zero skipped); `ALLOW_KILL` was unset. No source changes, port 8088 requests, container operations, commits, or branch changes were made.

## Per-test outcomes

| Test | Result | Exact failure / evidence | Classification |
| --- | --- | --- | --- |
| `glorious.spec.ts:8` — Home media loads and Live World shows six truck markers | PASS | Media visible; six marker buttons; monitored MapLibre worker errors empty. | Expected map display of six, independent of the simulator fleet size. |
| `glorious.spec.ts:27` — Truck Speeds drills down in the same window and returns to the list | PASS | Chart visible; same single dialog and title ID; position/size within 2 px; Back restores selected row. | No failure. |
| `glorious.spec.ts:63` — Logs & Recovery shows three workers covering partitions P0–P5 | FAIL, both attempts | At line 77: `expect.poll(() => partitions.count(), { message: 'Worker card 1 must show at least one partition' }).toBeGreaterThanOrEqual(1)`; expected `>= 1`, received `0`, timeout 20000 ms. | Test/DOM contract mismatch, not the preview history limit and not missing visible partitions. Screenshot and accessibility snapshot show worker-1 P0/P3, worker-2 P1/P4, worker-3 P2/P5. Supplemental DOM inspection finds no descendant `data-testid` values in the worker cards, so `/^partition-chip-[0-5]$/` matches nothing. GET `/api/workers` returns 200 with those assignments; GET `/api/recovery/history` returns 404 and the UI honestly displays `Recovery history unavailable: endpoint not found`. |
| `glorious-demo-path.spec.ts:14` — new-UI demo path records six non-destructive walkthrough steps | FAIL, both attempts | Initial attempt, line 35: `await trucks.first().hover()` raises `TimeoutError: locator.hover: Timeout 10000ms exceeded`, with `element is not stable`. Retry, line 37: `await expect(truckCard).toBeVisible()` fails strict mode: `map.getByLabel(/details$/)` matches both `<div aria-label="Truck T-01 details">` and `<button aria-label="Close truck details">`. | Neither failure is caused by GET-only APIs, missing history endpoints, or fleet size. The first is moving-marker actionability; [INFERENCE] an automation/motion timing issue, not proof that a human cannot hover. The retry is an ambiguous test locator; the screenshot visibly shows speed, route, partition and worker in the open truck card. No hover-card rendering bug established. |

The original demo run reached only Home and Live World screenshots; steps 4–6 were not executed by that test. Its failures must not be described as successful six-step coverage.

## Supplemental browser observations

A separate browser session used the same target without changing the test suites. Truck speed drilldown and real failed-record rows were captured in `supplemental/`. It confirmed rendered partition assignments and missing chip test IDs in `supplemental/evidence.json`.

Following the walkthrough navigation into Pipelines while Failed Records remained open caused a separate interaction failure: clicking the first example prompt timed out because a `.an-row--failure` button in the still-open Failed Records dialog intercepted pointer events. `supplemental/supplemental-stopped.png` shows that dialog covering the prompt area. This is not a read-only backend limitation; whether persistent floating dialogs are intentional is not established, but the walkthrough needs to close the dialog or otherwise account for it.

## Artifacts

- `html-report/index.html`: self-contained Playwright HTML summary with copied attachment data and trace viewer assets.
- `results.json`: machine-readable outcomes and exact errors for every attempt.
- `runner-output.txt`: original runner output.
- `test-results-glorious/`: all four failing-attempt directories, screenshots, error contexts, traces, and two demo videos.
- `test-results/demo-path/`: original Home and Live World walkthrough screenshots.
- `supplemental/`: isolated observations, screenshots, and browser video; not replacements for the failed test outcomes.
- `pipeline-isolated/`: fresh-page Generate investigation, screenshot, browser video, and request/status evidence.

## Isolated Pipelines availability

A fresh page avoided the retained Failed Records dialog and successfully clicked the first saved prompt and Generate. `POST /api/glorious/ai/generate` returned **403**; the actual UI rendered **`Request failed (403)`**. `.gl-generated` never appeared during the 60000 ms observation, so no generated/saved fallback pipeline, Run control, results window, or editor-tab continuation was exercised. This is the documented read-only preview limitation, with an honest unavailable state rather than a fabricated success; no backend capability or Run success is claimed.

## Remaining work outside this no-source-change task

Resolve the ambiguous hover-card locator and moving-marker actionability, reconcile partition-chip test IDs with the served build, and account for retained analytics dialogs in the walkthrough. Generate/Run end-to-end success requires a write-enabled backend with the Glorious endpoints; this preview cannot establish it. No production UI defect is proven by the two suite failures themselves.

Artifact-copy verification matched SHA-256 for all 17 copied original result files; HTML, JSON, runner output, and supplemental evidence files were present and nonempty.
