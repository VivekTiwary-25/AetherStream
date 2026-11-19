# T172: Offline expo mode must pass every time (3 runs in a row)  [expo safety]

Lane 7. ci-offline (.github/workflows/ci-offline.yml, merged from T156) proves the demo runs with outbound network blocked.
The expo fallback depends on it, so it must not flake. Push your branch (any small harmless change to a comment in
ci-offline.yml triggers it) and run it 3 times (re-run the workflow). If any run fails, find the cause in the logs and fix it in
the offline scripts/override (timeouts too short, health ordering, image tar restore, Grafana offline settings, browser check
false positives) without weakening the "no external requests" and "outbound blocked" checks. Also run the docs/EXPO_OFFLINE.md
commands mentally against the scripts and fix any mismatch in the doc.

Files (edit only these): .github/workflows/ci-offline.yml, docker-compose.offline.yml, infra/scripts/expo-prepull.sh,
infra/scripts/expo-offline-up.sh, docs/EXPO_OFFLINE.md.

Acceptance check: 3 green ci-offline runs on your branch (paste the 3 run URLs).

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql (if a contract looks wrong, say so in your report).
- Do not read or change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only edit the files listed. If you are on vivek-pc: do NOT start Docker containers or kind there (the live demo runs on it). Acceptance runs in GitHub Actions on your task branch.
