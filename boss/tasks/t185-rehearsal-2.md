# T185: Rehearsal 2 - verify the rehearsal-1 P1 fixes on the live app  [expo safety, Claude]

Read docs/expo/rehearsal-1.md (its P1/P2 list and the "Fixed in T158"/"Routed" marks). The fixes merged since: T158 (Recovered
banner + web P2s), T174 (truck-007 Breakdown -> stuck alert), T173 (deploy freeze + monitoring deployed), T175 (DEMO.md text),
T177 (compare memory), T178 (worker restart shown as recovery). Re-run DEMO.md against the live app (public link in
boss/STATE.md "Public link"; check /api/version first and note the commit) with Playwright at 1440x900, screenshots of each P1
item before/after. Do not change any app state beyond what DEMO.md does; do not toggle the deploy freeze.
Write docs/expo/rehearsal-2.md: for every rehearsal-1 P1/P2 item: FIXED / NOT FIXED / PARTLY with a screenshot link, plus any NEW
problem (ranked P1/P2/P3 with location and a concrete fix), and the total demo time. No app code changes.

Files (edit only these): docs/expo/rehearsal-2.md (new), docs/expo/rehearsal-2/** (new).

Acceptance check: ci-docs green on your branch.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql (if a contract looks wrong, say so in your report).
- Do not read or change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only edit the files listed. If you are on vivek-pc: do NOT start Docker containers or kind there (the live demo runs on it). Acceptance runs in GitHub Actions on your task branch.
- If you are on vivek-pc: it runs the live demo. Do NOT run builds, test suites, Gradle, npm build/test, Playwright or any browser/renderer locally there - read code, edit, and rely on GitHub Actions for every check.
