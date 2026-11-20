# ui-glorious — final evidence report

Evidence refreshed 2026-11-20 from `DONE-CHECKLIST.md`, the proof reports below, and `git log origin/ui-glorious --since=10:00` subjects. Live observations predate subsequent fixes; a committed fix is not a successful live re-test.

## VERIFIED-LIVE — observed scope only

- Live World: six real moving trucks, hover telemetry with partition/worker, Truck Speeds chart and drilldown. Failed Records showed real REJECTED rows with partition/offset. Smoke 1/2 passed; standby Smoke 3 showed three workers with partitions (`DONE-CHECKLIST.md`).
- Standby pipeline APIs: all three examples validated, ran and stopped. `partition-2` returned **190 real rows**; `speeding` and `avg-speed-30s` each returned **0 in 40 s**. Editor Run remained Disconnected; Ask AI returned HTTP 502 with no authenticated bridge. This is **partial**, not Ask AI → Run → results proof (`proof/standby-utkarsh/pipelines/REPORT.md`).
- Standby worker kills: coordinator history confirmed worker-2/worker-1 death and recovery from checkpoints **44/58**, with processing resumed and history retained during observation. **20 LATE records** appeared with closed-window reasons. No partition reassignment was observed; survivor “Rejoined” labels were unreliable, and recovery included a global processing pause. History is capped at 200 events, not permanent (`proof/standby-utkarsh/kill/REPORT.md`).

## Offline / scoped audit evidence

- `proof/check-all/RUN4.md`: web install/lint/tests/build/no-mock and AI-bridge tests **PASS**. Java tests **SKIP** (JDK 21 unavailable); Docker/integration and Playwright **SKIP**. No UI endpoint was checked.
- `proof/classic/CHECK2.md`: install/build **PASS**, full Vitest **FAIL** (263 passed, 34 failed, 3 skipped), largely storage setup errors on Node 26. Classic selection was source-checked, not visually verified. Later commit `cc6b5eed` reports the Node 26 storage fix; it does not replace this run's failed result.
- `proof/fake-audit/A.md`: 62 map/analytics/shell tests and direct typecheck passed; isolated browser outage rendering showed no invented fleet/counters. `B.md`: 25 recovery tests passed. `C.md`: 78 pipeline tests passed with Node Web Storage disabled, plus actual browser failure-state checks. `D.md`: shell/sidebar/home source audit; tests did not start because a dependency was missing.
- Audits improved unavailable/stale values, validation/results errors and recovery timing. Remaining questions include deployment-coupled partition counts and upstream measurement freshness. These scoped audits do **not** certify app-wide absence of fake data.

## Committed changes — not fresh live proof

- Pipelines: zero-output fix `e5f4aae4`; speeding default **45 km/h**, because the simulator caps at **50** (`4b07ce56`, prompt docs `cf0a5a25`); editor Run/results error fix `b93cf853`; explicitly labelled **Saved example** fallback when AI is unavailable (`ec2711b2`, audit C), not AI-generated telemetry or proof of successful execution.
- Live World: road-following smooth motion `749b3c84`, honest stale state `e3f6600b`, and chunk prefetch `589d634a`. Display-route motion is an illustration, not real GPS (audit A).
- Recovery: audit B records missing-worker state and pause-counter fixes; `2ce6fd4f` / merge `110f770a` record Dead hold on any `WORKER_DEAD` and history ordered by event time. Earlier standby label/timing limitations are not waived.
- Tooling: preview refresh/up/down cache process handles for reliable PowerShell 5.1 `ExitCode` (`fb8fda6a`, `df1e9a95`); expo-proofs sets `MSYS_NO_PATHCONV=1` for Git Bash offsets (`31b6b0d2`). Commit subjects are change evidence, not execution proof.

## Pending

- **vimlesh-pc re-test after refresh is pending.** Confirm speeding above 45 km/h, **30 s windows closed on event time**, editor Run/results and errors, and honest AI/saved-example attribution against real services.
- Re-test road motion, stale state, prefetch and recovery Dead/history behavior after refresh. Do not claim partition reassignment or uninterrupted survivor processing from the existing kill proof.
- Full seven-step end-to-end and demo-path Playwright rerun remain unverified. Home playback/loop and classic visual compatibility remain unverified; static video configuration is not playback proof.
