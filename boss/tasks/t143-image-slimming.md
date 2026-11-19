# T143: Smaller, safer Docker images (multi-stage, non-root, healthchecks)  [DevOps]

Lane 7. For each Dockerfile under infra/docker/ (or wherever docker-compose.yml builds from): use multi-stage builds (build
stage separate from runtime), a slim JRE base (eclipse-temurin:21-jre or a jlink-built runtime) for Java services, nginx/alpine
for web, run as a non-root user, keep the existing HEALTHCHECKs or add ones that match docker-compose.yml, add OCI labels
(org.opencontainers.image.source / revision via build arg). Record before/after image sizes from CI (docker image ls in the
ci-images log) in docs/report/docker-images.md (new). Do not change ports, env vars or entrypoint behaviour.

Files (edit only these): infra/docker/** (Dockerfiles), web/Dockerfile and other service Dockerfiles if they live elsewhere,
.dockerignore files, docs/report/docker-images.md (new).

Acceptance check: ci-images, ci-compose and ci-e2e green on your branch; sizes table in docs/report/docker-images.md.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql (if a contract looks wrong, say so in your report).
- Do not read or change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only edit the files listed. If you are on vivek-pc: do NOT start Docker containers or kind there (the live demo runs on it). Acceptance runs in GitHub Actions on your task branch.
