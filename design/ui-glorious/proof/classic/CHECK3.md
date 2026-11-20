# Classic UI compatibility check 3

**Not a clean pass.** Classic selection is unchanged in source and all executed classic/shared tests passed on each run. Each full suite failed one Glorious assertion, and `npm run build` failed a Glorious test-fixture typecheck. No proven classic regression justified a code change.

## Scope and source assessment

Checked on 2026-11-20 in the task checkout. HEAD and `origin/ui-glorious` both resolved to `073dc92bea486c47ae7f5f256b8d919548972286`; comparison base `bca11f3` resolved to `bca11f3f6be2a5184b67eee5f6514f4c5578617d`. Node `v24.12.0`, npm `11.6.2`, Windows.

`git diff --stat bca11f3..origin/ui-glorious -- web/` reported **38 files changed, 1,560 insertions, 134 deletions**. Filtering the complete `git diff --name-status` list excludes `web/src/glorious/` and `web/public/glorious/` and leaves exactly these three files:

| Changed file | Classic behavior / production-build impact |
| --- | --- |
| `web/vite.config.ts` | Adds test-only setup files, a 15-second test timeout, and conditional `--no-experimental-webstorage` worker arguments for forks/threads. React plugin, development proxies, and production-build settings are unchanged. The top-level flag-support lookup executes while loading the config but does not change process arguments or browser code. No intended classic runtime change. |
| `web/src/test/setup-webstorage.ts` (new) | Vitest setup only: if a storage global is missing or unusable, restores jsdom's storage object via a configurable getter. Changes test storage behavior, not classic production storage. TypeScript includes it under `src`, so a type error here could still fail `tsc -b`; `noEmit: true` prevents compiler output. |
| `web/src/test/setup-testing-library.ts` (new) | Vitest setup only: increases Testing Library's async-query timeout to 4 seconds. Changes test timing, not production UI timing. Also included in the production typecheck, but not a browser-entry import. |

No `web/src/app/**`, `web/src/App.tsx`, `web/src/main.tsx`, web dependency/lockfile, or other shared web file changed in this comparison. CHANGELOG is outside the requested `web/` diff scope. Glorious runtime changes are present inside the excluded directory; because the entry still imports Glorious eagerly, unchanged classic source is not proof of complete loading/performance isolation.

## Classic selector (source confirmation)

`web/src/main.tsx:10-19` still checks `new URLSearchParams(window.location.search).get('ui')` first. Exact `ui=classic` returns true before reading localStorage; exact `ui=glorious` returns false. Other/missing query values use `localStorage['aether.ui'] === 'classic'`; a storage exception returns false. Thus `?ui=classic` overrides the stored preference and still selects classic even if storage would throw.

At `main.tsx:21-25`, a true result renders the lazy `./App` inside `Suspense fallback={null}` under StrictMode; `web/src/App.tsx` renders `AppShell`. Default without a classic preference remains Glorious. This is source confirmation, not browser/visual verification.

## Command results

All commands ran from `web/` without interactive input. Installation and build had 600-second command deadlines; the sequential three-run Vitest command had a 1,200-second deadline. Git/environment commands had 30-second deadlines.

- **PASS** — `npm ci --no-audit --no-fund`: added 382 packages; wall time 85.61 seconds. npm warned about deprecated `whatwg-encoding@3.1.1`, `glob@10.5.0`, and `eslint@9.39.5`.

Each requested full `npx vitest run` used Vitest `v3.2.7`, without filtering, retries, or test/config changes:

| Run | Exit | Test files (48 total) | Tests (389 total) | Reported duration |
| --- | --- | --- | --- | --- |
| 1 | 1 | 47 passed, 1 failed | 385 passed, 1 failed, 3 skipped | 137.96 s |
| 2 | 1 | 47 passed, 1 failed | 385 passed, 1 failed, 3 skipped | 122.66 s |
| 3 | 1 | 47 passed, 1 failed | 385 passed, 1 failed, 3 skipped | 106.55 s |

All three fail only `src/glorious/pipelines/__tests__/pipelines.test.tsx:128:79`, `error rendering > explains a busy AI and other server errors honestly`: the assertion expects `/busy/`, but receives `"The AI is still finishing the previous request. Try again in a few seconds, or pick a suggestion to use a saved example."` These are failed full suites, not waived passes. No classic regression was established by this Glorious wording assertion, so no code or tests were changed.

Every executed classic/shared test passed in all three runs, including `appShell`, `CityStage`, `tour`, and the 10 executed classic accessibility checks that failed during CHECK2's storage setup. The 3 skips remain: 2 in `src/app/__tests__/a11y.test.tsx` and 1 in `src/app/ops/__tests__/OpsPanel.coverage.test.tsx`. This does not establish their skipped behavior as correct or prove CHECK2's Node 26 environment is fixed; this checkout was tested on Node 24.

Non-fatal stderr includes jsdom canvas-not-implemented messages and recovery/fleet unavailable or fetch/AbortSignal/relative-URL errors. The counts above are Vitest's final results, not counts inferred from stderr.

## Production build

**FAIL** — `npm run build` (`tsc -b && vite build`) exited 1 after 34.72 seconds. TypeScript stopped before Vite:

```text
src/glorious/accessibility.test.tsx(154,19): error TS2740: Type '{ workerId: string; alive: true; partitions: number[]; }' is missing the following properties from type 'WorkerInfo': state, sourceQueue, sinkQueue, backpressured, and 2 more.
```

The requested production command therefore emitted no Vite build to inspect. This is a Glorious test-fixture compile failure, not evidence of a classic runtime regression; it was reported without modifying the test, compiler scope, or application code.

### Supplemental emitted-output check (not a passing production command)

To finish the reachable bundle check without suppressing or changing the typecheck failure, ran **`npx vite build`** once, followed by an inline Node output scanner, under a 600-second deadline. **PASS** for this bundler-only check: Vite `v7.3.8` transformed 932 modules and reported `built in 52.81s`; the combined build/scan command completed in 63.99 seconds. The classic `App-Bpu4QNlB.js` chunk was emitted (75.37 kB). A non-fatal warning remains for chunks over 500 kB; `LiveWorld` is 1,091.95 kB.

The scanner recursively enumerated **347 output files** and scanned all **26 JS/CSS/HTML/JSON/source-map files**. No output path contained `setup-webstorage`, `setup-testing-library`, or a `test`/`__tests__` directory. No scanned file contained `setup-webstorage`, `setup-testing-library`, `asyncUtilTimeout`, `globalThis.jsdom`, `@testing-library`, or `--no-experimental-webstorage`.

Source search found the setup paths referenced only by `test.setupFiles` in `vite.config.ts`, with no references under `web/src` or `index.html`; the TypeScript configuration has `noEmit: true`. Together with the emitted-output scan, this confirms the two setup modules are not shipped in the supplemental production bundle. **The requested `npm run build` remains failed**; bundler-only success does not validate the full production build.

## Remaining limits

The Glorious error-message assertion and accessibility fixture type error remain unresolved; neither established a classic regression, so report-only scope was retained. No browser/visual, live-backend, or Node 26 parity check was performed.

## Restrictions and handoff

No Docker, live demo, port 8088, swarm command, commit, push, or branch change was used. The task asks for `swarm/reports/t261120-1224-3de1.md`, but the governing repository instructions forbid modifying anything under `swarm/` for this worker; that file was not created. This report is the permitted handoff location.
