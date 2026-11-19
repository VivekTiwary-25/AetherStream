# T136: Docs accuracy pass - README, DEMO.md, docs/testing.md, docs/runbook.md agree with each other and the code  [docs]

Lane 9. Several workers wrote docs in parallel this morning. Read README.md, DEMO.md, docs/testing.md, docs/runbook.md,
docs/report/ci-cd-evidence.md, docs/report/syllabus-mapping.md and docs/architecture.md. Fix (in those files only) every
inconsistency: different ports, commands, workflow names, test counts, UI labels (check web/src for the exact button text),
feature claims that the code does not support, broken relative links. Keep each document's structure; small edits only. List
every fix in your report (file: old -> new, and how you verified it).

Files (edit only these): README.md, DEMO.md, docs/testing.md, docs/runbook.md, docs/report/ci-cd-evidence.md,
docs/report/syllabus-mapping.md, docs/architecture.md.

Acceptance check: ci-mock green on your branch; every relative link resolves (say how you checked).

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql (if a contract looks wrong, say so in your report).
- Do not read or change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only edit the files listed. If you are on vivek-pc: do NOT start Docker containers or kind there (the live demo runs on it). Acceptance runs in GitHub Actions on your task branch.
