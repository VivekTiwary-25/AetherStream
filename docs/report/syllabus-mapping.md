# Syllabus and faculty evidence mapping

This mapping uses the CDD areas listed in `handoff.md` §11 and faculty requirements in §3. The handoff warns that the syllabus summary must be checked against the current course document. A path or command is a demonstration pointer, not proof that a run passed or a proposed feature is complete.

## CDD syllabus areas

| Area | Repository evidence | Show live / qualification |
|---|---|---|
| DevOps culture, CI/CD, IaC | `.github/workflows/`; `docker-compose.yml`; `infra/kubernetes/`; `infra/terraform/`; `infra/monitoring/` | Show a real PR and these logs: [ci-terraform 44395471523](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44395471523) printed a plan of 24 to add and later destroyed 24; [ci-k8s 44398295492](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44398295492) printed `kubectl get pods -A -o wide`. Terraform here is the local Docker provider, not a cloud account. |
| Git and Gitflow | Git repository branch/commit/PR history | Show actual task branch, reviewed PR, merge and release tags if available; planned workflow alone is insufficient. |
| Git practical operations | GitHub repository and local Git client | Demonstrate clone/fetch/pull/push, fork, merge/rebase and conflict resolution using a safe example; history does not establish all practical exercises. |
| Docker and automation | `infra/docker/*.Dockerfile`; `docker-compose.yml`; `.github/workflows/ci-images.yml`; `.github/workflows/ci-compose.yml`; `.github/workflows/ci-e2e.yml`; `infra/scripts/demo-e2e.sh` | `docker compose up -d --build`; `docker compose --profile monitoring up -d`; `docker compose ps`; `docker compose logs`; show CI image build and labels. CI image workflow has `push: false`. |
| UX and design thinking | `docs/ux/personas.md`, `journeys.md`, `design-thinking.md`, `figma-plan.md` | Show artifacts and explain that personas/journeys are assumptions/proposals; user research, prototype and revisions need actual evidence. |
| Usability and interaction design | `docs/ux/usability-decisions.md`, `design-system.md`; `web/src/` | Explain state labeling, accessibility, user feedback; use a running UI for implementation claims. No usability study/conformance claim from docs alone. |
| Figma | `docs/ux/figma-plan.md` | Show actual Figma file/frames/components if available. The plan document alone is not proof of a completed prototype. |
| Final mini-project | Java modules, `web/`, Compose stack, CI workflows and test sources | Run the actual stack and show successful CI, tests, and end-to-end evidence. |

## Faculty requirements (`handoff.md` §3)

| Faculty requirement | Concrete evidence in repo | Examiner demonstration / evidence limits |
|---|---|---|
| Substantial application using Python, Java or ML as appropriate | Java modules in `common/`, `compiler/`, `reference/`, `engine/`, `control-api/`, `simulator/`; Gradle build | `./gradlew build --console=plain`; show source and completed CI run. Java is the primary implementation. |
| Fully functional CI/CD: automated build, testing and deployment | `.github/workflows/ci-java.yml`, `.github/workflows/ci-integration.yml`, `.github/workflows/ci-web.yml`, `.github/workflows/ci-images.yml`, `.github/workflows/ci-compose.yml`, `.github/workflows/ci-mock.yml`, `.github/workflows/ci-e2e.yml`, `.github/workflows/ci-k8s.yml`, `.github/workflows/ci-terraform.yml`, `.github/workflows/bench.yml`, `.github/workflows/ci-ui-e2e.yml`, `.github/workflows/cd.yml` | These files are on `main`. `cd.yml` publishes GHCR images and deploys Compose on the self-hosted runner. A YAML file is not a green deploy. `ci-ui-e2e` is `continue-on-error: true`. Show a real Actions log before claiming a run passed. |
| GitHub Actions workflow definitions and execution logs | `.github/workflows/*.yml` | Actions tab or `gh run list`; select actual successful commit and inspect logs/artifacts. YAML existence alone is not a successful execution. |
| Effective Docker use: custom images and running containers | `infra/docker/engine.Dockerfile`, `simulator.Dockerfile`, `control-api.Dockerfile`, `web.Dockerfile`; `docker-compose.yml` | `docker compose up -d --build`; `docker compose ps`; `docker image ls`; show local running containers. External images are not custom project images. |
| Technical depth and innovation | Contracts `docs/contracts/02-pipeline-and-semantics.md` and `03-coordinator-worker-protocol.md`; compiler/runtime/reference/test module; `POST /api/compare`; savepoint and rescale routes | Show event-time semantics, generation fencing, aligned checkpoints, recovery, bounded queues, and a test log. [Run 44395770221](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44395770221) logged 811/811 matched and dead letters 25/25 equal. Do not reuse that count for a different run. |
| Complete demonstration | Compose services; `infra/scripts/smoke.sh`; `infra/scripts/demo-e2e.sh`; control API and web; kind and Terraform CI | Present the running UI only for controls on the page. Scenario buttons, savepoint, restore, rescale, and compare are API calls; those panels are not mounted in `web/src/app/shell/AppShell.tsx`. Show the cited CI logs for kind and Terraform. |

## Commands and live entry points

- Build and unit test: `./gradlew build --console=plain`.
- Integration test: `./gradlew :tests:integrationTest --console=plain` (requires Docker for Testcontainers).
- Full local stack: `docker compose up -d --build`; inspect with `docker compose ps` and `docker compose logs --tail=100`.
- Monitoring profile: `docker compose --profile monitoring up -d`. Grafana is `http://localhost:13000` (dashboard **AetherStream engine**). Prometheus is `http://localhost:19090`.
- CI runs: `gh run list --limit 20`, then `gh run view RUN_ID --log`. Cited logs: `gh run view 44395770221 --log`, `gh run view 44398295492 --log`, `gh run view 44395471523 --log`.
- Local API health/version: `curl -fsS http://localhost:18080/api/health`; `curl -fsS http://localhost:18080/api/version`.
- Scenarios, savepoint, rescale, compare: `curl -fsS http://localhost:18080/api/scenarios`; `curl -fsS -X POST http://localhost:18080/api/executions/ID/savepoints`; `curl -fsS -X POST http://localhost:18080/api/executions/ID/restore` with JSON `savepointId` and `idempotencyKey`; `curl -fsS -X POST http://localhost:18080/api/executions/ID/rescale`; `curl -fsS -X POST http://localhost:18080/api/compare -H 'content-type: application/json' -d '{}'`.
- Container images: `docker image ls`; inspect revision label using `docker image inspect --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' IMAGE`.

For expected host ports and endpoints, see `docs/contracts/00-stack-and-layout.md`. For deployment, runner, and GHCR evidence limitations, see [DevOps evidence guide](devops-evidence.md).
