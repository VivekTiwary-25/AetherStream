# T176: Read-only pre-expo risk review of today's engine + control-api changes  [light, Opus]

You are on vivek-pc: it runs the live demo. Do NOT build, run tests, start containers or browsers. Read code only.
Today (19 Nov, since 07:00 IST) many changes landed in engine/ and control-api/ (git log --since="2026-11-19 07:00" --
engine control-api simulator). Read those diffs and the code around them and look for risks to the live expo demo: races,
unbounded memory/queues/maps (e.g. the WS 500-message ring, NewRowsTracker overlap de-dup sets, recovery timing maps),
threads that can die silently, blocking calls in hot loops, generation/fencing mistakes, anything that could make
kill-worker -> recovery -> compare fail or slow down over a full day of running. Write docs/reviews/R08-expo-risk-review.md:
a ranked list (HIGH / MEDIUM / LOW) with file:line, the failure scenario, how likely during a 6-hour expo, and the smallest
safe fix. Do not change code.

Files (edit only these): docs/reviews/R08-expo-risk-review.md (new).

Acceptance check: ci-docs green on your branch.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql (if a contract looks wrong, say so in your report).
- Do not read or change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only edit the files listed. If you are on vivek-pc: do NOT start Docker containers or kind there (the live demo runs on it). Acceptance runs in GitHub Actions on your task branch.
- If you are on vivek-pc: it runs the live demo. Do NOT run builds, test suites, Gradle, npm build/test, Playwright or any browser/renderer locally there - read code, edit, and rely on GitHub Actions for every check.
