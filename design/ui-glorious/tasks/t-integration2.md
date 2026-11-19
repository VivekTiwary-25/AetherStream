# Integration check of ui-glorious (clean checkout) — fix only integration breakage

Files: any file under web/src/glorious/** ONLY to fix a cross-track build/type/lint/test break (smallest possible fix, explain each in the report). Do NOT read or describe swarm/.

Node: if `node` is not on PATH, prepend `%USERPROFILE%\tools\node` (PowerShell `$env:PATH = "$env:USERPROFILE\tools\node;$env:PATH"`).
1. In web/: `npm ci`, `npm run lint`, `npm test -- --maxWorkers=2`, `npm run build`, `npm run check:no-mock`. Record each result.
2. Fix only breakage between the merged tracks (shell, analytics, pipelines, recovery menu). If a failure is inside one track's logic and not trivial, do NOT rewrite it: report file:line and the error.
3. Run `npx vite preview --port 4173` in the background for at most 2 minutes, curl `/`, `/live`, `/pipelines`, `/pipelines/editor`, `/?ui=classic` (expect 200), then stop it.
4. Check that `git status` shows nothing ignored that should be tracked under web/src/glorious (run `git status --ignored web/src/glorious`).
Report: each command's result, what you fixed, open problems.

5. Also confirm the merged map (web/src/glorious/map) and recovery (web/src/glorious/recovery) are included: list their files from `git ls-files`.
