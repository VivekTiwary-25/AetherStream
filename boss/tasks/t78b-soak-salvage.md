# T78b: Chaos soak test - get ci-soak green (salvage T78)  [tests/infra]

Lane 7/8. Branch origin/task/t261119-0304-6570 has infra/scripts/soak.sh + .github/workflows/ci-soak.yml. Its CI run failed:
"FAIL worker-3 did not recover RUNNING in 30s after generation 3". main now has T81 coordinator hardening (workers rejoining
with the same WORKER_ID get their ASSIGN again; lost ASSIGNs are resent), which likely fixes this. Start from current main,
bring the two files over (git checkout origin/task/t261119-0304-6570 -- infra/scripts/soak.sh .github/workflows/ci-soak.yml),
then:
- Triggers: workflow_dispatch, nightly schedule, and push to task/** ONLY when ci-soak.yml or soak.sh change (paths filter).
  Never on main pushes (the Actions queue is congested).
- Run it on your branch. If recovery still fails, read the coordinator/worker container logs in the uploaded artifacts and
  report the exact cause (do not edit engine code; the boss routes engine fixes). Only relax a wait if the logs show recovery
  does complete, and say how long it took.

Files (edit only these): infra/scripts/soak.sh, .github/workflows/ci-soak.yml.

Acceptance check: ci-soak green on your branch, with the real soak summary (kills, recoveries, comparisons) in your report.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql (if a contract looks wrong, say so in your report).
- Do not read or change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only edit the files listed. If you are on vivek-pc: do NOT start Docker containers or kind there (the live demo runs on it). Acceptance runs in GitHub Actions on your task branch.
