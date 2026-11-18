# Syllabus and faculty evidence mapping

This mapping uses the CDD areas listed in `handoff.md` §11 and faculty requirements in §3. The handoff warns that the syllabus summary must be checked against the current course document. A path or command is a demonstration pointer, not proof that a run passed or a proposed feature is complete.

## CDD syllabus areas

| Area | Repository evidence | Show live / qualification |
|---|---|---|
| DevOps culture, CI/CD, IaC | `.github/workflows/`; `docker-compose.yml`; `infra/`; task branches and PR history | Show a real PR/review and run history. Terraform is not present in the inspected `infra/`; do not claim IaC implementation. |
| Git and Gitflow | Git repository branch/commit/PR history | Show actual task branch, reviewed PR, merge and release tags if available; planned workflow alone is insufficient. |
| Git practical operations | GitHub repository and local Git client | Demonstrate clone/fetch/pull/push, fork, merge/rebase and conflict resolution using a safe example; history does not establish all practical exercises. |
| Docker and automation | `infra/docker/*.Dockerfile`; `docker-compose.yml`; `.github/workflows/ci-images.yml`; `.github/workflows/ci-compose.yml` | `docker compose up -d --build`; `docker compose ps`; `docker compose logs`; show CI image build and labels. CI image workflow has `push: false`. |
| UX and design thinking | `docs/ux/personas.md`, `journeys.md`, `design-thinking.md`, `figma-plan.md` | Show artifacts and explain that personas/journeys are assumptions/proposals; user research, prototype and revisions need actual evidence. |
| Usability and interaction design | `docs/ux/usability-decisions.md`, `design-system.md`; `web/src/` | Explain state labeling, accessibility, user feedback; use a running UI for implementation claims. No usability study/conformance claim from docs alone. |
| Figma | `docs/ux/figma-plan.md` | Show actual Figma file/frames/components if available. The plan document alone is not proof of a completed prototype. |
| Final mini-project | Java modules, `web/`, Compose stack, CI workflows and test sources | Run the actual stack and show successful CI, tests, and end-to-end evidence. |

## Faculty requirements (`handoff.md` §3)

| Faculty requirement | Concrete evidence in repo | Examiner demonstration / evidence limits |
|---|---|---|
| Substantial application using Python, Java or ML as appropriate | Java modules in `common/`, `compiler/`, `reference/`, `engine/`, `control-api/`, `simulator/`; Gradle build | `./gradlew build --console=plain`; show source and completed CI run. Java is the primary implementation. |
| Fully functional CI/CD: automated build, testing and deployment | CI definitions: `ci-java.yml`, `ci-integration.yml`, `ci-web.yml`, `ci-images.yml`, `ci-compose.yml`, `ci-mock.yml` | These define build/test/image/Compose checks. `cd.yml` and deployment evidence are absent from the inspected checkout; CD is not established here. |
| GitHub Actions workflow definitions and execution logs | `.github/workflows/*.yml` | Actions tab or `gh run list`; select actual successful commit and inspect logs/artifacts. YAML existence alone is not a successful execution. |
| Effective Docker use: custom images and running containers | `infra/docker/engine.Dockerfile`, `simulator.Dockerfile`, `control-api.Dockerfile`, `web.Dockerfile`; `docker-compose.yml` | `docker compose up -d --build`; `docker compose ps`; `docker image ls`; show local running containers. External images are not custom project images. |
| Technical depth and innovation | Contracts `docs/contracts/02-pipeline-and-semantics.md` and `03-coordinator-worker-protocol.md`; compiler/runtime/reference/test module | Show event-time semantics, generation fencing, aligned checkpoints, recovery, bounded queues, and a test/log that demonstrates behavior. Avoid unverified guarantees. |
| Complete demonstration | Compose services; `infra/scripts/smoke.sh`; control API and web; test workflows | Present running UI/API, test results, actual fault/recovery and reference comparison, Docker evidence, CI logs, and deployment only if separately verified. |

## Commands and live entry points

- Build and unit test: `./gradlew build --console=plain`.
- Integration test: `./gradlew :tests:integrationTest --console=plain` (requires Docker for Testcontainers).
- Full local stack: `docker compose up -d --build`; inspect with `docker compose ps` and `docker compose logs --tail=100`.
- CI runs: `gh run list --limit 20`, then `gh run view RUN_ID --log`.
- Local API health/version: `curl -fsS http://localhost:18080/api/health`; `curl -fsS http://localhost:18080/api/version`.
- Container images: `docker image ls`; inspect revision label using `docker image inspect --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' IMAGE`.

For expected host ports and endpoints, see `docs/contracts/00-stack-and-layout.md`. For deployment, runner, and GHCR evidence limitations, see [DevOps evidence guide](devops-evidence.md).
