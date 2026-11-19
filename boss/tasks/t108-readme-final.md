# T108: README.md final pass for the expo  [M4 docs]

Lane 9. README.md is the first page the examiner opens on GitHub. Keep the badge row and the CI/CD evidence link at the top
(added by T101). Make sure it has, in this order: one-paragraph pitch (fault-tolerant distributed stream processing, a mini
Apache Flink, with a game-like truck city UI); a screenshot (use one from docs/ux/screenshots/, relative link); feature list
(each feature one line, only features that exist - check web/src and engine); architecture summary + link to
docs/architecture.md; quick start (docker compose up ... - copy exact commands from DEMO.md / infra/scripts, do not invent);
how to run the tests (unit, integration, e2e - exact Gradle / npm / script commands from the workflows); CI/CD summary + link
to docs/report/ci-cd-evidence.md; repo layout (one line per top-level folder that exists, except swarm/ and boss/); team / course
line (CDD expo, 21 Nov 2026); links to DEMO.md, docs/report/project-report.md (may not exist yet - link anyway), docs/ux/.

Files (edit only these): README.md.

Acceptance check: ci-mock green on your branch; every command and link in README.md exists in the repo (say how you checked).

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql (if a contract looks wrong, say so in your report).
- Do not read or change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only edit the files listed. If you are on vivek-pc: do NOT start Docker containers or kind there (the live demo runs on it). Acceptance runs in GitHub Actions on your task branch.
