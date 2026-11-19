# Definition-of-done evidence checklist

Status is strict: VERIFIED-LIVE requires proof showing the behavior with real data. VERIFIED-OFFLINE-ONLY means static/source evidence or non-live UI proof exists, but no real-data proof. UNVERIFIED means no adequate evidence in the listed artifacts.

| Requirement | Evidence file(s) or test name | Status |
|---|---|---|
| Demo path 1 — Home looping video and glass pill | `design/ui-glorious/GAPS.md` §2.1, §3 Home; `home-loop.mp4`; no playback proof | UNVERIFIED |
| Demo path 2 — Live World navigation and map | `design/ui-glorious/proof/map-prod.png` (Disconnected; Fleet unavailable HTTP 404); `proof/map-polish.png` (Disconnected; Fleet unavailable HTTP 500); `GAPS.md` §2.2 | VERIFIED-OFFLINE-ONLY |
| Demo path 3 — Truck hover card with real data | `design/ui-glorious/GAPS.md` §2.3; map proof screenshots show no trucks/card and disconnected data | VERIFIED-OFFLINE-ONLY |
| Demo path 4 — Truck speed history graph | `design/ui-glorious/GAPS.md` §2.4; no live proof | VERIFIED-OFFLINE-ONLY |
| Demo path 5 — Failed Records late/rejected messages and reasons | `design/ui-glorious/GAPS.md` §2.5; no live proof | VERIFIED-OFFLINE-ONLY |
| Demo path 6 — Ask AI → review → editor → Run → results | `design/ui-glorious/GAPS.md` §2.6; AI/API execution and stream delivery unverified | VERIFIED-OFFLINE-ONLY |
| Demo path 7 — `worker kill 2` and recovery shown in Logs & Recovery | `design/ui-glorious/GAPS.md` §2.7; no terminal/recovery sequence proof | VERIFIED-OFFLINE-ONLY |
| Smoke test 1 — Home → Live World, map visible with 6 truck markers | BOSS_PROMPT §5.1; no smoke-test result; map proof screenshots show no fleet | UNVERIFIED |
| Smoke test 2 — Truck Speeds row opens chart in same window | BOSS_PROMPT §5.2; no smoke-test result | UNVERIFIED |
| Smoke test 3 — Logs & Recovery lists 3 workers with partitions | BOSS_PROMPT §5.3; no smoke-test result | UNVERIFIED |
| Definition of done — All 7 demo steps end-to-end on preview with real data | BOSS_PROMPT §7.1; `GAPS.md` §§2, 99; only map screenshots in `proof/`, both disconnected | UNVERIFIED |
| Definition of done — Terminal kill visible; accurate recovery details and persistent history | BOSS_PROMPT §7.2; `GAPS.md` §§2.7, 3 Track C, 87–88; no live kill proof | UNVERIFIED |
| Definition of done — Classic UI works at `?ui=classic`; existing tests pass | BOSS_PROMPT §7.3; `GAPS.md` §§1, 99 notes compatibility and tests unverified | UNVERIFIED |
| Definition of done — Nothing visible is fake; nothing committed to main | BOSS_PROMPT §7.4; `GAPS.md` §99 covers source review only, not full UI or branch/commit state | UNVERIFIED |
