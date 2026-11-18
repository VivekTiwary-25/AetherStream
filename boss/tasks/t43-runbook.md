# T43: Operations runbook (docs/runbook.md)

Lane 9 (docs). Read docker-compose.yml, infra/deploy/*, infra/scripts/*, .github/workflows/*.yml, docs/contracts/03 and 04
on main. Write a practical runbook the team can follow under pressure on demo day. Plain, simple English, copy-paste commands
(Windows Git Bash + Docker Desktop on the demo PC; note PowerShell equivalents where different).

Files (create only this):
- docs/runbook.md

Sections: 1) Start / stop the whole stack (dev build vs deployed images, the `-p aether` project used by CD), 2) Health checks
(/api/health, /api/ready, /coord/state, compose ps) and what each failing check means, 3) Start the demo pipeline via API
(curl), 4) Chaos by hand (late events, slow sink, kill a worker via API and via `docker compose kill worker-2`) and what
"good" looks like, 5) Recovery troubleshooting (execution stuck RECOVERING, no COMPLETED checkpoint, MinIO bucket missing,
Kafka topic missing), 6) Deploy and roll back (push to main -> cd.yml; manual workflow_dispatch with a previous SHA;
infra/deploy/deploy.sh by hand), 7) Public link (cloudflared quick tunnel: `cloudflared tunnel --url http://localhost:8088`,
the link changes each restart), 8) Monitoring profile (Prometheus/Grafana), 9) Reset everything (down -v, what data is lost),
10) Known limits.

Acceptance check: file exists, every command uses the real service names/ports from docker-compose.yml.

## Rules
- Do not change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only create docs/runbook.md.
