# T112: Prove Prometheus + Grafana work in CI  [M4 DevOps]

Lane 7. docker-compose.yml has prometheus (infra/monitoring/prometheus.yml) and grafana (provisioning + dashboards/
aetherstream.json). Nothing proves they work. Add a job to .github/workflows/ci-compose.yml (or a new workflow
.github/workflows/ci-monitoring.yml, same triggers as ci-compose) that starts the stack needed for metrics (compose up --wait of
the engine services + prometheus + grafana; reuse how ci-compose / ci-e2e start the stack), then checks: Prometheus
/api/v1/targets shows the coordinator and every worker target "up"; a PromQL query for an engine metric that exists in
engine/src/main/java/**/WorkerMetrics.java returns data; Grafana /api/health is ok and /api/search finds the AetherStream
dashboard; each dashboard panel query names a metric that Prometheus knows (/api/v1/label/__name__/values). Fix the dashboard JSON
or prometheus.yml if a query or target is wrong (say what). Always tear down; upload logs on failure.

Files (edit only these): .github/workflows/ci-monitoring.yml (new) or .github/workflows/ci-compose.yml, infra/monitoring/**.

Acceptance check: the new job green on your branch with the checks visible in the log.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql (if a contract looks wrong, say so in your report).
- Do not read or change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only edit the files listed. If you are on vivek-pc: do NOT start Docker containers or kind there (the live demo runs on it). Acceptance runs in GitHub Actions on your task branch.
