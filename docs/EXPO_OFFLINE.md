# Expo: one-machine offline demo

Use Git Bash on Windows (or Bash on Linux), Docker Desktop/Engine with Compose **2.24.4+**, `curl`, and `jq`. Run from this checkout; keep the checkout because schema and monitoring files are bind-mounted. The offline scripts use project `aether-offline`, including monitoring, and the normal host ports. Stop any other stack using those ports **before expo startup**; these scripts do not stop the live/CD stack. Normal Compose and CD are unchanged.

## Night before — internet available

Start Docker Desktop. Choose a **published, tested commit SHA** from CD (all four GHCR images must exist); replace the sample value below. Authenticate with `docker login ghcr.io` first if the packages are private.

```bash
SHA=REPLACE_WITH_PUBLISHED_COMMIT_SHA
bash infra/scripts/expo-prepull.sh "$SHA" "$PWD/expo-images.tar"
# Rehearse once while online, then disconnect Wi-Fi/Ethernet and repeat:
bash infra/scripts/expo-offline-up.sh
```

Prepull downloads every resolved image, including Kafka and its initializer, Postgres, MinIO and `mc`, Prometheus, Grafana, and the four GHCR app images. It saves the complete set to `expo-images.tar` and records the tag in `.expo-offline.env`. Keep both beside this checkout; copy the tar to a USB drive as a second backup. Do not prune Docker images afterward. Prep **Bash, curl, jq, Docker Desktop and its Linux engine** before leaving; installers and first-time Docker setup are not offline operations.

## At the expo — no internet required

1. Power on the machine; start Docker Desktop and wait for its engine.
2. Open Git Bash in this checkout:

```bash
bash infra/scripts/expo-offline-up.sh
```

The script verifies every image is cached, starts with `--no-build --pull never`, waits for health (including monitoring), then runs `demo-ready.sh` against localhost. Success includes **`DEMO READY ... compare=M/M`**, with a nonzero, equal reference count, followed by the local URLs. It preserves existing volumes and does not require preexisting containers or data. Missing images fail before any container starts.

3. Open **http://localhost:8088**. Other local URLs: API readiness http://localhost:18080/api/ready, Grafana http://localhost:13000, Prometheus http://localhost:19090, MinIO console http://localhost:19001. Default Grafana admin password is `admin` unless `GRAFANA_ADMIN_PASSWORD` is set; viewing is anonymous/read-only.

## Restore a missing image cache

```bash
docker image load --input "$PWD/expo-images.tar"
# Only if .expo-offline.env was also lost; use the SAME SHA saved in that tar:
printf 'EXPO_TAG=%s\n' REPLACE_WITH_SAVED_COMMIT_SHA > .expo-offline.env
bash infra/scripts/expo-offline-up.sh
```

A stale `EXPO_TAG` environment variable overrides the saved file; `unset EXPO_TAG` before restoring another rehearsal. Tar backups contain images, not database volumes, credentials, the checkout, or recordings.

## Offline scope and proof

City rendering, live WebSocket updates, pipeline editing, execution/checkpoints, chaos/recovery, reference comparison, local Grafana dashboards and Prometheus work on the machine. The runtime audit found same-origin web HTTP/WebSocket calls, procedural city graphics, local CSS/system fonts, local nginx upstreams, local backend endpoints, and local Prometheus targets; no external web assets needed vendoring. The offline override disables Grafana reporting, update/plugin checks, preinstallation, key retrieval, gravatar and news.

GitHub Actions/run pages, GHCR pulls, public/tunnel links, and external documentation do **not** work offline. Use the local URLs. For a remote audience or machine failure, use the locally downloaded backup recording and screenshots in **`docs/expo/backup/`**; that directory/media is not present in this checkout and must be supplied before the expo. Do not rely on a cloud-hosted recording.

The **ci-offline** workflow runs on matching `task/**` changes or manual dispatch. It builds the branch with CD's GHCR names, caches infrastructure and a tar, removes images/containers/volumes, blocks IPv4/IPv6 runner and container outbound traffic (only loopback/Docker bridges allowed), proves GitHub HTTPS fails using a pre-resolved address, restores the tar, and runs the offline starter. Headless Chromium opens **http://localhost:8088/?city=live** to override the automatic static backdrop on software WebGL (SwiftShader), and must render the live city canvas and local Grafana without external or failed requests. Artifacts contain the readiness marker, firewall counters, logs and screenshots. CI green on the task branch remains the acceptance gate; local config/syntax checks alone are not that proof.
