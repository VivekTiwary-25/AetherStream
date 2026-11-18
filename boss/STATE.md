# Boss STATE (single source of truth)

Updated: 19 Nov 01:12 IST. Milestone: M1 (due 02:30) — smallest working slice.

## Plain-language update for Vivek (newest first)
- 01:12 Contracts written (docs/contracts/), Gradle + web scaffolds on main, compose infra (Kafka/Postgres/MinIO) proven
  in CI, Dockerfiles for all 4 images merged. Self-hosted deploy runner `vivek-pc-deploy` is online. cloudflared is
  downloaded (C:\Users\Arena\bin\cloudflared.exe). 8 workers are building the engine core, compiler, simulator, API and UI.

## Task board (01:16)
done (merged): T01 compose infra · T02 web scaffold · T03 ops PartitionPipeline · T04 compiler · T05 simulator ·
  T06 coordinator · T07 control-api core · T08 Dockerfiles · T12 docs/ux
running / queued:
- T14 worker runtime — vivek-pc/vachana-claude (01:10)
- T13 reference executor — utkarsh-pc/codex-sol (01:10)
- T15 api pipelines + start execution — vivek-pc/codex-sol (01:10)
- T16 compose app services — upendra-pc/codex-sol (01:12)
- T17 integration base ITs — upendra-pc/codex-luna (queued) then T20 chaos+compare UI
- T10 city UI — moved to upendra-pc/codex-luna (vimlesh claude logged out)
- T11 API client + panels — vivek-pc/codex-luna
- T18 control-api WebSocket — vimlesh-pc/codex-sol (01:11)
- T21 CD workflow — vimlesh-pc/codex-luna (01:11)
- T19 pipeline editor — utkarsh-pc/codex-luna (01:13)
- T09 mock server -> R01 review ops+coordinator -> T24 architecture doc — vivek-pc/grok (serial)
ready next (write when deps merge):
- T22 E2E M1 test: simulator records -> Kafka -> coordinator + 3 in-process workers -> Postgres == reference (after T13, T14)
- T23 kill-worker recovery test (in-process crash) + compose chaos script (after T22)  [M2]
- T25 control-api /api/compare endpoint (after T13)
- T26 UI integration: mount editor, chaos, compare, city on shared API client (after T10, T11, T19, T20)
- T27 README.md + DEMO.md (after T16/T21)
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
