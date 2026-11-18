# Terraform demo environment

Docker Compose remains the primary demo path. This parallel, local-only IaC path
requires Terraform >= 1.6 and a running Linux-container Docker daemon; no cloud
account or paid services. The Docker provider is constrained to 3.6.x. On Docker
Desktop, enable Linux containers; set `DOCKER_HOST` when the daemon is not at the
provider's default endpoint (for example `npipe:////./pipe/docker_engine` on Windows).
For private GHCR packages, authenticate with `docker login ghcr.io` first.

## Published images

Use a full commit SHA whose four images have been published by the CD workflow;
`image_tag` has no floating default. Run from the repository root:

```sh
export TF_VAR_image_tag=<full-published-commit-sha>
# Optional; the dev default is aether. This does not read Compose's .env file.
export TF_VAR_pg_password=<local-demo-password>
terraform -chdir=infra/terraform init
terraform -chdir=infra/terraform fmt -check -recursive
terraform -chdir=infra/terraform validate
terraform -chdir=infra/terraform plan -out=demo.plan
terraform -chdir=infra/terraform apply demo.plan
terraform -chdir=infra/terraform output urls
curl --fail http://localhost:18080/api/ready
# Stop and DELETE this demo's containers, network AND all three data volumes:
terraform -chdir=infra/terraform destroy
```

Keep the same variables for plan/apply/destroy. The registry prefix defaults to
`ghcr.io/vivektiwary-25/aetherstream-`; override `TF_VAR_registry_prefix` for a fork.
The suffixes are `engine`, `simulator`, `control-api`, and `web`. Terraform uses
existing local images before pulling missing tags, and keeps cached images after
destroy. `keep_locally` controls deletion, not pulling; there are no registry
lookup data sources or pull triggers in this configuration.

## Locally built images / CI

Run at the repository root; all four images must exist before apply:

```sh
for service in engine simulator control-api web; do
  docker build -f "infra/docker/${service}.Dockerfile"     -t "ghcr.io/vivektiwary-25/aetherstream-${service}:ci" .
done
export TF_VAR_image_tag=ci
terraform -chdir=infra/terraform init
terraform -chdir=infra/terraform apply
curl --fail http://localhost:18080/api/ready
terraform -chdir=infra/terraform destroy
```

`.github/workflows/ci-terraform.yml` checks formatting, initializes and validates,
builds those tags into the runner's Docker daemon, applies the real infrastructure,
requires readiness JSON `status == UP`, checks the web proxy and three registered
workers, and always attempts destroy (including after a partial apply failure).
A green `ci-terraform` job proves apply, readiness and destroy, not just syntax.

## Mapping to Compose

- Dedicated `aether-terraform` network with Compose DNS aliases (`kafka`,
  `postgres`, `minio`, `coordinator`, `simulator`, `control-api`, `web`). All
  container/volume names are prefixed `aether-terraform-`, not Compose resources.
- Three named data volumes: Kafka, Postgres, MinIO. Destroy removes them; never
  point this demo at production data or import Compose volumes.
- Same pinned infrastructure images and KRaft settings as `docker-compose.yml`.
  Kafka init creates `truck-events` with six partitions and replication one;
  MinIO init creates `aether-checkpoints`. Both one-shots are awaited and their
  exit codes must be zero before dependent services start.
- Postgres receives the unmodified `infra/db/schema.sql` via `upload` before
  startup. As in Compose, entrypoint SQL runs only on an empty database volume;
  this is not a schema-migration mechanism.
- Coordinator and three counted workers share the engine image. Workers use
  `/app/bin/worker` and IDs `worker-1` through `worker-3`. Shared environment,
  simulator defaults, checkpoint interval and health-gated startup match Compose.
- Web serves the production build and proxies `/api` and `/ws` to control-api.
  Optional Compose monitoring-profile services are not part of this demo path.
- Contract host ports are defaults: web 8088, API 18080, coordinator 18081,
  simulator 18070, Kafka 19092, Postgres 15432, MinIO 19000/19001. All bind to
  `127.0.0.1`; do not expose demo credentials on a public interface.

Stop Compose before apply, or override the complete `host_ports` object (for
example through `TF_VAR_host_ports` JSON) with distinct free ports. Kafka's external
advertised listener follows the chosen Kafka host port. This configuration supports
one Terraform demo per daemon; changing ports alone does not create a second one.

`pg_password` is sensitive but Terraform state and saved plans contain credentials.
MinIO uses the contract's dev-only `aether` / `aether-secret` credentials. Protect
local state and plans; never commit `.terraform/`, `*.tfstate*`, `*.plan`, secret
`*.tfvars` or credentials. Do not upload state as a CI artifact. Preserve local state
until destroy succeeds; deleting it is not teardown. Only the files listed in the
task are source changes; generated provider locks/state stay local to the run.
