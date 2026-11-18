# DevOps evidence guide for the examiner

Use the repository's actual current commit and environment. Record URLs, timestamps, commit SHA, command output, and relevant run IDs. Do not treat workflow definitions or image tags as proof that a workflow passed or an image was deployed.

## 1. GitHub Actions run list

From a checkout with GitHub CLI authenticated:

```sh
gh run list --limit 20
```

Select the run corresponding to the commit being demonstrated, then inspect it:

```sh
gh run view RUN_ID
gh run view RUN_ID --log
```

Alternatively open the repository's **Actions** tab, select workflow and run, and verify commit SHA, branch, conclusion, job logs, and uploaded artifacts. Current workflow definitions include `ci-java`, `ci-integration`, `ci-web`, `ci-images`, `ci-compose`, and `ci-mock`. Preserve the URL and commit in report results. A run marked queued/in-progress is not a pass.

## 2. Show a workflow log

For Java build/unit tests:

```sh
gh run list --workflow ci-java.yml --limit 10
gh run view RUN_ID --log
```

In Actions, expand **Build and unit-test every module** and inspect the uploaded `java-test-reports` artifact. For integration evidence, inspect the `ci-integration.yml` run and `integration-test-reports`. For the Compose workflow, show the `Smoke`, `Status`, and `Logs` steps and retain the smoke script's PASS/FAIL output. Do not infer results from workflow YAML.

## 3. Inspect custom Docker images and revision labels

Images built by `ci-images.yml` are local to its runner and the workflow explicitly sets `push: false`. To show local images, first build them if necessary:

```sh
docker compose build
docker image ls
```

Inspect the Compose-built image and its revision label. Compose assigns the control API image name unless an explicit tag is configured; use the container's configured image rather than assuming a `:local` tag:

```sh
docker compose images
docker inspect --format '{{.Config.Image}}' "$(docker compose ps -q control-api)"
docker inspect --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' "$(docker compose ps -q control-api)"
```

The CI workflow builds tags `aether/engine:ci`, `aether/simulator:ci`, `aether/control-api:ci`, and optionally `aether/web:ci`, then checks Java entrypoints and revision labels. Use those tags only in the CI runner where they exist. Record the observed SHA; do not assume `:local` identifies a released version.

## 4. Show running Compose services and logs

Start the local stack:

```sh
docker compose up -d --build
docker compose ps
```

Show service logs and smoke result:

```sh
docker compose logs --tail=100
docker compose exec -T control-api curl -fsS http://localhost:8080/api/ready
bash infra/scripts/smoke.sh
```

The compose workflow runs the smoke script and prints service status/logs before teardown. Local service ports are listed in `docs/contracts/00-stack-and-layout.md`; the web UI is normally at `http://localhost:8088`, control API at `http://localhost:18080`, coordinator at `http://localhost:18081`, and simulator at `http://localhost:18070`. Show actual command output. Clean up after the demo with `docker compose down` (use `-v` only when intentionally deleting local data).

## 5. Show deployed version endpoint

The control API version route returns the commit and optional build time:

```sh
curl -fsS http://localhost:18080/api/version
```

If a real deployment URL is available, use that URL instead and record the response with deployment identity. Compare `commit` with the deployed image's revision label and the successful workflow commit. The local response proves only the local container's identity; it does not prove remote deployment. Endpoint contract: `docs/contracts/04-control-api-rest-ws.md`.

## 6. Self-hosted runner evidence

The handoff and contract mention a self-hosted runner label `aether-deploy`, but the inspected `.github/workflows/` listing does not contain the referenced `cd.yml`. Therefore this checkout does not provide a workflow log proving a self-hosted runner is configured or has deployed the application.

If the team has current runner/deployment evidence outside this checkout, show it directly:

1. In GitHub, open **Settings → Actions → Runners** and show the runner as online with the expected label, without exposing secrets.
2. Open the actual deployment workflow run and show that its job was assigned to the self-hosted runner.
3. Show deployment and smoke-test logs for the same commit SHA.
4. Query the deployed `/api/version` endpoint and compare its commit with the run.

Never claim this step succeeded without those observations. Do not expose runner tokens, environment secrets, or private keys.

## 7. GHCR packages

The inspected `ci-images.yml` builds images but does not push. No GHCR publication workflow was found. Thus package availability is not established by this checkout. If packages are published by a separate real workflow, show them at the repository's **Packages** page and inspect a version/digest that matches the deployed commit. From an authenticated shell, package access may be checked with:

```sh
gh api user/packages?package_type=container
docker pull ghcr.io/OWNER/IMAGE:COMMIT_SHA
docker image inspect ghcr.io/OWNER/IMAGE:COMMIT_SHA
```

Use actual owner, image and tag from repository/package evidence; the placeholders above are not literal values. Do not imply that an `aether/*:ci` local tag is a GHCR package.

## Evidence record template

- Commit SHA: [observed]
- Workflow / run URL / conclusion: [observed]
- Test artifact and summary: [observed]
- Image name, tag, revision label/digest: [observed]
- Compose service status and smoke output: [observed]
- Deployment URL and `/api/version` response: [observed or “not available”]
- Runner job evidence: [observed or “not available”]
- GHCR package/digest: [observed or “not available”]
