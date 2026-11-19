# T146: Accessibility checks in the web tests (axe)  [tests]

Lane 6, tests only. Add vitest-axe (or jest-axe compatible with vitest) as a devDependency and a new test file
web/src/app/__tests__/a11y.test.tsx that renders the App shell (same mocks as appShell.test.tsx: mock WebSocket, fake timers
cleaned up) and each panel component, and asserts no serious/critical axe violations. Disable only rules that cannot work in
jsdom (color-contrast) and say so. If a real violation is found, use it.skip with "BUG:" and describe it.

Files (edit only these): web/src/app/__tests__/a11y.test.tsx (new), web/package.json (devDependencies only), web/package-lock.json.

Acceptance check: ci-web green on your branch.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql (if a contract looks wrong, say so in your report).
- Do not read or change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only edit the files listed. If you are on vivek-pc: do NOT start Docker containers or kind there (the live demo runs on it). Acceptance runs in GitHub Actions on your task branch.
