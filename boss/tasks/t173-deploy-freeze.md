# T173: Demo freeze for CD + monitoring profile in the deployed stack  [expo safety]

Lane 7. Rehearsal (docs/expo/rehearsal-1.md, P1) found: (1) CD redeployed the live app in the middle of the demo, and
(2) the monitoring profile (Prometheus + Grafana) is not running on the deployed stack, so the Grafana step fails.
1. Freeze: in .github/workflows/cd.yml, the deploy job must skip (with a clear notice in the step summary, job still green)
   when the repository variable DEMO_FREEZE is "1" (vars.DEMO_FREEZE) OR the file infra/deploy/FREEZE exists in the pushed
   commit. Build/push of images still runs. Add infra/scripts/demo-freeze.sh on|off|status that sets the variable with
   `gh variable set DEMO_FREEZE --body 1|0` (and prints how to do it in the GitHub UI if gh is missing).
2. Monitoring: the deploy (infra/deploy/** compose invocation used by cd.yml, and infra/deploy/expo-autostart.ps1 + the offline
   override if they start the stack) must also start the `monitoring` profile, so Prometheus and Grafana are up on vivek-pc after
   every deploy. Keep memory in mind: confirm compose already caps their memory or add mem_limit (Prometheus 512m, Grafana 256m)
   in docker-compose.yml for those two services only.
3. Document both in docs/expo/CHEATSHEET.md and the "30 minutes before" step of DEMO.md (freeze on, then demo).
Do not change trigger rules, runner labels or pull-by-SHA.

Files (edit only these): .github/workflows/cd.yml, infra/deploy/**, infra/scripts/demo-freeze.sh (new), docker-compose.yml
(monitoring services' mem_limit only), docker-compose.offline.yml (profile only), docs/expo/CHEATSHEET.md, DEMO.md (that step only).

Acceptance check: cd workflow green on your branch (deploy job does not run on branches; explain how the freeze logic was
tested, e.g. a dry-run step or act-style reasoning) and ci-compose green.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql (if a contract looks wrong, say so in your report).
- Do not read or change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only edit the files listed. If you are on vivek-pc: do NOT start Docker containers or kind there (the live demo runs on it). Acceptance runs in GitHub Actions on your task branch.
- If you are on vivek-pc: it runs the live demo. Do NOT run builds, test suites, Gradle, npm build/test, Playwright or any browser/renderer locally there - read code, edit, and rely on GitHub Actions for every check.
