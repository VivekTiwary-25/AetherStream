# T01: docker-compose with Kafka (KRaft), Postgres, MinIO + smoke workflow

Lane 7 (infra). Build the local infrastructure part of docker compose, plus a CI workflow that proves it starts.

Files (create only these):
- docker-compose.yml (repo root)
- .env.example (default non-secret dev values only)
- infra/scripts/smoke.sh
- .github/workflows/ci-compose.yml

What to build:
1. docker-compose.yml with network `aether`, named volumes, and services exactly as in docs/contracts/00-stack-and-layout.md:
   - `kafka`: image apache/kafka:4.3.2, single-node KRaft (broker+controller), two listeners: internal `kafka:9092` (for containers) and external `localhost:19092` (host port 19092). Healthcheck using /opt/kafka/bin/kafka-broker-api-versions.sh.
   - `kafka-init`: one-shot service (same image) that waits for kafka healthy, creates topic `truck-events` with 6 partitions, replication 1 (`--if-not-exists`), then exits 0.
   - `postgres`: postgres:17-alpine, db/user `aether`, password `${PG_PASSWORD:-aether}`, mounts ./infra/db/schema.sql into /docker-entrypoint-initdb.d/01-schema.sql, healthcheck pg_isready, host port 15432.
   - `minio`: pgsty/minio:RELEASE.2026-08-04T00-00-00Z, `server /data --console-address :9001`, MINIO_ROOT_USER=${S3_ACCESS_KEY:-aether}, MINIO_ROOT_PASSWORD=${S3_SECRET_KEY:-aether-secret}, host ports 19000/19001, healthcheck (curl or `mc ready local` — pick whatever binary exists in that image; if neither, use a TCP check with bash /dev/tcp).
   - `minio-init`: one-shot that creates bucket `aether-checkpoints` (use image `pgsty/mc` or any maintained `mc` image you can confirm; alternatively the minio image itself if it contains `mc`). Exit 0.
   - Leave clearly marked commented placeholders for coordinator, worker-1..3, simulator, control-api, web (later tasks fill them in). Do NOT add those services yet.
2. infra/scripts/smoke.sh (bash, `set -euo pipefail`): waits up to 120 s for kafka/postgres/minio healthy; checks the topic exists with 6 partitions; checks the `executions` table exists in postgres; checks the bucket exists. Print clear PASS/FAIL lines; exit non-zero on failure. Later tasks will append checks for app services: structure it as small functions.
3. .github/workflows/ci-compose.yml: on push to main and task/**, and pull_request to main. Job `compose` on ubuntu-latest: `docker compose up -d kafka kafka-init postgres minio minio-init`, then `bash infra/scripts/smoke.sh`, and ALWAYS (if: always()) `docker compose logs` and `docker compose down -v`. Timeout 20 min.

Acceptance check: the `ci-compose` workflow run on your task branch is green and its log shows smoke.sh PASS lines.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql. If a contract looks wrong, follow it anyway and say so in your result.
- Never open, read or change anything in swarm/.
- Only create/edit the files listed under "Files". If you truly need another file, say so in your result instead of editing it.
- Your PC may not have Java, Node or Docker. The acceptance check runs in GitHub Actions on your task branch (CI runs on push to task/**). Write code carefully so it compiles first time; keep it simple and readable.
- Do not commit secrets, build outputs, node_modules or .gradle/ folders.
- In your result, list what you built, how the acceptance check proves it, and anything left undone.
