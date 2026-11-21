# Fact-check docs/expo/MERGE-UI-GLORIOUS.md against the real deploy files (docs only, light)

Files: docs/expo/MERGE-UI-GLORIOUS.md only. Do NOT read or describe swarm/. This PC has very little disk: no npm install, no builds, no Docker.

Check every claim in docs/expo/MERGE-UI-GLORIOUS.md against the actual files: .github/workflows/cd.yml (what CD does, which images, how many containers, health/smoke steps, workflow_dispatch sha input for rollback), infra/scripts/demo-freeze.sh (exact subcommands status/on/off and what FREEZE file it uses), infra/scripts/demo-ready.sh, scripts/expo/*, scripts/ai-bridge/start.ps1, web/src/glorious/pipelines/mode.ts. Fix any wrong step, command, path or count with the smallest edit; if a claim cannot be verified, mark it '(check)'. Report a table: claim -> OK/fixed/check.
