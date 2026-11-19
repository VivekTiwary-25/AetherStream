# T75b: Coordinator-restart / rescale / savepoint integration tests (salvage T75)  [tests]

Lane 8. Branch origin/task/t261119-0258-bc06 has good work: EngineCluster.restartCoordinator(), CoordinatorRestartIT and
RescaleIT (passed), SavepointIT (not finished: ci-integration hit its 30-minute limit). Start from current main and bring that
work over (git checkout origin/task/t261119-0258-bc06 -- <files>), then:
1. Make SavepointIT pass (compatible savepoint continuation; incompatible window-size restore rejected). main now has the T86
   fix (restored executions complete their own checkpoints) and T81 coordinator hardening.
2. Keep ci-integration well under its timeout: split the integration tests into 2 parallel jobs (a matrix over test-class
   groups via --tests filters) in .github/workflows/ci-integration.yml, each job <= 20 minutes. Keep all existing ITs.
3. If EngineMatchesReferenceIT / IncompleteCheckpointIT flake (seen on that branch: worker 0 declared dead before initial
   assignment; producer exceeded its 60 s wait), report it with the log line; fix only test-harness timing, never weaken an
   assertion.

Files (edit only these): tests/src/test/java/**, .github/workflows/ci-integration.yml.

Acceptance check: ci-integration green on your branch (all jobs), including CoordinatorRestartIT, RescaleIT, SavepointIT.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql (if a contract looks wrong, say so in your report).
- Do not read or change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only edit the files listed. If you are on vivek-pc: do NOT start Docker containers or kind there (the live demo runs on it). Acceptance runs in GitHub Actions on your task branch.
