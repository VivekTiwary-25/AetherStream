# Boss STATE (single source of truth)

Updated: 19 Nov 01:37 IST. Milestone: M1 (due 02:30) — sim -> Kafka -> engine -> Postgres == reference, in CI.

## Plain-language update for Vivek (newest first)
- 01:24 Vivek asked: keep STATE fresh every loop; pre-queue the next task behind every busy worker. Done; doing it each loop.
- 01:12 Contracts, Gradle + web scaffolds, compose infra (Kafka/Postgres/MinIO, proven in CI), Dockerfiles, compiler,
  operator runtime, coordinator, simulator, control-api core and the first city UI are merged. Deploy runner
  `vivek-pc-deploy` is online. cloudflared at C:\Users\Arena\bin\cloudflared.exe.

## Task board (01:37)
done (merged): T01 compose infra · T02 web scaffold · T03 ops · T04 compiler · T05 simulator · T06 coordinator ·
  T07 control-api core · T08 Dockerfiles · T09 mock server · T10 city v1 · T11 API client+panels · T12 docs/ux ·
  T14 worker runtime · T16 compose app services · T17 integration base (boss fixed init) · T18 WebSocket · T19 pipeline editor ·
  T20 chaos+compare UI · T21 CD workflow · R02 review (its BLOCKER already fixed by T16)
running -> queued next on same worker:
- utkarsh-pc/codex-sol: T13 reference executor (01:10, long) -> (T25 compare after T13 merges)
- vivek-pc/codex-sol: T15 api pipelines (01:10) -> T22 E2E engine==reference IT (M1 critical)
- vivek-pc/codex-luna: T31 coordinator rejoin/rebalance + metrics (from vachana)
- vivek-pc/grok: R01 review ops+coordinator -> T24 architecture doc
- utkarsh-pc/codex-luna: T27 README + DEMO
- upendra-pc/codex-sol: T29 compose e2e chaos script + ci-e2e
- upendra-pc/codex-luna: T33 report + presentation docs
- vimlesh-pc/codex-luna (21% left): R03 review worker runtime
- vimlesh-pc/codex-sol: idle -> needs task
- vimlesh-pc/claude: T30 city polish
- vivek-pc/vachana-claude: OUT (5-hour limit, resets 03:10) -> then T23 recovery ITs / hard engine work
ready (waiting on a dependency): T23 crash/fencing ITs (needs T22) · T25 /api/compare (needs T13) · T26 UI integration (needs T30)

## Lanes
1 engine: coordinator merged; worker T14 running · 2 compiler/reference: ops+compiler merged; T13 running ·
3 control-api: core merged; T15, T18 running · 4 simulator: merged · 5 city: v1 merged; T30 polish running ·
6 app: scaffold merged; T11, T19, T20 running · 7 infra: compose infra + Dockerfiles merged; T16, T21 in review; T29 running ·
8 tests: T17 running · 9 docs: ux merged; T24, T27 queued

## Last integration result
01:30 local: ./gradlew :tests:integrationTest on vivek-pc Docker -> Postgres repo, S3 store, Kafka offsets ITs PASSED.
01:37 first CD run on main started (publish to GHCR + deploy on vivek-pc-deploy) — result pending.

## Deploy
Runner: vivek-pc-deploy (label aether-deploy), Scheduled Task "AetherStream-Deploy-Runner" at logon. Public link: none yet.

## Open problems
- Workers can't compile locally -> first-CI compile errors; boss patches tiny ones on the branch (reject would delete work).
- Workers refuse to write swarm/reports (AGENTS.md); swarm falls back to last output and flags "failed" — cosmetic, judge by CI.
- Actions concurrency: many runs queue; path filters + cancel-in-progress added to ci-java/ci-web/ci-images.
