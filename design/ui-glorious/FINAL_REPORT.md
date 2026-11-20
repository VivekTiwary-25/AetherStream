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

## Other evidence and remaining work

- `CLASSIC-CHECK.md`: classic routes rendered without backend; 154 tests passed, 3 skipped; production build passed. **VERIFIED-OFFLINE**, not live compatibility.
- `OFFLINE-CHECK.md`: static asset review only; runtime still needs the same-origin app/API server. `docs/expo/DEMO-NEW-UI.md` documents no-internet behavior; guidance, not a runtime check.
- Build #4 also showed StatusPill **Running** at ~40 ev/s. Eleven pre-freeze bug fixes were completed.
- `proof/polish/` and `proof/pipelines-polish/` show UI polish; fixture screenshots do not verify backend integration. Mobile is held stretch work, not merged or approved for demo (`BOSS_STATE.md`).
- All-seven-steps end-to-end, live pipeline execution, worker kill/recovery history, and exact smoke interactions remain unproven. No merge decision is implied; review and deployment follow `docs/expo/MERGE-UI-GLORIOUS.md`.
