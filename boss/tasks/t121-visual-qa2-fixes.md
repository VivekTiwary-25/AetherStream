# T121: Fix the P1 + P2 items from visual QA round 2  [M3 polish, Claude]

Lane 6. Pull main first. Read docs/ux/visual-qa-2.md (from T115; if it is not on main yet, use the open items in
docs/ux/visual-qa.md and docs/ux/city-visual-qa.md). Fix every P1, then as many P2 as fit, keeping Vivek's design rules (dark
navy/charcoal, restrained luminous accents, glass panels, clay primary buttons, bento layout, motion only for state changes,
readable contrast, no clutter). Mark each item "Fixed in T121" or "Left: <why>" in the QA doc.

Files (edit only these): web/src/app/**, web/src/styles/**, docs/ux/visual-qa-2.md, docs/ux/visual-qa.md.

Acceptance check: ci-web and ci-ui-e2e green on your branch; no test removed or weakened.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql (if a contract looks wrong, say so in your report).
- Do not read or change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only edit the files listed. If you are on vivek-pc: do NOT start Docker containers or kind there (the live demo runs on it). Acceptance runs in GitHub Actions on your task branch.
