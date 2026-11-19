# T158: Fix the P1/P2 problems found in the expo rehearsal  [expo safety, hardest UI]

Pull main first. Read docs/expo/rehearsal-1.md (from T155; if not on main yet, stop and say so). Fix every P1, then P2s,
anywhere in web/ (shell, panels, city overlay, styles). For non-web problems (API, engine, infra, DEMO.md wording) do not edit
them - list each in your report with the exact fix so the boss can route it. Keep Vivek's design rules. Mark each item
"Fixed in T158" or "Routed: <why>" in docs/expo/rehearsal-1.md.

Files (edit only these): web/src/**, tests/ui/** (keep every step/assertion), docs/expo/rehearsal-1.md.

Acceptance check: ci-web and ci-ui-e2e green on your branch.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql (if a contract looks wrong, say so in your report).
- Do not read or change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only edit the files listed. If you are on vivek-pc: do NOT start Docker containers or kind there (the live demo runs on it). Acceptance runs in GitHub Actions on your task branch.
- If you are on vivek-pc: it runs the live demo. Do NOT run builds, test suites, Gradle, npm build/test, Playwright or any browser/renderer locally there - read code, edit, and rely on GitHub Actions for every check.
