# ADR 0008: Compose delivery and infrastructure proofs

**Status:** Accepted

## Context

The project needs a runnable multi-service demo, a repeatable deployment path, and infrastructure checks without claiming a production-grade multi-node platform. Compose defines the application stack; Kubernetes/kind and Terraform provide separate deployment proofs (`docs/contracts/00-stack-and-layout.md`, `infra/kubernetes/README.md`, `infra/terraform/README.md`).

## Decision

Use Docker Compose for the baseline deployment. The CD workflow publishes application images to GHCR tagged with the commit SHA and deploys the selected SHA on a self-hosted runner; deployment configuration is in `infra/deploy/docker-compose.deploy.yml` and `infra/deploy/deploy.sh` (`.github/workflows/cd.yml`, `docs/ci-cd.md`). Keep kind/Kubernetes and Terraform as verifiable infrastructure proofs, not as a claim of HA production infrastructure (`infra/kubernetes/README.md`, `infra/terraform/README.md`).

## Consequences

SHA-tagged images identify the deployed build and allow selecting a previously published build for rollback. Deployment depends on GHCR availability and the configured self-hosted runner. Compose, kind, and Terraform validate distinct deployment surfaces and do not imply that the single-node baseline is highly available.

## Alternatives considered

- Deploy mutable `latest` tags: cannot reliably identify or roll back to a specific build.
- Require a managed Kubernetes production cluster: expands operational scope beyond the current demonstration and verification environment.
- Treat kind or Terraform proof runs as the primary deployment: neither replaces the documented Compose deployment flow.
