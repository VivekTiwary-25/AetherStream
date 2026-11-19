# T100: demo-ready check + self-heal script for the deployed stack  [M4 reliability]

Lane 7. After a deploy the live stack must show live panels without a human. Write infra/scripts/demo-ready.sh
(bash, set -euo pipefail, shellcheck clean) that takes the API base URL (default http://localhost:18080) and:
1. waits for health (contract 04 health endpoint) and prints /api/version;
2. finds the active execution; if there is none, or it wrote no window results in the last 120 s, or it is FAILED, starts the
   demo pipeline the same way infra/scripts/demo-e2e.sh does (reuse its API calls; same pipeline JSON);
3. waits until window results arrive and at least 1 checkpoint is COMPLETED (timeout 180 s);
4. runs POST /api/compare and prints "matched / total" (fails if total == 0 or mismatches > 0);
5. prints one final line: "DEMO READY <commit> execution=<id> checkpoint=#N compare=M/M" or "DEMO NOT READY: <reason>", exit 1.
Then add it as the LAST step of .github/workflows/ci-e2e.yml (after the existing demo-e2e step, same API URL) so CI proves it.

Files (edit only these): infra/scripts/demo-ready.sh (new), .github/workflows/ci-e2e.yml (append one step at the end of the
e2e job; do not touch the E2E_EXTENDED line or other steps).

Acceptance check: ci-e2e green on your branch, with the "DEMO READY ..." line visible in the log.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql (if a contract looks wrong, say so in your report).
- Do not read or change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only edit the files listed. If you are on vivek-pc: do NOT start Docker containers or kind there (the live demo runs on it). Acceptance runs in GitHub Actions on your task branch.
