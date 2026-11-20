# Merge ui-glorious and run on vivek-pc

## What changes

- Merge only after Vivek reviews the expo-night final report and chooses to proceed.
- The branch changes web, control-api, coordinator/engine, and simulator config.
- CD builds and publishes four SHA-tagged images: web, control-api, engine, simulator.
  The engine image serves coordinator and worker-1, worker-2, worker-3.
  These seven app containers are recreated for the new SHA; expect a service pause.
- Simulator initial trucks change from 8 to 6 (`SIM_INITIAL_TRUCKS=6`).
- Web nginx adds `/api/glorious/ai/`, forwarding to the host bridge on
  `host.docker.internal:18090` and stripping that prefix (`health` becomes `/health`).
  The bridge is a separate host process, not a CD-started container.

## Merge and deploy

1. Outside the presentation slot, check no deployment is running. Save the full
   previous main SHA and the currently deployed SHA from
   <http://localhost:8088/api/version>; check they match before using main as rollback.
2. Check demo freeze: `bash infra/scripts/demo-freeze.sh status`.
   When ready to deploy, run `bash infra/scripts/demo-freeze.sh off` and check
   `infra/deploy/FREEZE` is absent from the merge commit. Either freeze gate skips
   deployment and live checks, even when Actions is green; images still publish.
3. From a checkout with GitHub CLI access, create the PR:
   ```sh
   git fetch origin main ui-glorious
   git rev-parse origin/main
   gh pr create --base main --head ui-glorious --title "Merge ui-glorious" --body "Deploy after expo-night approval."
   ```
   Record the printed main SHA. If a PR already exists, use it instead.
   Review its diff and checks, then choose **Create a merge commit** on GitHub.
   Do not merge or push during the demo.
4. Watch the `cd` run at <https://github.com/VivekTiwary-25/AetherStream/actions>.
   A main code push publishes to GHCR; the self-hosted `aether-deploy` runner pulls
   that SHA and runs Compose with the deploy override and monitoring profile.
   It waits for health, smoke-checks health/version, then runs demo-ready.
5. Pause duration: **check on vivek-pc**; no measured restart time is recorded here.
   Allow a few minutes as a planning allowance, not a guarantee. Compose's health
   wait is 180 seconds; the later smoke check allows 120 seconds and demo-ready
   has a six-minute job-step timeout. Build/pull time is additional.
6. After the live checks pass, open <http://localhost:8088> and check the new UI
   loads with live data and no MOCK DATA badge. Open
   <http://localhost:8088/?ui=classic> and check the classic UI still works.
   Check <http://localhost:8088/api/health> reports UP and `/api/version` reports
   the merge SHA; direct API readiness: <http://localhost:18080/api/ready>.

   In PowerShell at the repo root, run:
   ```powershell
   $env:PATH = "$PWD\scripts\expo;$env:PATH"
   worker status
   ```
   Check all three worker IDs, alive/state, and partitions. Check monitoring at
   <http://localhost:13000> and <http://localhost:19090> if needed for the demo.

## Automated checks after merge

| Check | Loads web UI? | Change |
|---|---|---|
| `cd`: `smoke-deploy.sh`, `demo-ready.sh`, `expo-check.sh`, `expo-proofs.sh` | No; API/readiness and stack checks only | None |
| `ci-e2e`: `demo-e2e.sh`, `expo-check.sh`, `expo-proofs.sh`, `demo-ready.sh`; `ci-coldstart` | No; API checks only | None |
| `ci-compose`: `smoke.sh` | Yes; checks only that `/` returns HTML, no classic-only content | None |
| `ci-terraform` | Yes; checks only that `/` returns a nonempty HTML document | None |
| `ci-k8s` | Yes; checks only that `/` responds | None |
| `ci-ui-e2e`: classic Playwright specs | Yes; asserts classic dashboard/editor elements | `playwright.config.ts` selects classic via `aether.ui=classic` and runs only `demo.spec.ts` and `features.spec.ts`; new-UI specs remain on their separate Glorious config |
| `ci-offline` | Yes; asserts the Glorious live map, panels, canvas, and live frames | None; these are new-UI checks |
| `ci-web` | No browser/web-root load | None |

## Start Ask AI after deploy (PowerShell, vivek-pc repo root)

Requires Python 3.10+ and Claude Code installed and logged in to this profile.
```powershell
$env:CLAUDE_CONFIG_DIR = "$env:USERPROFILE\.swarm\profiles\vachana-claude\claude"
powershell -ExecutionPolicy Bypass -File scripts/ai-bridge/start.ps1
curl.exe -fsS http://localhost:8088/api/glorious/ai/health
```
Check the config directory exists and health succeeds through nginx, then try Ask AI.
Default bind is `127.0.0.1:18090`; keep it local on Docker Desktop.
Logs/PID: `scripts/ai-bridge/.run/`. Health alone does not prove generation works.

## Rollback

For a UI-only fallback, use <http://localhost:8088/?ui=classic> (not an image rollback).
For image rollback, open Actions → **cd** → **Run workflow**, select branch **main**,
and enter the saved previous deployed main SHA in input **sha**. Check its images
still exist in GHCR and freeze is off. Dispatch pulls existing images; it does not
rebuild them. Repeat the health/version, UI, and worker checks above for that SHA.
This rolls back images, not main's source or Compose config: check the six-truck
setting still in current Compose; do not expect this dispatch to restore eight.
