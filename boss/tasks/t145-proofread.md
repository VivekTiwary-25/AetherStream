# T145: Proofread the report docs (typos, grammar, broken Markdown only)  [docs]

Lane 9. Proofread docs/report/*.md and docs/adr/*.md: fix spelling, grammar, broken Markdown tables/lists, inconsistent
capitalisation of product names (AetherStream, Kafka, MinIO, PostgreSQL, GitHub Actions, Docker Compose, Kubernetes, Grafana,
Prometheus). Do NOT change facts, numbers, commands or structure. Keep the diff small.

Files (edit only these): docs/report/*.md, docs/adr/*.md.

Acceptance check: ci-mock green on your branch.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql (if a contract looks wrong, say so in your report).
- Do not read or change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only edit the files listed. If you are on vivek-pc: do NOT start Docker containers or kind there (the live demo runs on it). Acceptance runs in GitHub Actions on your task branch.
