# T137: Web tests for the ops + scenario panels  [tests]

Lane 6, tests only. Extend vitest tests for web/src/app/ops (OpsPanel: create savepoint, list, confirm-before-restore, rescale
input validation, verbatim 409) and web/src/app/scenarios (ScenarioPanel: list, run, running/disabled state, error state).
Put new files in web/src/app/ops/__tests__/ and web/src/app/scenarios/__tests__/ with new names (do not edit existing test
files). Mock fetch; fake timers cleaned up so vitest exits. No non-test changes (it.skip + "BUG:" for bugs).

Files (edit only these): new files in web/src/app/ops/__tests__/ and web/src/app/scenarios/__tests__/.

Acceptance check: ci-web green on your branch.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql (if a contract looks wrong, say so in your report).
- Do not read or change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only edit the files listed. If you are on vivek-pc: do NOT start Docker containers or kind there (the live demo runs on it). Acceptance runs in GitHub Actions on your task branch.
