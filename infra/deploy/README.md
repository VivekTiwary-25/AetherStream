# Self-hosted deployment

On pushes to `main`, the CD workflow builds and publishes the engine, simulator, control-api, and web images to GHCR under both the full commit SHA and `main` tags. The deploy job runs only for `VivekTiwary-25/AetherStream` on the self-hosted runner labeled `aether-deploy`. It pulls the SHA-pinned images, updates the existing root Compose stack, then checks `/api/health` and the exact commit reported by `/api/version`.

The runner needs Git for Windows with Git Bash, Docker Desktop with Compose, network access to GHCR and the deployed ports, and a checkout of this repository. Public GHCR packages can be pulled anonymously. If pulls are denied, make each package public under the repository's **Packages** settings or authenticate Docker on the runner with a token that has package-read access. Do not add credentials to this repository.

## Manual deploy or rollback

From the repository root, run Git Bash:

```bash
bash infra/deploy/deploy.sh <full-commit-sha>
bash infra/deploy/smoke-deploy.sh <full-commit-sha>
```

The commit must already have published GHCR images for all four components. To roll back, use the full SHA of an earlier published deployment. Alternatively, dispatch the `cd` workflow from `main` and enter that SHA in its optional `sha` input. A blank input deploys the current `main` commit. `.last-deployed` records the most recently successful deploy locally and is generated at runtime; it is not tracked.
