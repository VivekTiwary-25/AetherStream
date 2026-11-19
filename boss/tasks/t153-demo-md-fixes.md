# T153: DEMO.md text fixes from visual QA round 2  [docs]

Lane 9. docs/ux/visual-qa-2.md has a "DEMO.md mismatches" section (items 1-9). Apply the text fixes to DEMO.md only: exact tab
names (Rejected (N) / Late (N)), the alert timing note (Run and Restore start a new execution, so the stuck-truck alert needs
~90 s again - say so before clicking), the "do not wait for Backpressured" fallback, and any other label that differs from the
app. Do not describe P1 bugs as features; where a P1 is still open, add a short fallback line instead. Small edits only.

Files (edit only these): DEMO.md.

Acceptance check: ci-mock green on your branch.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql (if a contract looks wrong, say so in your report).
- Do not read or change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only edit the files listed. If you are on vivek-pc: do NOT start Docker containers or kind there (the live demo runs on it). Acceptance runs in GitHub Actions on your task branch.
