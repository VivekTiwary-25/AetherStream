# Boss STATE (single source of truth)

Updated: 19 Nov 02:06 IST. Milestone: M1 engine proof DONE in CI; waiting for first successful deploy; M2 (07:30) crash recovery next.

## Plain-language update for Vivek (newest first)
- 02:00 M1 engine proof: GitHub Actions ran the real engine (coordinator + 3 workers, Kafka, Postgres, MinIO) on a fixed
  truck workload and compared with the single-process reference: 407 / 407 results match (EngineMatchesReferenceIT,
  ci-integration on task branch t261119-0132-e746). Images are published to GHCR by commit SHA. First automatic deploy to
  vivek-pc failed on a Windows shell detail (WSL bash) - fixed, re-running now.
- 01:24 Vivek asked: keep STATE fresh every loop; pre-queue the next task behind every busy worker. Done; doing it each loop.
- 01:12 Contracts, Gradle + web scaffolds, compose infra (Kafka/Postgres/MinIO, proven in CI), Dockerfiles, compiler,
  operator runtime, coordinator, simulator, control-api core and the first city UI are merged. Deploy runner
  `vivek-pc-deploy` is online. cloudflared at C:\Users\Arena\bin\cloudflared.exe.

## Task board (02:00)
done (merged): T01-T22 (all), T27 T29 T33 T35 T36 · reviews R01 PASS, R02 (blocker fixed), R03 (1 minor -> T39)
running -> queued next:
- vivek-pc/codex-sol: T39 worker hardening + metrics (01:47)
- vivek-pc/codex-luna: T42 checkpoint/recovery timeline panel (01:51)  [T31 merged 02:04]
- vivek-pc/grok: T24 architecture doc
- utkarsh-pc/codex-sol: T25 /api/compare (01:41) -> T23 crash/fencing ITs (M2 critical)
- utkarsh-pc/codex-luna: T43 runbook (01:51)  [R04 merged: only blocker = compare endpoint, T25 in progress]
- upendra-pc/codex-sol: T41 simulator scenarios (01:47)
- upendra-pc/codex-luna: T37 UI states + a11y (01:45)
- vimlesh-pc/claude: T30 city polish (01:21, long)
- OUT: vimlesh-pc codex-luna + codex-sol (reset 04:49) · vivek-pc/vachana-claude (reset 03:10)
ready: T26 UI integration (needs T30) · after T23: savepoints, rescale button UI, late-event watermark view

## Lanes
1 engine: coordinator merged; worker T14 running · 2 compiler/reference: ops+compiler merged; T13 running ·
3 control-api: core merged; T15, T18 running · 4 simulator: merged · 5 city: v1 merged; T30 polish running ·
6 app: scaffold merged; T11, T19, T20 running · 7 infra: compose infra + Dockerfiles merged; T16, T21 in review; T29 running ·
8 tests: T17 running · 9 docs: ux merged; T24, T27 queued

## Last integration result
02:00 CI (task branch of T22): EngineMatchesReferenceIT PASSED - 407/407 match, 9,748 records replayed, checkpoint 1.
02:00 CD: publish to GHCR OK; deploy job failed (bash.exe = WSL); fixed to Git Bash, rerun pending.
ci-e2e (compose demo script) still continue-on-error; last known failure: control-api not ready.

## Deploy
Runner: vivek-pc-deploy (label aether-deploy), Scheduled Task "AetherStream-Deploy-Runner" at logon. Public link: none yet.

## Open problems
- Local docker builds need proxy build args (see DECISIONS 01:48).
- Manual conflict merges: run `swarm reject <id>` afterwards or the swarm re-queues the task.
- R03: TaskRuntime pendingBarriers unbounded (MINOR in practice) — fold into a later worker task.
- Workers can't compile locally; boss patches tiny CI compile errors on the branch.
