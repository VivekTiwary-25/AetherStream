# T92: UI shell integration (redo of T26) - mount ops, scenario, tour; finish T26 leftovers  [M3 critical]

Lane 6 (web/app) INTEGRATION task. T26 (merged) built the dark bento shell (web/src/app/shell/AppShell.tsx), shared store and
live panels. These merged components are NOT mounted yet: OpsPanel (web/src/app/ops: savepoint / restore / rescale),
ScenarioPanel (web/src/app/scenarios), TourOverlay + IntroSplash (web/src/app/tour, presenter tour), and the editor example
picker (web/src/app/editor). CheckpointTimeline is mounted but still shows client-derived recovery durations: use the value the
API returns (contract 04) and show "-" when the API gives none.

Do this:
1. Mount OpsPanel and ScenarioPanel as bento cards (collapsible like the others) wired to the shared store / selected execution.
2. Mount IntroSplash + TourOverlay: a "Tour" button in the header starts it; tour steps must target elements that exist
   (fix tourSteps.ts selectors / add data-tour attributes in the shell). Respect storage.ts (seen-once).
3. Make sure the editor example picker is reachable from the shell editor area.
4. Keep the layout readable at 1440x900 and 390x844 (no overlap of the recovering pill, see docs/ux/visual-qa.md).
5. Extend web/src/app/__tests__/appShell.test.tsx: each mounted panel renders; the Tour button opens the tour. Tests must not
   hang or leak timers/sockets (mock WebSocket + fake timers cleaned up in afterEach; vitest must exit).
6. Remove `continue-on-error: true` from .github/workflows/ci-ui-e2e.yml (make it blocking) once its Playwright test passes,
   updating the Playwright spec for the new shell if needed.

Files (edit only these): web/src/app/shell/**, web/src/app/state/**, web/src/app/timeline/**, web/src/app/tour/**,
web/src/app/ops/index.ts, web/src/app/scenarios/index.ts, web/src/app/__tests__/**, web/src/styles/global.css,
web/e2e/** (Playwright specs), .github/workflows/ci-ui-e2e.yml (only the continue-on-error line). Do NOT touch web/src/city/**.

Acceptance check: ci-web and ci-ui-e2e green on your branch (ci-ui-e2e blocking). Mock server stays OFF unless VITE_USE_MOCK=1.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql (if a contract looks wrong, say so in your report).
- Do not read or change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only edit the files listed. If you are on vivek-pc: do NOT start Docker containers or kind there (the live demo runs on it). Acceptance runs in GitHub Actions on your task branch.
