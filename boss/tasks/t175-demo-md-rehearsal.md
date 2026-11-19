# T175: DEMO.md fixes from rehearsal 1 (text only)  [expo safety]

Lane 9. Apply the DEMO.md wording fixes listed in docs/expo/rehearsal-1.md (the P2/P3 text items and any "DEMO.md says X, app
shows Y" rows): exact labels, timings measured in the rehearsal, the bounded queue wording (compose runs workers with
WORKER_QUEUE_CAPACITY=32 so backpressure shows quickly; say "bounded queue (32 in the demo config)"), and GitHub Actions log
links that need a login (point to the run summary page or the screenshots in docs/expo/rehearsal-1/ instead). Do not touch the
"30 minutes before" step (another task changes it) or the alert step (another task changes it).

Files (edit only these): DEMO.md (except those two steps), docs/expo/CHEATSHEET.md (only if a label there is wrong).

Acceptance check: ci-docs green on your branch.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql (if a contract looks wrong, say so in your report).
- Do not read or change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only edit the files listed. If you are on vivek-pc: do NOT start Docker containers or kind there (the live demo runs on it). Acceptance runs in GitHub Actions on your task branch.
- If you are on vivek-pc: it runs the live demo. Do NOT run builds, test suites, Gradle, npm build/test, Playwright or any browser/renderer locally there - read code, edit, and rely on GitHub Actions for every check.
