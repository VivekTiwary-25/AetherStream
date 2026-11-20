# Definition-of-done evidence checklist

`VERIFIED-LIVE` means observed with live data. `VERIFIED-OFFLINE` means test/static evidence only. `BLOCKED` means the requested live check could not run; reason is listed. `UNVERIFIED` means no adequate evidence.

| Requirement | Evidence | Status |
|---|---|---|
| Demo 1 — Home looping video and glass pill | `BOSS_STATE.md` preview saw Home; no playback/loop proof | UNVERIFIED |
| Demo 2 — Live World navigation and map | `BOSS_STATE.md`: six real moving trucks on live preview | VERIFIED-LIVE |
| Demo 3 — Truck hover card with real telemetry | `BOSS_STATE.md`: live hover card with real partition/worker | VERIFIED-LIVE |
| Demo 4 — Truck Speeds history graph | `BOSS_STATE.md`: chart seen with live data; `GAP7.md` confirms completed-window label uses timestamps (static review) | VERIFIED-LIVE |
| Demo 5 — Failed Records with reasons | `BOSS_STATE.md`: live rows seen | VERIFIED-LIVE |
| Demo 6 — Ask AI → review → editor → Run → results | Ask AI generation 4/4 via live bridge (`BOSS_STATE.md`); live pipeline run blocked because stack never came up; Kafka integration test passed 3 examples offline | BLOCKED — live run unverified |
| Demo 7 — `worker kill 2` and recovery history | `proof/kill/REPORT.md`: no healthy preview/RUNNING execution during readiness window; disk full on utkarsh-pc, stack unavailable; no kill attempted | BLOCKED |
| Smoke 1 — Home → Live World, map with 6 trucks | `BOSS_STATE.md`: live preview | VERIFIED-LIVE |
| Smoke 2 — Truck Speeds row opens chart in same window | Chart seen live (`BOSS_STATE.md`); same-window interaction not recorded | UNVERIFIED |
| Smoke 3 — Logs & Recovery lists 3 workers with partitions | Live worker cards seen (`BOSS_STATE.md`); exact three-worker/partition check not recorded | UNVERIFIED |
| All 7 demo steps end-to-end with real data | Live pipeline run and worker kill/recovery remain unverified/blocked | BLOCKED |
| Terminal kill, accurate recovery details, persistent history | `proof/kill/REPORT.md`: not tested; preview unavailable | BLOCKED |
| Classic UI at `?ui=classic` and tests | `CLASSIC-CHECK.md`: routes rendered; 154 tests passed, 3 skipped; production build passed | VERIFIED-OFFLINE |
| Nothing visible is fake; nothing committed to main | No complete live/source and branch-state proof | UNVERIFIED |

Other evidence: `OFFLINE-CHECK.md` is static asset/request review; `GAP7.md` is source inspection. `proof/polish/` and `proof/pipelines-polish/` are visual polish evidence, not live-backend proof. Three `PipelineRunsKafkaIntegrationTest` cases passed against real in-JVM Kafka; this is offline integration evidence, not a live preview run. Mobile is a held stretch, not merged or approved for demo.
