# T99: UX documentation for the new features (CDD syllabus: UX / design thinking)  [M4 docs]

Lane 9. docs/ux/ has personas, journeys, usability decisions and design-system notes. New features are merged: ops panel
(savepoint / restore / rescale, web/src/app/ops), scenario panel (web/src/app/scenarios), presenter tour + intro splash
(web/src/app/tour), editor example picker (web/src/app/editor), checkpoint timeline (web/src/app/timeline). For each, add the
user journey (persona, goal, steps, pain points, how the design answers them), usability decisions (why confirm-before-restore,
why verbatim 409 errors, empty / loading / error states) and design-system notes (glass vs clay usage, tokens, motion rules,
accessibility: focus order, aria, contrast). Add a short design-thinking section (empathise / define / ideate / prototype /
test) tied to real artefacts in the repo (docs/ux/visual-qa.md, screenshots). Describe only what the code really does.

Files (edit only these): docs/ux/** except docs/ux/visual-qa.md and docs/ux/screenshots/**.

Acceptance check: ci-mock green on your branch; every component you describe exists (cite its file path).

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql (if a contract looks wrong, say so in your report).
- Do not read or change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only edit the files listed. If you are on vivek-pc: do NOT start Docker containers or kind there (the live demo runs on it). Acceptance runs in GitHub Actions on your task branch.
