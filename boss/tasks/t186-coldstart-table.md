# T186: Fill docs/expo/cold-start-times.md with the CI-measured cold-start numbers  [docs]

The ci-coldstart workflow writes a cold-start timing table into each run's step summary, but docs/expo/cold-start-times.md is
still empty. Read the last 5 successful ci-coldstart runs on main (public API: GET
https://api.github.com/repos/VivekTiwary-25/AetherStream/actions/workflows/ci-coldstart.yml/runs?branch=main&status=success&per_page=5,
then each run's logs are not public without login - so instead use the job step timings from
.../actions/runs/<id>/jobs: started_at/completed_at per step) and add one row per run (date, phase timings you can derive,
total) plus a median row. Keep the existing header and the row reserved for the vivek-pc laptop measurement.

Files (edit only these): docs/expo/cold-start-times.md.

Acceptance check: ci-docs green on your branch.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql (if a contract looks wrong, say so in your report).
- Do not read or change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only edit the files listed. If you are on vivek-pc: do NOT start Docker containers or kind there (the live demo runs on it). Acceptance runs in GitHub Actions on your task branch.
- If you are on vivek-pc: it runs the live demo. Do NOT run builds, test suites, Gradle, npm build/test, Playwright or any browser/renderer locally there - read code, edit, and rely on GitHub Actions for every check.
