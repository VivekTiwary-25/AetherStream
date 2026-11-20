# Review 12:20 — `110f770..origin/ui-glorious` (0be540f6), web/src + scripts + infra

Ranked by impact on the live demo. Only CONFIRMED bugs were fixed; each fix is a few lines with a test or check.

## 1. CONFIRMED, FIXED — production build broken by a test fixture (blocks demo refresh)
- `web/src/glorious/accessibility.test.tsx:154` (from 3df6500f) passed a partial `WorkerInfo` (`workerId`, `alive`, `partitions` only).
  `npm run build` is `tsc -b && vite build`, and `tsc -b` type-checks test files too, so it failed with TS2740.
- What happens: the demo host refreshes to origin/ui-glorious and rebuilds the web image. `infra/docker/web.Dockerfile:8` (`RUN npm run build`) fails, so no new web image gets built. The refresh either aborts or keeps serving the old bundle.
- Fix: the fixture now has every `WorkerInfo` field. Checked: `npm run build` passes (built in 39 s) and accessibility.test.tsx passes 10/10.
- Note: vitest does not type-check, so `npm test` stays green while the build is broken. Add `npm run build` to the pre-merge check (check-all.ps1 already runs `web-build`).

## 2. CONFIRMED, FIXED — useLiveFleet leaks one abort listener per poll
- `web/src/glorious/map/useLiveFleet.ts:68` adds `abortParent` to the controller that lives for the whole mount on every poll. `{ once: true }` only removes a listener once it has fired, and it only fires on unmount, so nothing was ever removed.
- What happens: Live World stays open on the kiosk all day and polls every 3 s, which is about 1,200 closures (each holding an AbortController) per hour, never freed. That is a slow memory creep, not a crash.
- Fix: `removeEventListener` in the `finally` block. New test "does not pile up abort listeners…" counts 11 listeners after 30 s without the fix and 1 with it. All 6 map/analytics test files pass (65 tests).
- The 4 s timeout itself is correct: it aborts both the fetch and the body read, marks controls stale and recovers on the next poll. The `AbortSignal.any` fallback is also correct, because the parent aborts the request controller.

## 3. CONFIRMED, FIXED (dev tooling only, no demo impact) — check-all.ps1 JDK finder dropped two sources
- `scripts/check-all.ps1:84` The rewrite no longer looked at `JAVA_HOME_21` (still used by check-all.sh and the old error text) or at the `java` on PATH. A machine with JDK 21 found only through those now got java-test SKIP plus a failed run.
- Fix: added both as candidates. A PATH entry only counts if `<home>\bin\javac.exe` exists and `-version` reports 21, so JRE-only shims such as the Oracle javapath are rejected. Checked with `check-all.ps1 -DryRun`: the "Places searched" list shows both. This machine has only JDK 25, so the skip there is correct.
- `Get-JavaVersion` reads output only after `WaitForExit`. That is fine for `-version`, whose output is far smaller than the pipe buffer.

## 4. Reviewed, no bug — AI bridge `Connection: close` and body drain (`scripts/ai-bridge/ai_bridge.py:310-329`)
- nginx (`infra/docker/nginx.conf:12-24`) uses a variable `proxy_pass` with no upstream keepalive pool, so it opens a new upstream connection for every request anyway. `Connection: close` changes nothing on that side. Request buffering is on by default, so the bridge always gets a `Content-Length` and the body arrives in full.
- Reading the body before `BUSY.acquire` is an improvement: a slow client can no longer hold the single AI slot. The 429 is delivered cleanly.
- Low, pre-existing: a non-numeric `Content-Length` raises `ValueError` with no response. It was unhandled before too, and nginx never sends one. Not fixed.
- `unittest` for the ai-bridge passes 10/10.

## 5. Reviewed, no bug — Failed Records list (`web/src/glorious/analytics/AnalyticsPanel.tsx:127,177`)
- Removing `.slice(0, 30)` is bounded: the store caps `deadLetters` at 200 (`app/state/store.ts:119,151`), so at most 200 buttons are rendered.
- The new key `JSON.stringify([eventId, kind])` is unique, because `mapFailedRecords` (`analytics/data.ts:89`) de-duplicates on exactly that identity. "Newest first" matches the existing sort.

## 6. Reviewed, no bug — label centring (`map/mapLogic.ts:81`, `LiveWorld.tsx:226`)
- The inline transform replaces `.gl-map-truck__label { left: 50%; transform: translateX(-50%) }` (`LiveWorld.css:29`). `translate(-50%, Ypx)` keeps the centring, and a unit test covers it.

## 7. Reviewed, no bug — vite.config test changes (`web/vite.config.ts:21-24`)
- `setupFiles` and `testTimeout` sit under `test:`, which only vitest reads. `vite build` ignores it, and the production build output is unchanged.

## 8. Not in range — Live World prefetch (GloriousApp.tsx)
- `GloriousApp.tsx` has no changes in `110f770..origin/ui-glorious`, so the prefetch has not merged yet and could not be reviewed here.

## Checks run
`npx vitest run src/glorious/map/ src/glorious/analytics/` (65 passed) · `npx vitest run src/glorious/accessibility.test.tsx` (10 passed) · `npm run build` (passes) · `npm run lint` (0 errors, 7 warnings) · ai-bridge unittest (10 OK) · `check-all.ps1 -DryRun`.
