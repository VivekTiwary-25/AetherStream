# T144: Speaker notes for the 12 slides  [docs]

Lane 9. For each slide in docs/report/presentation-outline.md, write speaker notes (what to say in 45-60 s, plain spoken
English, short sentences), the one number or fact to stress (only facts that exist in the repo: docs/testing.md,
docs/report/ci-cd-evidence.md, docs/report/benchmarks.md if present), and a likely examiner question with a 2-sentence answer.
Split the speaking between 4 presenters (Presenter A-D, no real names) with clean hand-offs.

Files (edit only these): docs/report/speaker-notes.md (new).

Acceptance check: ci-mock green on your branch.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql (if a contract looks wrong, say so in your report).
- Do not read or change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only edit the files listed. If you are on vivek-pc: do NOT start Docker containers or kind there (the live demo runs on it). Acceptance runs in GitHub Actions on your task branch.
