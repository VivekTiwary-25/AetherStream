# T101: CI/CD evidence page + README badges  [M4 docs]

Lane 9. The examiner wants to SEE GitHub Actions working. Write docs/report/ci-cd-evidence.md: one row per workflow in
.github/workflows/ (name, trigger, what it proves, typical duration, link to its runs page
https://github.com/VivekTiwary-25/AetherStream/actions/workflows/<file>), plus the CD path (build -> GHCR by commit SHA ->
self-hosted runner on vivek-pc -> compose deploy -> smoke test: health + /api/version == SHA). Read each workflow file to describe
it accurately. Read latest run data from the public API (curl -s https://api.github.com/repos/VivekTiwary-25/AetherStream/actions/runs?branch=main&per_page=50,
no token needed) and include a dated snapshot table (workflow, last conclusion, run link). In README.md add a status badge for
each workflow that runs on main (https://github.com/VivekTiwary-25/AetherStream/actions/workflows/<file>/badge.svg?branch=main)
in one compact row near the top, and a link to the evidence page. Do not change other README sections.

Files (edit only these): docs/report/ci-cd-evidence.md (new), README.md (badge row + one link only).

Acceptance check: ci-mock green on your branch; every workflow file listed exists (say how you checked).

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql (if a contract looks wrong, say so in your report).
- Do not read or change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only edit the files listed. If you are on vivek-pc: do NOT start Docker containers or kind there (the live demo runs on it). Acceptance runs in GitHub Actions on your task branch.
