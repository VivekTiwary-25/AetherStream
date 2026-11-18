# T55: Infrastructure as Code with Terraform (docker provider) + CI validation

Lane 7 (infra). The CDD syllabus covers IaC with Terraform. Provision the AetherStream demo environment with Terraform using
the kreuzwerker/docker provider (no cloud account, no cost): network, volumes and the containers of the compose stack, using
the GHCR images by SHA (variable `image_tag`). Docker Compose remains the main demo path; this is a parallel, working IaC path.

Files (create only these):
- infra/terraform/versions.tf (terraform >= 1.6, kreuzwerker/docker ~> 3.x pinned), providers.tf, variables.tf (image_tag,
  registry prefix default ghcr.io/vivektiwary-25/aetherstream-, host ports, pg_password sensitive with a dev default),
  network.tf, volumes.tf, kafka.tf (+ topic init container), postgres.tf (schema from infra/db/schema.sql via upload),
  minio.tf (+ bucket init), app.tf (coordinator, worker x3 via count, simulator, control-api, web), outputs.tf (urls),
  README.md (init/plan/apply/destroy, how it maps to compose)
- .github/workflows/ci-terraform.yml

ci-terraform.yml: on push to main and task/** (paths: infra/terraform/**, the workflow) and pull_request; ubuntu-latest;
hashicorp/setup-terraform; `terraform fmt -check -recursive`, `terraform init`, `terraform validate`, then a real
`terraform apply -auto-approve -var image_tag=<a tag that exists>` against the runner's Docker using locally built images
(build the 4 images from infra/docker with tags matching the registry prefix + `ci`, and set image_tag=ci with
`keep_locally = true` / no pull), wait for http://localhost:18080/api/ready == UP, then `terraform destroy -auto-approve`.
Use current action majors (actions/checkout@v7). Timeout 30 min.

Acceptance check: `ci-terraform` green on your branch with apply + ready check + destroy.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql.
- Do not change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only create/edit the files listed under "Files". Do not commit real secrets or terraform state.
- In your result, list what you built, how the acceptance check proves it, and anything left undone.
