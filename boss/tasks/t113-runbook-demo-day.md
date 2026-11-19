# T113: docs/runbook.md - demo-day runbook (what to do when something breaks on stage)  [M4 docs]

Lane 9. Update docs/runbook.md for the expo on 21 Nov. Sections, each a short symptom -> check -> fix list with exact commands
that exist in the repo: (1) before the demo: check the deployed version (/api/version vs latest main commit), run
infra/scripts/demo-ready.sh, check containers (docker ps), public link (quick tunnel; the URL is in the tunnel log, it changes
on restart); (2) panels empty or only LATE events -> demo-ready.sh restarts the pipeline; (3) a worker stuck after a kill ->
docker compose start <worker>; (4) deploy failed in GitHub Actions -> how to re-run, how to deploy the last good SHA by hand;
(5) PC low on memory -> what to stop; (6) no internet -> run everything locally on localhost:8088 / :18080; (7) GitHub
Actions slow -> show a previous green run (docs/report/ci-cd-evidence.md). Read infra/scripts, infra/deploy, docker-compose.yml and
.github/workflows/cd.yml to get the commands right. Do not include secrets or personal paths other than those already in the repo.

Files (edit only these): docs/runbook.md.

Acceptance check: ci-mock green on your branch; every command exists (say how you checked).

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql (if a contract looks wrong, say so in your report).
- Do not read or change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only edit the files listed. If you are on vivek-pc: do NOT start Docker containers or kind there (the live demo runs on it). Acceptance runs in GitHub Actions on your task branch.
