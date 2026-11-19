# T156: Offline expo mode - the whole demo runs on vivek-pc with NO internet  [expo safety, hard]

Lanes 7/9. The expo hall may have no internet. Make sure, and prove in CI, that the full demo runs offline on one machine:
1. Audit: no runtime fetches to the internet anywhere in the demo path - web (fonts, CDN scripts/styles, map tiles, analytics;
   grep web/index.html, web/src, web/public, CSS @import/url()), nginx config, control-api/simulator/engine (any external URL),
   Grafana (plugins, gravatar, news feed -> disable via env), Prometheus. Fix anything found (self-host assets under web/public).
2. Compose: an offline-ready way to start from cold: docker-compose.offline.yml override (or env) that uses the already-pulled
   GHCR images by tag/SHA with pull_policy: never, no build sections, and the same ports. A script infra/scripts/expo-prepull.sh
   (run while online: pull every image the demo needs, incl. kafka/postgres/minio/prometheus/grafana, and `docker image save`
   them to a tar as a second backup) and infra/scripts/expo-offline-up.sh (compose up with the override, wait healthy, run
   demo-ready.sh against localhost, print the URLs).
3. CI proof: .github/workflows/ci-offline.yml (workflow_dispatch + push to task/** when these files change): build/pull the images,
   then BLOCK outbound network on the runner (iptables: allow loopback and docker bridges only; verify `curl https://github.com`
   fails), then run expo-offline-up.sh from cold and require "DEMO READY ... compare=M/M". Also load the web page with a headless
   browser and fail on any failed external request.
4. docs/EXPO_OFFLINE.md: one page, exact commands: the night before (prepull + save tar), at the expo (power on, Docker Desktop,
   expo-offline-up.sh, open http://localhost:8088), what works offline and what does not (GitHub Actions pages, public link ->
   use the backup recording docs/expo/backup/ and screenshots), and how to restore from the tar if images are missing.
Do not change the normal compose file behaviour or the CD deploy.

Files (edit only these): docker-compose.offline.yml (new), infra/scripts/expo-prepull.sh, infra/scripts/expo-offline-up.sh (new),
.github/workflows/ci-offline.yml (new), docs/EXPO_OFFLINE.md (new), web/index.html, web/public/**, web/src/styles/** (only to
remove external URLs), infra/docker/nginx.conf, infra/monitoring/** (Grafana offline env/provisioning only).

Acceptance check: ci-offline green on your branch with outbound network proven blocked and DEMO READY in the log.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql (if a contract looks wrong, say so in your report).
- Do not read or change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only edit the files listed. If you are on vivek-pc: do NOT start Docker containers or kind there (the live demo runs on it). Acceptance runs in GitHub Actions on your task branch.
