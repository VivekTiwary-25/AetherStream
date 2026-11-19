# Write the merge + run guide for ui-glorious on vivek-pc (docs only, small)

Files: docs/expo/MERGE-UI-GLORIOUS.md (new). Do NOT read or describe swarm/. Keep it small: this PC has little Codex usage left.

Vivek will decide after the expo-night final report whether to merge branch ui-glorious into main (main auto-deploys to vivek-pc via .github/workflows/cd.yml). Read cd.yml, docker-compose.yml, infra/docker/nginx.conf, scripts/ai-bridge/README.md, scripts/expo/README.md, docs/expo/CHEATSHEET.md, and `git diff --stat origin/main...origin/ui-glorious` (changed services: web, control-api, coordinator/engine, simulator config). Write docs/expo/MERGE-UI-GLORIOUS.md (max 80 lines, plain language):
1. What the merge changes (services that will rebuild/restart, the simulator going from 8 to 6 trucks, new nginx route for Ask AI).
2. Exact merge steps (a PR or `git merge --no-ff`), what CD will do, how long a restart pause to expect, and how to verify after deploy (URLs, `worker status`, new UI loads, `?ui=classic` still works).
3. After deploy on vivek-pc: start the AI bridge with scripts/ai-bridge/start.ps1 (vachana-claude config dir) and check /api/glorious/ai/health through http://localhost:8088.
4. Rollback: how to redeploy the previous main SHA (cd.yml workflow_dispatch input `sha`) and the fallback `?ui=classic`.
No claims beyond what the files show; mark anything unsure as "check".
