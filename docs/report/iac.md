# Infrastructure as code

`infra/terraform/` is a local-only Terraform configuration. It declares Docker containers, images, one network, and three volumes on a Docker daemon. It does not declare a cloud provider, a remote backend, or any non-Docker resource. Docker Compose remains the primary demo path (`infra/terraform/README.md`). This page states only what those Terraform files, that README, `.github/workflows/ci-terraform.yml`, `docker-compose.yml`, and `infra/kubernetes/` say. A workflow file is not evidence that a run passed. Runs: [ci-terraform Actions](https://github.com/VivekTiwary-25/AetherStream/actions/workflows/ci-terraform.yml).

There is no `main.tf`. The application containers are in [`app.tf`](../../infra/terraform/app.tf).

## Providers and versions

[`versions.tf`](../../infra/terraform/versions.tf) requires Terraform `>= 1.6.0` and provider `kreuzwerker/docker` at `~> 3.6.2`. The README describes that pin as Docker provider 3.6.x. [`providers.tf`](../../infra/terraform/providers.tf) is only `provider "docker" {}`. It uses the local Docker daemon; `DOCKER_HOST` can select another endpoint (README example on Docker Desktop for Windows: `npipe:////./pipe/docker_engine`). No `backend` block is present, so state stays local. The directory contains no `.terraform.lock.hcl`. The README says generated provider locks and state stay local to the run, and that `.terraform/`, `*.tfstate*`, `*.plan`, secret `*.tfvars`, and credentials must not be committed. It also says not to upload state as a CI artifact, and that deleting local state is not teardown.

CI installs Terraform `1.14.0` with `hashicorp/setup-terraform@v4` and `terraform_wrapper: false` (`.github/workflows/ci-terraform.yml`).

## Variables

Declared in [`variables.tf`](../../infra/terraform/variables.tf):

| Variable | Default | Constraint |
|---|---|---|
| `image_tag` | none (required) | `^([0-9a-f]{40}\|ci)$` — a full lowercase commit SHA, or `ci` for locally built images |
| `registry_prefix` | `ghcr.io/vivektiwary-25/aetherstream-` | string; suffixes `engine`, `simulator`, `control-api`, and `web` are appended |
| `pg_password` | `aether` | `sensitive = true`. The README says sensitive values are still stored in Terraform state and saved plans |
| `host_ports` | see below | object; every value must be a distinct integer from 1 to 65535 |

Default `host_ports` match contract 00 host ports: Kafka `19092`, Postgres `15432`, MinIO API `19000`, MinIO console `19001`, coordinator `18081`, simulator `18070`, control-api `18080`, web `8088`. The README says to stop Compose before apply, or override the whole `host_ports` object (for example `TF_VAR_host_ports` JSON) with distinct free ports. Kafka's external advertised listener follows the chosen Kafka host port. Changing ports alone does not create a second demo: the README says this configuration supports one Terraform demo per daemon.

`image_tag` has no floating default. For published images the README requires a full commit SHA whose four images were published by the CD workflow. CI sets `TF_VAR_image_tag=ci` and `TF_VAR_registry_prefix=ghcr.io/vivektiwary-25/aetherstream-`. It does not set `TF_VAR_pg_password`, so apply uses the default `aether`.

## Resources

Expanded `for_each` and `count` produce 24 resource instances: 8 `docker_image`, 12 `docker_container`, 1 `docker_network`, 3 `docker_volume`.

| File | Instances |
|---|---|
| [`network.tf`](../../infra/terraform/network.tf) | `docker_network.aether`, name `aether-terraform`. Local `log_opts`: json-file `max-size=10m`, `max-file=3` |
| [`volumes.tf`](../../infra/terraform/volumes.tf) | `aether-terraform-kafka-data`, `aether-terraform-postgres-data`, `aether-terraform-minio-data` |
| [`postgres.tf`](../../infra/terraform/postgres.tf) | image `postgres:17-alpine`; container `aether-terraform-postgres` |
| [`minio.tf`](../../infra/terraform/minio.tf) | images `pgsty/minio:RELEASE.2026-08-04T00-00-00Z` and `pgsty/mc:RELEASE.2026-11-12T00-00-00Z`; containers `aether-terraform-minio` and `aether-terraform-minio-init` |
| [`kafka.tf`](../../infra/terraform/kafka.tf) | image `apache/kafka:4.3.2`; containers `aether-terraform-kafka` and `aether-terraform-kafka-init` |
| [`app.tf`](../../infra/terraform/app.tf) | four app images; containers coordinator, worker (count 3), simulator, control-api, web |

Every `docker_image` sets `keep_locally = true`. The README says the provider uses an existing local tag before pulling a missing tag, keeps cached images after destroy, and that `keep_locally` controls deletion, not pulling. There are no registry-lookup data sources and no pull triggers.

Published ports set `ip = "127.0.0.1"`. Workers publish no host port. Container and volume names are prefixed `aether-terraform-`. DNS aliases on `aether-terraform` are `kafka`, `postgres`, `minio`, `coordinator`, `simulator`, `control-api`, and `web`. The network is not Compose's `aether` network.

**Postgres.** Database `aether`, user `aether`, password `var.pg_password`. The unmodified `infra/db/schema.sql` is uploaded to `/docker-entrypoint-initdb.d/01-schema.sql` before startup. The data volume is `/var/lib/postgresql/data`. Healthcheck is `pg_isready -h 127.0.0.1 -U aether -d aether` (interval 5s, timeout 5s, 20 retries, start period 5s). The file comment says the temporary init server uses a Unix socket, so the TCP check waits for schema loading. `wait = true`, `wait_timeout = 180`. Host port maps container `5432` to `var.host_ports.postgres`. The README says entrypoint SQL runs only on an empty database volume and is not a schema migration.

**MinIO.** Command `server /data --console-address :9001`. Root user `aether`, root password `aether-secret` (literals, not variables). Volume `/data`. Ports `9000` and `9001` to `minio_api` and `minio_console`. Healthcheck curls `http://127.0.0.1:9000/minio/health/ready`. `aether-terraform-minio-init` runs `mb --ignore-existing local/aether-checkpoints` with `MC_HOST_local=http://aether:aether-secret@minio:9000`. It is a one-shot (`must_run = false`, `attach = true`) and a lifecycle postcondition requires `exit_code == 0`.

**Kafka.** Single-node KRaft. Hostname `kafka`. Listeners `CONTROLLER://:9093`, `INTERNAL://:9092`, `EXTERNAL://:19092`. Advertised listeners `INTERNAL://kafka:9092` and `EXTERNAL://localhost:${var.host_ports.kafka}`. The published port is container `19092` to `var.host_ports.kafka` on `127.0.0.1`. `CLUSTER_ID=4L6g3nShT-eMCtK--X86sw`. Replication factors and min ISR are 1. `KAFKA_NUM_PARTITIONS=6`. `KAFKA_AUTO_CREATE_TOPICS_ENABLE=false`. Log dir `/var/lib/kafka/data` on the Kafka volume. Healthcheck runs `kafka-broker-api-versions.sh --bootstrap-server localhost:9092` (interval 10s, timeout 20s, 12 retries, start period 20s). `wait_timeout = 240`. There is no `KAFKA_HEAP_OPTS` in this file. `aether-terraform-kafka-init` runs `kafka-topics.sh --bootstrap-server kafka:9092 --create --if-not-exists --topic truck-events --partitions 6 --replication-factor 1`, with the same exit-code postcondition.

**Applications.** Shared `local.app_env`:

- `KAFKA_BOOTSTRAP=kafka:9092`, `KAFKA_TOPIC=truck-events`
- `PG_URL=jdbc:postgresql://postgres:5432/aether`, `PG_USER=aether`, `PG_PASSWORD` from `var.pg_password`
- `S3_ENDPOINT=http://minio:9000`, `S3_ACCESS_KEY=aether`, `S3_SECRET_KEY=aether-secret`, `S3_BUCKET=aether-checkpoints`
- `COORDINATOR_URL=http://coordinator:8081`, `SIMULATOR_URL=http://simulator:8070`
- `GIT_SHA=${var.image_tag}`

Those names are container DNS aliases and container ports, not the host ports. `JAVA_OPTS` is not set. Terraform does not build the four app images; it references `${var.registry_prefix}${service}:${var.image_tag}`.

| Container | Image | Extra env / command | Depends on | Host port | Health |
|---|---|---|---|---|---|
| `aether-terraform-coordinator` | engine | `CHECKPOINT_INTERVAL_MS=10000`; `restart = unless-stopped`; `destroy_grace_seconds = 5` | kafka-init, minio-init, postgres | `8081` → `coordinator` | `curl` `http://localhost:8081/coord/health`; 30 retries; start period 20s; wait 240s |
| `aether-terraform-worker-1` .. `worker-3` | engine | entrypoint `/app/bin/worker`; `WORKER_ID=worker-1` .. `worker-3`; same restart and grace | coordinator | none (alias `worker-N`) | `curl` `http://localhost:8090/health`; start period 10s; wait 180s |
| `aether-terraform-simulator` | simulator | `SIM_SEED=42`, `SIM_TIME_SCALE=10` only | kafka-init | `8070` → `simulator` | `curl` `http://localhost:8070/health` |
| `aether-terraform-control-api` | control-api | shared env only | coordinator, simulator | `8080` → `control_api` | `curl` `http://localhost:8080/api/ready`; start period 30s; wait 240s |
| `aether-terraform-web` | web | no env block | control-api | `80` → `web` | `wget -q -O /dev/null -T 3 http://127.0.0.1/` |

Coordinator and workers are the only app containers with `restart = unless-stopped`. Logging on these containers is json-file with the shared `log_opts`.

## Outputs

[`outputs.tf`](../../infra/terraform/outputs.tf) has one output, `urls`. With default ports it is:

- web `http://localhost:8088`
- control_api `http://localhost:18080/api`
- readiness `http://localhost:18080/api/ready`
- coordinator `http://localhost:18081/coord/health`
- simulator `http://localhost:18070/state`
- minio_api `http://localhost:19000`
- minio_console `http://localhost:19001`
- kafka `localhost:19092`
- postgres `postgresql://aether@localhost:15432/aether` (user only; the password is not in the output)

The description says all published ports bind to loopback.

## Annotated excerpt

There is no `main.tf`. These are the first 18 lines of [`infra/terraform/app.tf`](../../infra/terraform/app.tf), the file that selects the four application images and the environment every app container shares. The two `#` comments are in the file. The slice stops in the middle of `app_env`: the next source lines are `S3_BUCKET`, `COORDINATOR_URL`, `SIMULATOR_URL`, and `GIT_SHA`. The closing `]` and `}` are not in this excerpt.

```hcl
resource "docker_image" "app" {
  for_each = toset(["engine", "simulator", "control-api", "web"])
  name     = "${var.registry_prefix}${each.key}:${var.image_tag}"
  # The provider uses an existing local tag before attempting a pull.
  # CI builds all four tags first; keep them on destroy as well.
  keep_locally = true
}

locals {
  app_env = [
    "KAFKA_BOOTSTRAP=kafka:9092",
    "KAFKA_TOPIC=truck-events",
    "PG_URL=jdbc:postgresql://postgres:5432/aether",
    "PG_USER=aether",
    "PG_PASSWORD=${var.pg_password}",
    "S3_ENDPOINT=http://minio:9000",
    "S3_ACCESS_KEY=aether",
    "S3_SECRET_KEY=aether-secret",
```

- `for_each` is four image resources, not a `docker build`. Names are the registry prefix, the service, and `image_tag`. CI's tag is `ci`; a published tag must be a 40-character SHA.
- `keep_locally = true` is why destroy leaves the images on the daemon. The comments match the README: local tag first, then pull if missing.
- `app_env` uses Compose-style DNS (`kafka`, `postgres`, `minio`), which works only because `network.tf` sets those aliases on `aether-terraform`.
- `PG_PASSWORD` is the sensitive variable. The MinIO key and secret in this local are string literals, the same dev pair the MinIO container sets as `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD`.
- `GIT_SHA` (next lines, not shown) is `var.image_tag`. In CI that runtime value is `ci`, even though the workflow build-args pass `GIT_SHA=${{ github.sha }}` into the engine, simulator, and control-api image builds and `VITE_GIT_SHA` into the web build.

## How CI runs it

Workflow: [`.github/workflows/ci-terraform.yml`](../../.github/workflows/ci-terraform.yml). Actions page: [ci-terraform](https://github.com/VivekTiwary-25/AetherStream/actions/workflows/ci-terraform.yml).

Job `ci-terraform` runs on `ubuntu-latest`, timeout 30 minutes, `permissions: contents: read`. Triggers: push to `main` or `task/**` only when `infra/terraform/**` or this workflow file changes; every pull request to `main` (no path filter on the pull request). Env: `TF_IN_AUTOMATION=true`, `TF_INPUT=false`, `TF_VAR_image_tag=ci`, `TF_VAR_registry_prefix=ghcr.io/vivektiwary-25/aetherstream-`. Shell is bash.

The job does not have a step named plan, and it does not save a plan file. The README's local sequence (`plan -out=demo.plan`, then `apply demo.plan`) is not what CI runs. `terraform apply` still calculates a plan before it creates resources. Order in the workflow:

1. `actions/checkout@v7`.
2. Install Terraform `1.14.0`.
3. **Format:** `terraform -chdir=infra/terraform fmt -check -recursive`.
4. **Init:** `terraform -chdir=infra/terraform init -no-color`. Step id `init`. Later destroy steps run only if this outcome is `success`.
5. **Validate:** `terraform -chdir=infra/terraform validate -no-color`.
6. `docker/setup-buildx-action@v4`, then four `docker/build-push-action@v7` builds, each `push: false`, `load: true`, context `.`:
   - `infra/docker/engine.Dockerfile` → `ghcr.io/vivektiwary-25/aetherstream-engine:ci`, build-arg `GIT_SHA=${{ github.sha }}`, cache scope `terraform-engine`
   - `infra/docker/simulator.Dockerfile` → `...-simulator:ci`, same `GIT_SHA` build-arg, scope `terraform-simulator`
   - `infra/docker/control-api.Dockerfile` → `...-control-api:ci`, same `GIT_SHA` build-arg, scope `terraform-control-api`
   - `infra/docker/web.Dockerfile` → `...-web:ci`, build-arg `VITE_GIT_SHA=${{ github.sha }}`, scope `terraform-web`
7. **Images present:** `docker image inspect` on all four `:ci` tags. The README says all four images must exist before apply.
8. **Apply:** `terraform -chdir=infra/terraform apply -auto-approve -no-color -var=image_tag=ci`. This is the real Docker infrastructure, not a plan-only check.
9. **Ready check** (hardcoded default ports, not `terraform output`):
   - Up to 36 attempts, 5 seconds apart: `curl --fail --silent --show-error --max-time 5 http://localhost:18080/api/ready` and `jq -e '.status == "UP"'`. Then `test "$ready" = true`. The body is always printed (`cat` or true).
   - `curl` `http://localhost:8088/` must write a non-empty file.
   - `curl` `http://localhost:8088/api/ready` must also have `.status == "UP"` (web proxy).
   - `curl` `http://localhost:18080/api/workers` must satisfy `length == 3 and all(.[]; .alive == true)`.
   - `docker exec aether-terraform-postgres psql -U aether -d aether -v ON_ERROR_STOP=1 -c 'SELECT count(*) FROM pipelines; SELECT count(*) FROM checkpoints;'`
   - `docker exec` on `aether-terraform-minio` runs `/usr/bin/mc stat local/aether-checkpoints` with `MC_HOST_local=http://aether:aether-secret@minio:9000`.
   - `docker exec` on `aether-terraform-kafka` runs `kafka-topics.sh --bootstrap-server kafka:9092 --describe --topic truck-events`. The step fails if that command fails. It does not parse partition count.
10. On failure, list `aether-terraform-` containers, inspect state, and dump the last 150 log lines of each.
11. **Destroy**, `if: always() && steps.init.outcome == 'success'`: `terraform -chdir=infra/terraform destroy -auto-approve -no-color -var=image_tag=ci`. This includes a failed or partial apply, as long as init succeeded.
12. **Teardown check**, same condition: no container id matching name `aether-terraform-`, no network matching name `aether-terraform`, no volume matching name `aether-terraform-`.

The README says a green `ci-terraform` job proves apply, readiness, and destroy, not just syntax. That is a claim about a green run of this job, not about the YAML by itself. This write-up does not record a run result. The ready-check URLs are the default host ports; the workflow would not follow a non-default `host_ports` override because it does not read `terraform output`.

## Relation to Compose

Compose file: [`docker-compose.yml`](../../docker-compose.yml). The Terraform README calls Compose the primary demo path and this configuration a parallel local path. Same pinned infrastructure images: `apache/kafka:4.3.2`, `postgres:17-alpine`, `pgsty/minio:RELEASE.2026-08-04T00-00-00Z`, `pgsty/mc:RELEASE.2026-11-12T00-00-00Z`. Same KRaft `CLUSTER_ID`, listener names, replication factors, six default partitions, auto-create off, and log dir. Same topic create flags (`truck-events`, 6 partitions, replication 1) and the same MinIO bucket create (`mb --ignore-existing local/aether-checkpoints`). Same json-file log limits. Same default host ports. Coordinator checkpoint interval `10000`, worker entrypoint `/app/bin/worker`, worker ids `worker-1` through `worker-3`, simulator `SIM_SEED=42` and `SIM_TIME_SCALE=10`.

Differences that are in the files:

- Compose network name is `aether`. Terraform's is `aether-terraform`, with aliases so the same DNS names resolve. Container names in Terraform are explicit `aether-terraform-*`. Compose does not set `container_name`.
- Compose volumes are `kafka-data`, `postgres-data`, `minio-data`. Terraform volumes are `aether-terraform-kafka-data`, `aether-terraform-postgres-data`, `aether-terraform-minio-data`. The README says destroy removes the Terraform volumes and not to import Compose volumes or point this demo at production data.
- Compose builds app images as `aether/engine:local` (workers reuse that image with `pull_policy: never`), plus locally built simulator, control-api, and web images from `infra/docker/*.Dockerfile`, with `GIT_SHA` default `dev`. Terraform does not build; it references GHCR names (or the CI `:ci` tags the workflow loads first).
- Compose publishes ports as `"19092:19092"` and `"15432:5432"` with no `127.0.0.1` bind in the file. Terraform sets `ip = "127.0.0.1"` on every published port.
- Compose mounts `./infra/db/schema.sql` read-only onto the Postgres init directory. Terraform uploads the file contents. The Terraform README says entrypoint SQL runs only on an empty database volume, as in Compose. `docker-compose.yml` itself does not state that.
- Compose Postgres healthcheck is `pg_isready -U aether -d aether` with no `-h`. Terraform adds `-h 127.0.0.1`.
- Compose Kafka advertised external listener is the literal `localhost:19092` and sets `KAFKA_HEAP_OPTS`. Terraform interpolates `var.host_ports.kafka` and does not set `KAFKA_HEAP_OPTS`.
- Compose `x-app` sets `JAVA_OPTS` (`AETHER_JAVA_OPTS`, default `-Xms64m -Xmx384m -XX:+ExitOnOutOfMemoryError`). Terraform `app_env` does not.
- Compose init services use `condition: service_healthy` / `service_completed_successfully`. Terraform uses `depends_on` plus `wait` and init-container `exit_code == 0` postconditions. Those are not the same attribute. Compose `control-api` waits for healthy Postgres and coordinator. Terraform `control_api` depends on the coordinator and simulator containers, not on Postgres.
- Compose services `prometheus` and `grafana` are under profile `monitoring`. They are not in `infra/terraform/`. The Terraform README says optional Compose monitoring-profile services are not part of this demo path.
- Compose reads `PG_PASSWORD` and `S3_*` from the environment with the same dev defaults. Terraform does not read Compose's `.env` (README). MinIO credentials in Terraform are literals.

## Relation to Kubernetes

Kubernetes files: [`infra/kubernetes/`](../../infra/kubernetes/). Its README calls that tree a deployment alongside the unchanged root Compose demo, namespace `aether`, a single-node development baseline, not HA and not internet-facing. CI for that path is `ci-k8s.yml` (kind), not `ci-terraform.yml`.

Shared facts: Kafka image `apache/kafka:4.3.2`, Postgres `postgres:17-alpine`, MinIO `pgsty/minio:RELEASE.2026-08-04T00-00-00Z`, schema taken from `infra/db/schema.sql` (kustomize `configMapGenerator` `postgres-schema`; Terraform `upload`), topic `truck-events` with six partitions and replication one, checkpoint bucket `aether-checkpoints`, four app images, one coordinator, three workers running `/app/bin/worker`, web proxying `/api` and `/ws`. `kustomization.yaml` pins all four app images to GHCR tag `6fb5171839a762f762c7bea3194da343b953c2cb`. Terraform instead requires `image_tag` (SHA or `ci`) and has no tag checked in.

Differences that are in the files:

- Kubernetes uses a Namespace, Services, Deployments, StatefulSets, PVCs, a ConfigMap, and Secret `aether-secret`. Terraform uses the Docker provider only.
- Kafka is a 1-replica StatefulSet with a headless Service. Its listeners in `kafka.yaml` are `CONTROLLER` and `INTERNAL` only (`INTERNAL://kafka:9092`). Quorum voter is `1@kafka-0.kafka.aether.svc.cluster.local:9093`. Terraform also publishes an `EXTERNAL` listener on `19092` to the host.
- Postgres is a StatefulSet with a 2 GiB PVC (`postgres.yaml` request). MinIO is a Deployment with `Recreate` and a 2 GiB PVC. Terraform uses Docker named volumes and does not set a size.
- Coordinator is a Deployment with strategy `Recreate`, replicas 1. Workers are a StatefulSet, replicas 3, pod names `worker-0`, `worker-1`, `worker-2`; `WORKER_ID` comes from the pod name (downward API). Terraform workers are three containers named `worker-1` through `worker-3` with those literal ids. Neither publishes worker health to the host; Kubernetes health is Service port 8090, clusterIP None.
- Web is a NodePort Service on `30088`. The kind example maps that to host `127.0.0.1:8088`. Terraform publishes container port 80 on host port `var.host_ports.web` (default 8088) bound to `127.0.0.1`.
- Kubernetes splits non-secret env into `configmap.yaml` and credentials into `secret.example.yaml`. Terraform puts the password in a sensitive variable and the MinIO root pair in container env literals. The Kubernetes README says those development credentials are public and applied by default.
- `ci-k8s` renders manifests, validates with kubeconform, builds local images, loads them into kind, applies, and smokes readiness plus pipeline processing and worker recovery. `ci-terraform` applies to the runner's Docker daemon and always destroys. They do not share a state file.

## Pointing this at a real cloud later

The files do not contain that procedure. There is no AWS, Azure, or Google provider, no Kubernetes provider, no Terraform module for a cluster, and no remote backend. `provider "docker" {}` and `DOCKER_HOST` select a Docker daemon, including a non-default endpoint. They do not select a cloud API. `registry_prefix` and `image_tag` select image names already published to GHCR (or loaded locally as `:ci`). They do not create cloud infrastructure. `host_ports` only changes loopback publishes on that daemon.

The README states this path needs no cloud account and no paid services, and that a green `ci-terraform` job is a Docker apply/readiness/destroy proof. Nothing in `infra/terraform/` or `ci-terraform.yml` says which cloud resources would replace `docker_container`, `docker_network`, or `docker_volume`, or how state would move. Treating a variable change as a cloud cutover is not supported by these files. A cloud target would be a different configuration. This page does not invent one.

What the files do say must stay true of any later copy of this demo: do not point it at production data; do not import Compose volumes; Postgres init is not a migration; `pg_password` and the MinIO dev pair land in state and plans; published ports in this configuration bind to `127.0.0.1` so those demo credentials are not on a public interface; destroy deletes the three data volumes.
