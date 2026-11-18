# T21: Continuous deployment: publish images to GHCR by commit SHA, deploy on the self-hosted runner, smoke test

Lane 7 (infra). Images are already built in CI by .github/workflows/ci-images.yml from infra/docker/*.Dockerfile
(engine, simulator, control-api, web). docker-compose.yml at the repo root defines the infra services (app services are
being added by another task in the same file - do NOT edit docker-compose.yml).

Files (create only these):
- .github/workflows/cd.yml
- infra/deploy/docker-compose.deploy.yml (an override file that sets `image: ghcr.io/vivektiwary-25/aetherstream-<name>:${AETHER_TAG}`
  for services coordinator, worker-1, worker-2, worker-3, simulator, control-api, web, with `pull_policy: always` and NO build
  sections; env GIT_SHA=${AETHER_TAG} for every app service)
- infra/deploy/deploy.sh (bash; used by the deploy job; also runnable by hand)
- infra/deploy/smoke-deploy.sh (bash)
- infra/deploy/README.md (how deploy works; how to roll back to a previous SHA by hand)

cd.yml requirements (security matters - this deploys onto a real PC):
- `on: push: branches: [main]` ONLY (no pull_request, no workflow_run from forks), plus `workflow_dispatch` with an
  optional `sha` input for manual rollback. `permissions: contents: read, packages: write` at the publish job only;
  deploy job `permissions: contents: read`. `concurrency: group: deploy, cancel-in-progress: false`.
- Job `publish` (ubuntu-latest): docker/login-action to ghcr.io with GITHUB_TOKEN; docker/build-push-action for the 4 images
  (engine, simulator, control-api, web) with build arg GIT_SHA=${{ github.sha }} (web: VITE_GIT_SHA), tags
  `ghcr.io/vivektiwary-25/aetherstream-<name>:${{ github.sha }}` and `:main`, gha cache, provenance false. Lower-case image names.
- Job `deploy` needs publish, `runs-on: [self-hosted, aether-deploy]`, `if: github.repository == 'VivekTiwary-25/AetherStream' && github.ref == 'refs/heads/main'`.
  The runner is Windows with Git Bash and Docker Desktop: use `shell: bash` for every step. Steps: checkout; run
  `bash infra/deploy/deploy.sh ${{ github.sha }}`; run `bash infra/deploy/smoke-deploy.sh ${{ github.sha }}`; on failure
  `docker compose ... logs --tail 200`.
- deploy.sh SHA: exports AETHER_TAG=SHA, `docker compose -p aether -f docker-compose.yml -f infra/deploy/docker-compose.deploy.yml pull`
  then `up -d --remove-orphans --wait --wait-timeout 180` (fall back to polling health if --wait is unsupported); writes the
  deployed SHA to `infra/deploy/.last-deployed` (gitignored? it is under infra/deploy; create it at runtime only, do not commit it).
  GHCR packages of a public repo can be pulled anonymously once public; if pull is denied, print a clear message telling the
  owner to make the packages public (Packages > settings > visibility) and exit 1.
- smoke-deploy.sh SHA: waits up to 120 s for `http://localhost:8088/api/health` = UP, then checks
  `http://localhost:8088/api/version` commit == SHA (exact), prints PASS/FAIL, exits non-zero on failure.

Acceptance check: `actionlint`-clean YAML (run rhysd/actionlint in a tiny extra job `lint-cd` inside cd.yml that runs on
push to main AND task/** - this job must not deploy anything), and the bash scripts pass `bash -n` and shellcheck in that same
job. The deploy itself will be proven after merge on main.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql. If a contract looks wrong, follow it anyway and say so in your result.
- Do not change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only create/edit the files listed under "Files". If you truly need another file, say so in your result instead of editing it.
- Your PC may not have Java, Node or Docker. The acceptance check runs in GitHub Actions on your task branch (CI runs on push to task/**). Write code carefully so it compiles first time; keep it simple and readable.
- Do not commit secrets, build outputs, node_modules or .gradle/ folders.
- In your result, list what you built, how the acceptance check proves it, and anything left undone.
