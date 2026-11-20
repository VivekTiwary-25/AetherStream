# Expo morning runbook — 21 Nov (check)

1. **If merging `ui-glorious`:** Wait for Vivek to review the expo-night final report and approve; do this outside the presentation slot. Report/review status: (check).
2. Check no deployment is running; save previous main SHA and deployed SHA from `http://localhost:8088/api/version` and confirm they match before using main for rollback.
3. Check freeze with `bash infra/scripts/demo-freeze.sh status`; before deployment run `bash infra/scripts/demo-freeze.sh off` and ensure `infra/deploy/FREEZE` is absent from the workflow commit. The script requires authenticated `gh`; `off` sets repository variable `DEMO_FREEZE=0` but does not remove the file.
4. Fetch `main` and `ui-glorious`, review/create the PR and checks, then choose **Create a merge commit** on GitHub; do not merge or push during the demo. Branch/PR availability and merge settings: (check).
5. Watch the **cd** run in GitHub Actions: eligible pushes to main publish SHA-tagged web, control-api, engine and simulator images (docs/Markdown-only pushes are ignored); the self-hosted runner pulls images and runs Compose up with monitoring enabled. Seven app services use those images; the stack also includes three infrastructure services, two monitoring services and two one-shot init services. Compose recreates changed services, waits for health, then CD smoke-checks health/version and runs demo-ready, which may start a demo execution and verifies results, a completed checkpoint and comparison. Freeze skips deployment and live checks, not publishing.
6. Expect a service pause. Restart duration is unverified (check): allow a few minutes, not a guarantee; Compose health wait is 180 s, smoke check allows 120 s, demo-ready step times out at 6 min, plus build/pull time.
7. After live checks pass, verify `http://localhost:8088` has live data and no MOCK DATA badge (check); check `http://localhost:8088/api/health` is UP, `/api/version` is the merge SHA, and `http://localhost:18080/api/ready` reports UP. Run `bash infra/scripts/demo-freeze.sh on` before presenting; this freezes future deployments, not one already running.
8. Either way, 30 minutes before the slot, open a PowerShell at the repo root for the AI bridge:
9. `$env:CLAUDE_CONFIG_DIR = "$env:USERPROFILE\.swarm\profiles\vachana-claude\claude"`
10. `powershell -ExecutionPolicy Bypass -File scripts/ai-bridge/start.ps1`
11. The launcher exits after starting a hidden background Python process; logs and PID are in `scripts/ai-bridge/.run/`. Verify `http://127.0.0.1:18090/health` (check) or `http://localhost:8088/api/glorious/ai/health` after deploy.
12. Open a second expo terminal at the repo root. **PowerShell:** `$env:PATH = "$PWD\scripts\expo;$env:PATH"`
13. **cmd:** `set PATH=%CD%\scripts\expo;%PATH%`
14. Run `.\scripts\expo\preflight.cmd` from either Windows terminal at the repo root; it uses GET requests only.
15. **FAIL** means a core app/API/current-execution, worker, simulator, recovery, or Pipelines check failed; fix before the slot.
16. **WARN** means simulator truck-count mismatch (six trucks expected) or AI bridge unavailable/unhealthy; check the warning, and start/check the bridge if needed.
17. Run `worker status`; check all three worker IDs, alive/state and partitions. Do not kill a worker during setup.
18. Open `http://localhost:8088` in one browser tab and `http://localhost:8088/?ui=classic` in another; confirm both load (check live).
19. **Demo — 1 Home:** open `http://localhost:8088/`, point out navigation/status, click **Live World** (check live).
20. **2 Live map:** click **Live World** in the left navigation; show the Mysuru map and live truck markers (check live).
21. **3 Truck details:** hover a marker (or focus/select it with the keyboard); show its details card (check live).
22. **4 Speeds:** click **Analytics** → **Truck Speeds** → a truck row; use **Back** to return (check live).
23. **5 Failed records:** run `events late 10` once; records appear only if the engine classifies them late. Click **Analytics** → **Failed Records** → **Late** (or **All**) → a record (check live).
24. **6 Pipelines:** click **Pipelines**; enter `Alert me when any truck goes over 45 km/h` (simulator maximum: 50 km/h) in **Ask AI**, click **Generate**, review steps → **Open in editor** → review → **Run** (check live).
25. **7 Recovery:** click **Settings** → **Logs & Recovery** (check live), then run `worker kill 2` once in the expo terminal; **Dead** is shown for about 3 seconds, recovery from a checkpoint takes about 4 seconds, the worker keeps its own partitions (no reassignment), and the failure stays in Recovery History. With six trucks, keys are hashed to partitions, leaving P1 idle while P4 has two.
26. **Fallback — UI:** if only the new UI fails, try `http://localhost:8088/?ui=classic` (check live); this still requires the web app and API to be available.
27. **Fallback — Pipelines mode:** `web/src/glorious/pipelines/mode.ts` currently sets `'full'`. Claimed effects: `'editor'` shows only Open Editor; `'hidden'` removes Pipelines and redirects its routes to Home; `'full'` shows Ask AI and editor. This is a source setting, not a runtime switch.
28. **Fallback — AI:** run the displayed saved example only when labelled **AI is offline - this is a saved example, not generated by AI**; say it is saved, not AI-generated, and review before running.
29. **Fallback — worker recovery:** never repeat `worker kill 2`; wait for the live feed and describe only the state shown. If the command errors, check worker status/recovery first; use classic UI’s **Kill worker** (check live) once only if the first request did not take effect, then watch Logs & Recovery.
30. **Rollback — images:** GitHub Actions → **cd** → **Run workflow**, branch **main**, input **sha** = saved previous deployed main SHA; ensure images remain in GHCR and freeze is off, then repeat health/version, UI and worker checks.
31. Image rollback does not restore main source or Compose config; verify the current `SIM_INITIAL_TRUCKS: "6"` setting and live truck count. Do not expect an earlier truck-count setting to return.
