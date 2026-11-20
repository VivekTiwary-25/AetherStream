# Classic UI compatibility check 2

## Verdict

**Not a clean pass:** installation and production build passed; the full Vitest suite failed. No application code was changed because this check did not establish a classic runtime regression attributable to the branch changes. Source confirmation is not browser or live-backend verification.

Checked on 2026-11-20:
- HEAD: `b8f8636b5f105aa955806791064621ae6a573d7c`.
- `origin/main`: `d8f3600ef49d72380781a069effa700281a0b14b`.
- Windows; Node `v26.13.0`; npm `11.20.0`.

## Changed web files outside Glorious directories

Ran `git diff --stat origin/main...HEAD -- web/`: **336 files changed, 14,664 insertions, 6 deletions**. Filtered the complete filename list to exclude `web/src/glorious/` and `web/public/glorious/`; exactly these four files remain:

- **`web/index.html` — yes, loading behavior can change.** Adds an unconditional high-priority preload of `/glorious/home-poster.jpg`, including on `?ui=classic`. This adds image/network work; it does not change classic markup or selection logic.
- **`web/package.json` — installation/build behavior can change; no direct classic component change.** Adds `@fontsource/inter`, `maplibre-gl`, `pmtiles`, and `react-rnd`. Existing dependency ranges and scripts in this diff are unchanged. Glorious is statically imported by the shared entry, so its dependency/style loading is not isolated by selecting classic.
- **`web/package-lock.json` — installation/dependency graph can change; no direct classic component change.** Adds the new dependencies and transitive packages (363 additions, 2 deletions). The diff does not change existing resolved package versions; an existing package's development-only classification changes. The requested install used `--package-lock=false`, so its result is not an `npm ci` reproducibility check.
- **`web/src/main.tsx` — yes, selection and initial rendering change.** Replaces unconditional classic rendering with the selector below, makes classic `App` a lazy import, and uses `Suspense fallback={null}` while it loads. `GloriousApp` and its styles are still imported eagerly. The default UI changes from classic to Glorious.

`git diff --quiet origin/main...HEAD -- web/src/App.tsx web/src/app web/src/city web/src/api web/src/styles` exited **0**: no branch differences in those classic/shared implementation paths. This does not eliminate shared-entry, dependency, CSS-loading, or performance risks.

## Source selection confirmation

`web/src/main.tsx:10-19` defines `wantsClassic()`:
1. `new URLSearchParams(window.location.search).get('ui') === 'classic'` selects classic, regardless of saved preference.
2. `ui === 'glorious'` selects Glorious, regardless of saved preference.
3. Otherwise, `localStorage.getItem('aether.ui') === 'classic'` selects classic. Missing or unrecognized query values fall through to this preference.
4. Missing/non-classic preference, or a storage exception, selects Glorious. **Default without a saved preference: Glorious.**

`web/src/main.tsx:21-25` renders either the lazy classic `App` inside `Suspense` or `GloriousApp`, under React StrictMode. `web/src/App.tsx` renders the existing `AppShell`. The inspected Glorious theme declarations in `web/src/glorious/shell/tokens.css` are scoped to `.glorious-app`; shell rules use Glorious class names. This is a source check, not a complete CSS-isolation or visual-parity proof.

## Requested command results

All commands ran from `web/`, with a 600-second timeout each; no interactive input was used. Git/environment inspection commands had 30-second timeouts.

- **PASS** — `npm install --package-lock=false --no-audit --no-fund`: added 382 packages in approximately 81 seconds. Warnings: deprecated `whatwg-encoding@3.1.1`, `glob@10.5.0`, and `eslint@9.39.5`; npm also reported an unapproved `esbuild@0.28.2` install script. No approval/settings changes were made.
- **FAIL** — `npx vitest run`: exit 1; **37 test files passed, 6 failed (43 total)**; **263 tests passed, 34 failed, 3 skipped (300 total)**. Reported duration: 103.14 seconds.
- **PASS** — `npm run build`: `tsc -b && vite build` completed; Vite transformed 931 modules and reported `built in 37.23s` (approximately 118 seconds for the whole command). Non-fatal warning: some minified chunks exceed 500 kB; `LiveWorld` is 1,090.34 kB.

Requested command totals: **2 passed, 1 failed, 0 skipped**. No command was rerun to obtain a different result.

### Failed test names

**`src/app/__tests__/a11y.test.tsx` — 10 failures**, suite `axe accessibility`:
- `checks the speed windows panel`
- `checks the deliveries panel`
- `checks the alerts panel`
- `checks the workers panel`
- `checks the execution banner panel`
- `checks the chaos controls panel`
- `checks the reference comparison panel`
- `checks the operations panel`
- `checks the city scenarios panel`
- `checks the checkpoint timeline panel`

These fail on `localStorage.clear()` in setup at line 60 and cleanup at line 72: `Cannot read properties of undefined (reading 'clear')`.

**`src/app/__tests__/appShell.test.tsx` — 5 failures**, suite `App shell integration`:
- `mounts all dashboard panels, opens the editor, and shares one WebSocket`
- `mounts the operations and scenario panels as collapsible cards`
- `reaches the example picker from the shell editor`
- `shows the intro splash once per session and the Tour button opens the tour`
- `every tour step targets an element that exists in the shell`

These fail on `localStorage.clear()` in setup at line 52 and cleanup at line 65 with the same undefined-storage error.

**`src/app/shell/__tests__/CityStage.test.tsx` — 4 failures**, suite `CityStage`:
- `detects software WebGL renderers only`
- `shows the static city on a software renderer and lets the presenter opt back in`
- `keeps the live city on hardware and honours a stored static choice`
- `falls back when the live city renders too slowly`

These fail on `localStorage.clear()` in setup at line 18 and cleanup at line 24 with the same undefined-storage error.

**`src/app/tour/__tests__/tour.test.tsx` — 9 failures**:
- `TourOverlay > has 8 steps and navigates with arrow keys, remembering the step`
- `TourOverlay > Next/Back buttons work and Esc closes`
- `TourOverlay > resumes from the stored step and finishes on the last step`
- `TourOverlay > does not crash when the spotlight target is missing or the selector is invalid`
- `TourOverlay > shows a spotlight when the target exists`
- `TourOverlay > survives storage that throws`
- `IntroSplash > is skipped with reduced motion`
- `IntroSplash > shows once per session and hides after 2 seconds`
- `IntroSplash > can be skipped`

These fail on `localStorage.clear()` in setup at line 15 with the same undefined-storage error. Despite their wording, the two names containing “skipped” above are failures, not skipped tests.

**`src/glorious/pipelines/__tests__/panels.test.tsx` — 5 failures**, suite `EditorPage`:
- `loads the AI hand-off from localStorage and shows validation errors on the node`
- `restores this tab’s edited pipeline after refresh`
- `restores this tab’s edited pipeline after back`
- `ignores an outdated validation response (200) after editing`
- `ignores an outdated validation response (400) after editing`

These fail on `window.localStorage.setItem()` at lines 66, 79, or 104: `Cannot read properties of undefined (reading 'setItem')`.

**`src/glorious/pipelines/__tests__/pipelines.test.tsx` — 1 failure**:
- `pipeline description > hands a pipeline to the editor through localStorage`

At line 37, `expect(storeHandoff(speeding)).toBe(true)` received `false`. A shared storage/environment cause is plausible but not established for this assertion.

### Skips and other output

The 3 skipped tests consist of 2 in `src/app/__tests__/a11y.test.tsx` and 1 in `src/app/ops/__tests__/OpsPanel.coverage.test.tsx`. The accessibility skips were reported as:
- `axe accessibility > BUG: checks the App shell; the Dead letters panel uses aria-pressed on role="tab" buttons`
- `axe accessibility > BUG: checks the dead letters panel (aria-pressed on role="tab" buttons)`

The run also emitted jsdom canvas-not-implemented messages and Glorious recovery-fetch errors (service unavailable, AbortSignal mismatch, and relative-URL parsing). These messages are not additional failed tests in the totals above.

## Remaining limits and handoff

The 28 classic test failures above occur in storage setup/cleanup rather than proving a changed classic interaction; 6 additional failures are in Glorious pipeline tests. Their root cause and baseline status remain unverified. The suite is therefore **failed**, not waived or reclassified as passed. No runtime fix, test rewrite, dependency adjustment, fixture, or data fallback was introduced.

Browser/visual classic verification was not performed; this task requested source selection confirmation, the full unit suite, and a build. No demo, Docker, port 8088, or swarm command was used. No commit, push, or branch change was made. The requested report under `swarm/reports/` was not created because the higher-priority repository restriction prohibits modifying anything under `swarm/`; this file contains the handoff instead.
