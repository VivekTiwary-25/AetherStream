# ui-glorious — final evidence report

`DONE-CHECKLIST.md` is the status source. At ~05:57, live preview build #4 showed six live trucks, the polished Failed Records window with live REJECTED rows and partition/offset, and StatusPill Running at ~40 ev/s. Other live observations remain limited to the surfaces listed below; they do not prove the complete demo path.

## Demo path
1. Home video loop — **UNVERIFIED**; Home was seen, playback/loop was not.
2. Live World and map — **VERIFIED-LIVE**; six live trucks seen.
3. Truck hover card — **VERIFIED-LIVE**; real partition/worker shown.
4. Truck Speeds — **VERIFIED-LIVE**; chart seen with live data. `GAP7.md` independently confirms the completed-window label follows returned timestamps, by source review.
5. Failed Records — **VERIFIED-LIVE**; live REJECTED rows showed partition and offset.
6. Ask AI → Run → results — **BLOCKED** for live execution. AI generation succeeded 4/4 through the live bridge. The 06:00 checkpoint decision was **KEEP `full`** (Ask AI + editor): generation was valid 4/4, and the executor passed 3/3 examples against real in-JVM Kafka, but the live run remained unverified because the stack was unavailable. One-line fallback: `web/src/glorious/pipelines/mode.ts` → `'editor'` or `'hidden'` if live execution fails.
7. Worker kill/recovery — **BLOCKED** for live execution. `proof/kill/REPORT.md` records connection failures throughout the readiness window; no kill or late-event injection was attempted. Separately, `RecoveryHistoryCoordinatorTest` passed offline with the real Coordinator and embedded Postgres (no Docker), verifying worker-2 attribution and restore checkpoint.

## New pre-freeze evidence
- Ask AI's real bridge evaluation produced valid pipelines for 14/15 visitor prompts (median 5.8 s); impossible requests were handled honestly. This does not verify the UI Run/results path.
- Read-only preview Playwright: Smoke 1 (six markers) and Smoke 2 (Truck Speeds drilldown) **PASS, VERIFIED-LIVE**. Demo-path and Logs & Recovery smokes failed on test/DOM issues; fixes have landed, but reruns are pending.
- API-down offline test evidence: polling backs off 5→10→15 s and views recover within 15 s. Logs & Recovery received visual polish; screenshots are polish evidence, not live recovery verification.
- `check-all.ps1` **PASS** on vivek-pc: web checks, Java tests (JDK 21), and AI bridge. Docker integration tests and Playwright suites were not run.

## Other evidence and remaining work

- `CLASSIC-CHECK.md`: classic routes rendered without backend; 154 tests passed, 3 skipped; production build passed. **VERIFIED-OFFLINE**, not live compatibility.
- `OFFLINE-CHECK.md`: static asset review only; runtime still needs the same-origin app/API server. `docs/expo/DEMO-NEW-UI.md` documents no-internet behavior; guidance, not a runtime check.
- Build #4 also showed StatusPill **Running** at ~40 ev/s. Eleven pre-freeze bug fixes were completed.
- `proof/polish/` and `proof/pipelines-polish/` show UI polish; fixture screenshots do not verify backend integration. Mobile is held stretch work, not merged or approved for demo (`BOSS_STATE.md`).
- All-seven-steps end-to-end, live pipeline execution, worker kill/recovery history, and the demo-path/Logs & Recovery smoke reruns remain unproven or pending. Smoke 1 and Smoke 2 are verified as noted above. No merge decision is implied; review and deployment follow `docs/expo/MERGE-UI-GLORIOUS.md`.
