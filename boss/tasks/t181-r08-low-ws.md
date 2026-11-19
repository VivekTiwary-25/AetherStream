# T181: Fix R08 LOW #6 and #7 (stale recoveryPending, re-broadcast of old window rows)  [control-api]

Read docs/reviews/R08-expo-risk-review.md items 6 and 7 and apply the smallest safe fix proposed: #6 a stale recoveryPending
flag must not mislabel a later rebalance (clear it on completion / generation change); #7 recovery replays must not
re-broadcast window rows the browser already has (de-dup by key + window, or only push rows newer than the last sent cursor).
One focused unit test each. T178 just changed the same package - pull main first.

Files (edit only these): control-api/src/main/java/io/aetherstream/api/ws/**, control-api/src/test/java/**/ws/** (new test classes only).

Acceptance check: ci-java and ci-e2e green on your branch.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql (if a contract looks wrong, say so in your report).
- Do not read or change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only edit the files listed. If you are on vivek-pc: do NOT start Docker containers or kind there (the live demo runs on it). Acceptance runs in GitHub Actions on your task branch.
- If you are on vivek-pc: it runs the live demo. Do NOT run builds, test suites, Gradle, npm build/test, Playwright or any browser/renderer locally there - read code, edit, and rely on GitHub Actions for every check.
