# Definition-of-done evidence checklist

**DEMO READY 20 Nov 12:33** — host vimlesh-pc, ui-glorious commit fb333a3 (public link kept off the public repo). Nothing is refreshed onto the demo host without Vivek's OK.

`VERIFIED-LIVE` means observed with live data. `VERIFIED-OFFLINE` means test/static evidence only. `BLOCKED` means the requested live check could not run; reason is listed. `UNVERIFIED` means no adequate evidence.

| Requirement | Evidence | Status |
|---|---|---|
| Demo 1 — Home looping video and glass pill | `BOSS_STATE.md` preview saw Home; no playback/loop proof | UNVERIFIED |
| Demo 2 — Live World navigation and map | `BOSS_STATE.md`: six real moving trucks on live preview | VERIFIED-LIVE |
| Demo 3 — Truck hover card with real telemetry | `BOSS_STATE.md`: live hover card with real partition/worker | VERIFIED-LIVE |
| Demo 4 — Truck Speeds history graph | `BOSS_STATE.md`: chart seen with live data; `GAP7.md` confirms completed-window label uses timestamps (static review) | VERIFIED-LIVE |
| Demo 5 — Failed Records with reasons | Build #4 (~05:57): polished window with live REJECTED rows showing partition/offset | VERIFIED-LIVE |
| Demo 6 — Ask AI → review → editor → Run → results | `proof/standby-utkarsh/pipelines/REPORT.md`: 3 examples validated and ran; `partition-2` returned 190 real rows, but `speeding` and `avg-speed-30s` returned 0. Editor Run stayed Disconnected; Ask AI returned HTTP 502 without a bridge. | PARTIAL - fix in progress |
| Demo 7 — `worker kill 2` and recovery history | Demo host vimlesh-pc (commit fb333a3), real kill test: kill 11:12:20.374, WORKER_DEAD +2.0 s (re-registered), RECOVERING +2.0 s, RECOVERED +3.8 s from checkpoint #37, generation 1→2, recovery 1803 ms; screen times match the history JSON. Worker keeps its own partitions (restarts before the 4 s heartbeat timeout; no reassignment, by design for the demo). Survivor “Rejoined” label / rebalance banner fix pending (not on the demo host). | VERIFIED-LIVE (survivor label fix pending) |
| Recovery history attribution and restore checkpoint | `RecoveryHistoryCoordinatorTest`: real Coordinator + embedded Postgres, no Docker; worker-2 attribution and restore checkpoint verified | VERIFIED-OFFLINE |
| Smoke 1 — Home → Live World, map with 6 trucks | `BOSS_STATE.md`: live preview | VERIFIED-LIVE |
| Smoke 2 — Truck Speeds row opens chart in same window | `proof/preview-e2e/RESULTS.md`: Playwright drilldown passed on live preview | VERIFIED-LIVE |
| Smoke 3 — Logs & Recovery lists 3 workers with partitions | `glorious.spec.ts:63`: 3 workers with partitions passed live on the standby. | VERIFIED-LIVE |
| Ask AI visitor-prompt evaluation | `proof/ai-eval/README.md`: 14/15 valid real bridge pipelines; median 5.8 s; impossible prompts handled honestly; not an app Run/results test | VERIFIED — BRIDGE EVALUATION |
| API-down polling recovery | `BOSS_STATE.md` records views recover ≤15 s; `web/src/glorious/data/backoff.test.ts` verifies failure delays of 5→10→15 s | VERIFIED-OFFLINE |
| Logs & Recovery visual polish | `proof/polish/` visual polish evidence; does not verify live recovery history or interactions | VERIFIED-OFFLINE |
| Preview demo-path Playwright smoke | `proof/preview-e2e/RESULTS.md`: failed on moving-marker hover and ambiguous locator; fixes since landed, rerun pending | UNVERIFIED — rerun pending |
| Pre-freeze check-all | `BOSS_STATE.md`: `check-all.ps1` passed on vivek-pc (web, Java JDK 21, AI bridge); Docker ITs and Playwright not run | VERIFIED-OFFLINE |
| All 7 demo steps end-to-end with real data | Pipeline outputs and worker reassignment/UI limitations remain; see standby reports. | PARTIAL - fix in progress |
| Terminal kill, accurate recovery details, persistent history | vimlesh-pc live kill: checkpoint #37 restore, 1803 ms recovery, history times match the server JSON; earlier standby run `proof/standby-utkarsh/kill/REPORT.md` (checkpoints 44/58, history retained). No partition reassignment (honest demo). | VERIFIED-LIVE (survivor label fix pending) |
| Classic UI at `?ui=classic` and tests | `proof/classic/CHECK2.md`: production build passed; Vitest failures included Node 26 `localStorage` environment errors; classic runtime not visually verified. | VERIFIED-OFFLINE |
| Nothing visible is fake; nothing committed to main | `proof/fake-audit/B.md` and `proof/fake-audit/C.md` audit recovery and pipeline value sources/failure states; these scoped audits do not establish app-wide absence of fake data or branch state. | UNVERIFIED |

Standby evidence confirms one of three example pipelines produced real results and two did not; the editor Run window stayed disconnected and Ask AI returned HTTP 502 without a bridge. Worker kills recovered from checkpoints 44 and 58, but partition reassignment was not observed and the survivor “Rejoined” label is unreliable. Smoke 3 passed live. Fake-data audits cover recovery and pipelines only. Classic Vitest storage failures were attributed to the Node 26 environment; the suite remains failed and classic runtime was not visually verified.
