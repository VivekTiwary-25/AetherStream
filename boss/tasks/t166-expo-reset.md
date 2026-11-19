# T166: expo-reset.sh - one command back to a clean, fresh demo  [expo safety]

Lanes 7/3. At the expo the demo runs again for every visitor group. Write infra/scripts/expo-reset.sh (bash, Git-Bash on
Windows compatible) that in < 60 s: stops the active execution(s) via the API (contract 04), clears demo results (window
results, alerts, dead letters, compare cache) through an API if one exists - otherwise via `docker compose exec postgres psql`
TRUNCATE of exactly the result tables in infra/db/schema.sql (never pipelines/executions history unless --hard), resets the
simulator to its seed and default trucks (simulator API in contract 01: scenario reset, truck removal), removes injected chaos
(slow sink off), makes sure all workers are running (docker compose start for any stopped worker), then runs demo-ready.sh so a
new demo pipeline is live. Print each step and the final DEMO READY line. Flags: --api URL, --hard, --dry-run. Add it as a step
in .github/workflows/ci-e2e.yml after the existing steps (reset, then demo-ready again must pass). Add one line to
docs/expo/CHEATSHEET.md under the commands.

Files (edit only these): infra/scripts/expo-reset.sh (new), .github/workflows/ci-e2e.yml (one appended step),
docs/expo/CHEATSHEET.md (one line).

Acceptance check: ci-e2e green on your branch with the reset step and a second DEMO READY in the log.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql (if a contract looks wrong, say so in your report).
- Do not read or change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only edit the files listed. If you are on vivek-pc: do NOT start Docker containers or kind there (the live demo runs on it). Acceptance runs in GitHub Actions on your task branch.
