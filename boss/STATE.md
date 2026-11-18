# Boss STATE (single source of truth)

Updated: 19 Nov 01:24 IST. Milestone: M1 (due 02:30) — sim -> Kafka -> engine -> Postgres == reference, in CI.

## Plain-language update for Vivek (newest first)
- 01:24 Vivek asked: keep STATE fresh every loop; pre-queue the next task behind every busy worker. Done; doing it each loop.
- 01:12 Contracts, Gradle + web scaffolds, compose infra (Kafka/Postgres/MinIO, proven in CI), Dockerfiles, compiler,
  operator runtime, coordinator, simulator, control-api core and the first city UI are merged. Deploy runner
  `vivek-pc-deploy` is online. cloudflared at C:\Users\Arena\bin\cloudflared.exe.

## Task board
done (merged): T01 compose infra · T02 web scaffold · T03 ops PartitionPipeline · T04 compiler · T05 simulator ·
  T06 coordinator · T07 control-api core · T08 Dockerfiles · T10 city v1 · T12 docs/ux
waiting review: T16 compose app services (ci-compose running) · T21 CD (cd lint fails only on node20 action deprecation -> boss bumps action versions on main after T16 merges)
running (worker, started) -> queued next on the same worker:
- vivek-pc/vachana-claude: T14 worker runtime (01:10) -> T31 coordinator rejoin/rebalance + metrics
- vivek-pc/codex-sol: T15 api pipelines + start execution (01:10) -> (T25 compare once T13 merges)
- vivek-pc/codex-luna: T11 API client + panels (01:04) -> R02 review infra+web
- vivek-pc/grok: T09 mock server (00:54, slow) -> R01 review ops+coordinator -> T24 architecture doc
- utkarsh-pc/codex-sol: T13 reference executor (01:10) -> (T22 E2E once T14 merges)
- utkarsh-pc/codex-luna: T19 pipeline editor (01:13) -> T27 README + DEMO
- upendra-pc/codex-sol: T29 compose e2e chaos script + ci-e2e (01:20)
- upendra-pc/codex-luna: T20 chaos panel + compare view (01:13)
- vimlesh-pc/codex-sol: T18 control-api WebSocket (01:11)
- vimlesh-pc/codex-luna: T17 integration base ITs (01:16)
- vimlesh-pc/claude: T30 city polish (01:21)
ready (written, waiting on a dependency):
- T22 E2E engine == reference IT (needs T14, T17) · T23 crash/fencing/incomplete-checkpoint ITs (needs T22)
- T25 /api/compare (needs T13) · T26 UI integration (needs T11, T19, T20, T30)
ideas next: savepoints, Prometheus+Grafana in compose, late-event watermark UI, rescale button, report/presentation docs.

## Lanes
1 engine: coordinator merged; worker T14 running · 2 compiler/reference: ops+compiler merged; T13 running ·
3 control-api: core merged; T15, T18 running · 4 simulator: merged · 5 city: v1 merged; T30 polish running ·
6 app: scaffold merged; T11, T19, T20 running · 7 infra: compose infra + Dockerfiles merged; T16, T21 in review; T29 running ·
8 tests: T17 running · 9 docs: ux merged; T24, T27 queued

## Last integration result
none end-to-end yet. CI: compose infra smoke green (T01); main builds (`./gradlew build` local 01:05 OK).

## Deploy
Runner: vivek-pc-deploy (label aether-deploy), Scheduled Task "AetherStream-Deploy-Runner" at logon. Public link: none yet.

## Open problems
- Workers can't compile locally -> first-CI compile errors; boss patches tiny ones on the branch (reject would delete work).
- Workers refuse to write swarm/reports (AGENTS.md); swarm falls back to last output and flags "failed" — cosmetic, judge by CI.
- Actions concurrency: many runs queue; path filters + cancel-in-progress added to ci-java/ci-web/ci-images.
