# T159: Cold-start timing: from power-on to demo-ready, measured and written into DEMO.md  [expo safety]

Lanes 7/9. Write infra/scripts/cold-start-timer.sh (bash, Git-Bash compatible for Windows; also works on Linux): records
timestamps for (a) Docker engine reachable (`docker info` succeeds; poll from script start), (b) `docker compose up -d` returned,
(c) all services healthy, (d) /api/health ok, (e) demo-ready.sh prints DEMO READY; prints a table of each phase and the total,
and appends it as a dated row to docs/expo/cold-start-times.md. Options: --compose-file (default docker-compose.yml; also works
with docker-compose.offline.yml if present), --api (default http://localhost:18080), --from-cold (runs `docker compose down`
first, keeps volumes unless --clean).
CI: add a job to .github/workflows/ci-compose.yml (or a new ci-coldstart.yml with the same triggers) that runs it --from-cold on
the runner with the CI-built images and puts the table in the step summary. Then add a short "Cold start" section to DEMO.md:
CI-measured numbers (link the run) and a row "vivek-pc laptop: <to be measured by the boss with
cold-start-timer.sh --from-cold>" plus the instruction to start Docker Desktop at least N minutes before the demo (N from the
measurement + margin). Do not run Docker on vivek-pc.

Files (edit only these): infra/scripts/cold-start-timer.sh (new), docs/expo/cold-start-times.md (new),
.github/workflows/ci-coldstart.yml (new) or ci-compose.yml (one job), DEMO.md (new "Cold start" section only).

Acceptance check: the timing job green on your branch with the table in its summary.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql (if a contract looks wrong, say so in your report).
- Do not read or change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only edit the files listed. If you are on vivek-pc: do NOT start Docker containers or kind there (the live demo runs on it). Acceptance runs in GitHub Actions on your task branch.
