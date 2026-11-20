# Definition-of-done evidence checklist

`VERIFIED-LIVE` means observed with live data. `VERIFIED-OFFLINE` means test/static evidence only. `BLOCKED` means the requested live check could not run; reason is listed. `UNVERIFIED` means no adequate evidence.

| Requirement | Evidence | Status |
|---|---|---|
| Demo 1 — Home looping video and glass pill | `BOSS_STATE.md` preview saw Home; no playback/loop proof | UNVERIFIED |
| Demo 2 — Live World navigation and map | `BOSS_STATE.md`: six real moving trucks on live preview | VERIFIED-LIVE |
| Demo 3 — Truck hover card with real telemetry | `BOSS_STATE.md`: live hover card with real partition/worker | VERIFIED-LIVE |
| Demo 4 — Truck Speeds history graph | `BOSS_STATE.md`: chart seen with live data; `GAP7.md` confirms completed-window label uses timestamps (static review) | VERIFIED-LIVE |
| Demo 5 — Failed Records with reasons | Build #4 (~05:57): polished window with live REJECTED rows showing partition/offset | VERIFIED-LIVE |
| Demo 6 — Ask AI → review → editor → Run → results | Ask AI generation 4/4 via live bridge; executor passed 3/3 examples against real in-JVM Kafka, offline; live run unverified because stack unavailable | BLOCKED — live run unverified |
| Demo 7 — `worker kill 2` and recovery history | `proof/kill/REPORT.md`: no healthy preview/RUNNING execution during readiness window; disk full on utkarsh-pc, stack unavailable; no kill attempted | BLOCKED |
| Recovery history attribution and restore checkpoint | `RecoveryHistoryCoordinatorTest`: real Coordinator + embedded Postgres, no Docker; worker-2 attribution and restore checkpoint verified | VERIFIED-OFFLINE |
| Smoke 1 — Home → Live World, map with 6 trucks | `BOSS_STATE.md`: live preview | VERIFIED-LIVE |
| Smoke 2 — Truck Speeds row opens chart in same window | `proof/preview-e2e/RESULTS.md`: Playwright drilldown passed on live preview | VERIFIED-LIVE |
| Smoke 3 — Logs & Recovery lists 3 workers with partitions | Visible worker assignments confirmed, but Playwright test failed because partition-chip test IDs were absent; recovery-history endpoint returned 404 | UNVERIFIED — rerun pending |
| Ask AI visitor-prompt evaluation | `proof/ai-eval/README.md`: 14/15 valid real bridge pipelines; median 5.8 s; impossible prompts handled honestly; not an app Run/results test | VERIFIED — BRIDGE EVALUATION |
| API-down polling recovery | `BOSS_STATE.md` records views recover ≤15 s; `web/src/glorious/data/backoff.test.ts` verifies failure delays of 5→10→15 s | VERIFIED-OFFLINE |
| Logs & Recovery visual polish | `proof/polish/` visual polish evidence; does not verify live recovery history or interactions | VERIFIED-OFFLINE |
| Preview demo-path Playwright smoke | `proof/preview-e2e/RESULTS.md`: failed on moving-marker hover and ambiguous locator; fixes since landed, rerun pending | UNVERIFIED — rerun pending |
| Pre-freeze check-all | `BOSS_STATE.md`: `check-all.ps1` passed on vivek-pc (web, Java JDK 21, AI bridge); Docker ITs and Playwright not run | VERIFIED-OFFLINE |
| All 7 demo steps end-to-end with real data | Live pipeline run and worker kill/recovery remain unverified/blocked | BLOCKED |
| Terminal kill, accurate recovery details, persistent history | `proof/kill/REPORT.md`: not tested; preview unavailable | BLOCKED |
| Classic UI at `?ui=classic` and tests | `CLASSIC-CHECK.md`: routes rendered; 154 tests passed, 3 skipped; production build passed | VERIFIED-OFFLINE |
| Nothing visible is fake; nothing committed to main | No complete live/source and branch-state proof | UNVERIFIED |

Other evidence: 06:00 checkpoint decision **KEEP `full`** (Ask AI + editor): valid 4/4 AI generation and executor proven on 3/3 in-JVM Kafka examples; live execution remains unverified. If the live run fails, switch `web/src/glorious/pipelines/mode.ts` to `'editor'` or `'hidden'`. Build #4 (~05:57) showed six live trucks and StatusPill **Running** at ~40 ev/s. `docs/expo/DEMO-NEW-UI.md` documents no-internet behavior (guidance, not runtime verification). Eleven pre-freeze bug fixes were completed. `proof/polish/` and `proof/pipelines-polish/` are visual polish evidence, not live-backend proof. Mobile is held stretch work, not merged or approved for demo.
