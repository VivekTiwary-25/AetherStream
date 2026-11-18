# Boss STATE (single source of truth)

Updated: 19 Nov 01:12 IST. Milestone: M1 (due 02:30) — smallest working slice.

## Plain-language update for Vivek (newest first)
- 01:12 Contracts written (docs/contracts/), Gradle + web scaffolds on main, compose infra (Kafka/Postgres/MinIO) proven
  in CI, Dockerfiles for all 4 images merged. Self-hosted deploy runner `vivek-pc-deploy` is online. cloudflared is
  downloaded (C:\Users\Arena\bin\cloudflared.exe). 8 workers are building the engine core, compiler, simulator, API and UI.

## Task board
done (merged): T01 compose infra + smoke CI · T02 web scaffold · T08 Dockerfiles + ci-images
running:
- T03 ops PartitionPipeline — vivek-pc/codex-sol (since 00:53)
- T04 compiler validate+plan — utkarsh-pc/codex-sol (00:53)
- T05 simulator — upendra-pc/codex-sol (00:53)
- T06 coordinator — vivek-pc/vachana-claude (00:53)
- T07 control-api core — done by upendra luna; boss patched compile errors on branch, CI re-running
queued: T09 mock server (grok) · T10 city UI (vimlesh claude) · T11 API client + panels (vivek luna) · T12 docs/ux (utkarsh luna)
ready next (write when deps merge):
- T13 reference executor + Kafka range reader (after T03)
- T14 worker runtime: 3-stage pipeline, barriers, fenced sink (after T03 + T06 storage pkg)
- T15 control-api pipelines endpoints + compare (after T04, T13)
- T16 compose app services + smoke extension (after T05/T07/T14)
- T17 integration test M1: simulator->kafka->engine->postgres == reference (ci-integration.yml)
- T18 WebSocket in control-api
- T19 pipeline editor (xyflow) · T20 chaos panel + compare view
- T21 CD workflow: GHCR push by SHA + deploy on aether-deploy runner + smoke
- T22 kill-worker recovery CI test (M2)
- T23 README/DEMO/architecture docs

## Lanes
1 engine: coordinator running (T06) · 2 compiler/reference: T03, T04 running · 3 control-api: T07 in review ·
4 simulator: T05 running · 5 city: T10 queued · 6 app: scaffold merged, T11 queued · 7 infra: compose+Dockerfiles merged ·
8 tests: not started · 9 docs: T12 queued

## Last integration result
none yet (compose infra smoke green in CI on T01)

## Deploy
Runner: vivek-pc-deploy (label aether-deploy), started by Scheduled Task "AetherStream-Deploy-Runner" at logon.
Public link: not yet (cloudflared ready at C:\Users\Arena\bin\cloudflared.exe).

## Open problems
- vimlesh-pc claude was "no agent" at 00:42; now shows idle. Watch T10.
- Workers cannot compile locally; expect first-CI compile errors. Boss patches tiny ones on the branch, rejects big ones.
