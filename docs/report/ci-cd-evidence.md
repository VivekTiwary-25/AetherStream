# CI/CD evidence

This page describes the workflow definitions checked in `.github/workflows/`; a workflow file is not itself evidence of a successful run. The snapshot below is from the public GitHub Actions API, queried 2026-11-19 (UTC). Conclusions are point-in-time and can change as active runs finish.

## Workflows

Typical durations are approximate planning ranges, not guarantees; runtime varies with dependency downloads, Docker builds, and runner load. Triggers list the events and branch/path filters configured in each workflow.

| Workflow | Trigger | What it proves | Typical duration | Runs |
|---|---|---|---:|---|
| `ci-java.yml` (`ci-java`) | Push to `main` or `task/**` when Java/Gradle/schema/workflow paths match; PR to `main` | `./gradlew build` compiles and unit-tests every module; uploads test reports. | ~2–8 min | [Runs](https://github.com/VivekTiwary-25/AetherStream/actions/workflows/ci-java.yml) |
| `ci-web.yml` (`ci-web`) | Push to `main` or `task/**` for `web/**` or this workflow; PR to `main` | Locked Node install, lint, web tests, production build, and `web/dist` artifact. | ~2–6 min | [Runs](https://github.com/VivekTiwary-25/AetherStream/actions/workflows/ci-web.yml) |
| `ci-integration.yml` (`ci-integration`) | Push to `main` or `task/**` when Java/Gradle/schema/workflow paths match; PR to `main` | Runs Testcontainers integration tests and uploads test reports. | ~5–20 min | [Runs](https://github.com/VivekTiwary-25/AetherStream/actions/workflows/ci-integration.yml) |
| `ci-mock.yml` (`ci-mock`) | Push to `main` or `task/**` for `web/mock/**` or this workflow; PR to `main` | Installs the dev mock server dependencies and executes `node smoke.mjs`. | ~1–3 min | [Runs](https://github.com/VivekTiwary-25/AetherStream/actions/workflows/ci-mock.yml) |
| `ci-images.yml` (`ci-images`) | Push to `main` except ignored `swarm/**`, `boss/**`, `docs/**`, and Markdown paths; PR to `main` | Builds engine, simulator, control API, and web images without pushing; smoke-checks Java images and revision labels. | ~5–20 min | [Runs](https://github.com/VivekTiwary-25/AetherStream/actions/workflows/ci-images.yml) |
| `ci-compose.yml` (`ci-compose`) | Push to `main` except ignored `swarm/**`, `boss/**`, `docs/**`, and Markdown paths; PR to `main` | Builds and starts the Compose stack, runs `infra/scripts/smoke.sh`, captures status/logs, and tears down. | ~8–25 min | [Runs](https://github.com/VivekTiwary-25/AetherStream/actions/workflows/ci-compose.yml) |
| `ci-e2e.yml` (`ci-e2e`) | Push to `main` or `task/**` except ignored `swarm/**`, `boss/**`, `docs/**`, and Markdown paths; PR to `main`; manual dispatch | ShellChecks the demo script, then runs the full-system Compose demo with expected SHA; uploads diagnostics. | ~10–30 min | [Runs](https://github.com/VivekTiwary-25/AetherStream/actions/workflows/ci-e2e.yml) |
| `ci-ui-e2e.yml` (`ci-ui-e2e`) | Push/PR to `main` for web, control-api, engine, UI tests, or this workflow; manual dispatch | Runs a six-step Playwright demo against Compose and uploads browser/Compose evidence. Advisory only: the job has `continue-on-error: true`. | ~10–35 min | [Runs](https://github.com/VivekTiwary-25/AetherStream/actions/workflows/ci-ui-e2e.yml) |
| `ci-k8s.yml` (`ci-k8s`) | Push to `main` or `task/**` and PR to `main` when Kubernetes/Docker/workflow paths match; manual dispatch | Renders and validates manifests, builds images, deploys to kind, checks readiness/demo processing and worker recovery, and collects diagnostics. | ~5–40 min | [Runs](https://github.com/VivekTiwary-25/AetherStream/actions/workflows/ci-k8s.yml) |
| `ci-terraform.yml` (`ci-terraform`) | Push to `main` or `task/**` for `infra/terraform/**` or this workflow; PR to `main` | Formats/validates Terraform, builds local images, applies real Docker infrastructure, checks readiness and services, then destroys and verifies teardown. | ~5–30 min | [Runs](https://github.com/VivekTiwary-25/AetherStream/actions/workflows/ci-terraform.yml) |
| `ci-monitoring.yml` (`ci-monitoring`) | Push to `main` or `task/**` except ignored `swarm/**`, `boss/**`, `docs/**`, and Markdown paths; PR to `main` | Starts the monitoring profile and verifies Prometheus targets/metrics, Grafana health/dashboard, and dashboard metric names. | ~5–30 min | [Runs](https://github.com/VivekTiwary-25/AetherStream/actions/workflows/ci-monitoring.yml) |
| `ci-soak.yml` (`ci-soak`) | Scheduled, manual dispatch, or task-branch changes to this workflow/soak script | ShellChecks and runs a 15-minute chaos soak; uploads summary and Compose logs. | ~15–40 min | [Runs](https://github.com/VivekTiwary-25/AetherStream/actions/workflows/ci-soak.yml) |
| `bench.yml` (`bench`) | Push to `main` or `task/**` for its listed Java/benchmark paths; PR to `main`; manual dispatch | Runs `:tests:benchmarkTest`, appends any generated benchmark report to the job summary, and uploads reports/results. | ~2–30 min | [Runs](https://github.com/VivekTiwary-25/AetherStream/actions/workflows/bench.yml) |
| `cd.yml` (`cd`) | Push to `main` except ignored `swarm/**`, `boss/**`, `docs/**`, and Markdown paths; manual dispatch (optional SHA) | Lints deployment scripts; publishes four GHCR images on main push. Deployment is skipped when `DEMO_FREEZE=1` or `infra/deploy/FREEZE` exists; otherwise deploys the SHA on the self-hosted `aether-deploy` runner. | ~5–30 min | [Runs](https://github.com/VivekTiwary-25/AetherStream/actions/workflows/cd.yml) |
| `ci-offline.yml` (`ci-offline`) | Manual dispatch; push to `task/**` for listed offline/Expo/stack paths | Builds and caches images, blocks runner and container egress, then verifies offline demo readiness, UI/WebSocket, Grafana and absence of external browser requests; uploads proof artifacts. | ~10–30 min | [Runs](https://github.com/VivekTiwary-25/AetherStream/actions/workflows/ci-offline.yml) |
| `ci-kill-repeat.yml` (`ci-kill-repeat`) | Manual dispatch; push to `task/**` for the repeat-kill script/workflow | ShellChecks the script and performs 20 worker-kill/recovery rounds against the Compose demo. | ~10–30 min | [Runs](https://github.com/VivekTiwary-25/AetherStream/actions/workflows/ci-kill-repeat.yml) |
| `ci-coldstart.yml` (`ci-coldstart`) | Push to `main` or `task/**`; PR to `main`; manual dispatch | Builds Compose images and measures cold startup to demo readiness; uploads Compose logs. | ~5–20 min | [Runs](https://github.com/VivekTiwary-25/AetherStream/actions/workflows/ci-coldstart.yml) |
| `ci-docs.yml` (`ci-docs`) | Push to `main` or `task/**` when Markdown/docs paths match; manual dispatch | Runs `infra/scripts/check-md-links.py` to check Markdown links. | <1 min | [Runs](https://github.com/VivekTiwary-25/AetherStream/actions/workflows/ci-docs.yml) |
| `release.yml` (`release`) | Push tag `v*`; manual dispatch with version and dry-run option | Checks release notes/version, builds four images with version/SHA tags, and creates a GitHub Release when not dry-running. | ~5–30 min | [Runs](https://github.com/VivekTiwary-25/AetherStream/actions/workflows/release.yml) |

### CD path

1. On a push to `main`, `cd` builds the engine, simulator, control-api, and web images and publishes each to GHCR under both the immutable commit-SHA tag and `main`.
2. After publish succeeds, the `deploy` job checks out the repository on the self-hosted runner labelled `aether-deploy`. A manual dispatch can select a prior SHA for rollback.
3. `infra/deploy/deploy.sh` sets `AETHER_TAG` to the requested SHA, pulls the SHA-tagged images, and runs Compose with the deployment override (`docker-compose.yml` plus `infra/deploy/docker-compose.deploy.yml`), waiting for services to be ready.
4. `infra/deploy/smoke-deploy.sh` polls `/api/health` until status is `UP`, then checks that `/api/version` reports the requested commit SHA. The job succeeds only when both checks pass (within 120 seconds).

### Dated run snapshot

Latest run snapshot queried from the public Actions API for `branch=main`, `per_page=100`, on 2026-11-19. `In progress` is not a passing conclusion. Run links lead to the individual logs; workflows absent from the returned recent runs are marked not included.

| Workflow | Last conclusion/status | Run |
|---|---|---|
| `bench` | success | [Run 44458797146](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44458797146) |
| `cd` | success | [Run 44460264804](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44460264804) |
| `ci-coldstart` | in progress | [Run 44460820210](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44460820210) |
| `ci-compose` | success | [Run 44460264602](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44460264602) |
| `ci-docs` | success | [Run 44460820178](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44460820178) |
| `ci-e2e` | in progress | [Run 44460264712](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44460264712) |
| `ci-images` | success | [Run 44460264820](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44460264820) |
| `ci-integration` | success | [Run 44458797222](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44458797222) |
| `ci-java` | success | [Run 44458797166](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44458797166) |
| `ci-monitoring` | success | [Run 44460264695](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44460264695) |
| `ci-ui-e2e` | success | [Run 44458797463](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44458797463) |
| `ci-web` | success | [Run 44457719918](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44457719918) |
| `ci-k8s` | not included in returned recent runs | — |
| `ci-kill-repeat` | not included in returned recent runs | — |
| `ci-mock` | not included in returned recent runs | — |
| `ci-offline` | not included in returned recent runs | — |
| `ci-soak` | not included in returned recent runs | — |
| `ci-terraform` | not included in returned recent runs | — |
| `release` | not included in returned recent runs | — |
| `ci-longrun` | no workflow file in this checkout | — |

Workflow inventory checked against `.github/workflows/`: all 19 workflow files in this checkout are represented in the workflow table; `ci-longrun` named in the task is not present.
