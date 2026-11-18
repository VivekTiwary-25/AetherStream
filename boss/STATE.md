# Boss STATE (single source of truth)

Updated: 19 Nov 02:18 IST. M1 DONE. M2 (07:30) mostly proven: compose demo in CI kills a worker and recovers; still need compare (T25) + crash ITs vs reference (T23).

## Plain-language update for Vivek (newest first)
- 02:18 The full-system demo now runs automatically in GitHub Actions (ci-e2e): start the stack, run the pipeline, send late
  events, kill worker-3 (recovered in 7 s from a checkpoint), slow the sink (backpressure, then drains), check the deployed
  version. Savepoints merged. vivek-pc ran low on memory; freed build cache and capped JVM heaps in compose.
- 02:04 M1 DONE. What works now (all run by the boss, not just claimed):
  * Push to main -> GitHub Actions builds + publishes 4 images to GHCR by commit SHA -> deploys on vivek-pc via the
    self-hosted runner -> smoke test PASS (deployed commit 6fb5171 = /api/version).
  * Live deployed stack: 10 containers healthy; demo pipeline started via the API; 3 workers processing ~40 events/s;
    215+ one-minute windows written; checkpoints every 10 s.
  * Kill-worker live: worker-1 crashed -> ~5 s later generation 1->2, restored from checkpoint #18, partitions moved to
    worker-2/3, checkpoints continued (#20).
  * Public link: https://trance-cover-acrobat-submissions.trycloudflare.com (quick tunnel; changes if PC restarts).
  * Bug found live and fixed: /api/results/windows without sinkId crashed (typed NULL) - redeploying.
- 02:00 M1 engine proof: GitHub Actions ran the real engine (coordinator + 3 workers, Kafka, Postgres, MinIO) on a fixed
  truck workload and compared with the single-process reference: 407 / 407 results match (EngineMatchesReferenceIT,
  ci-integration on task branch t261119-0132-e746). Images are published to GHCR by commit SHA. First automatic deploy to
  vivek-pc failed on a Windows shell detail (WSL bash) - fixed, re-running now.
- 01:24 Vivek asked: keep STATE fresh every loop; pre-queue the next task behind every busy worker. Done; doing it each loop.
- 01:12 Contracts, Gradle + web scaffolds, compose infra (Kafka/Postgres/MinIO, proven in CI), Dockerfiles, compiler,
  operator runtime, coordinator, simulator, control-api core and the first city UI are merged. Deploy runner
  `vivek-pc-deploy` is online. cloudflared at C:\Users\Arena\bin\cloudflared.exe.

## Task board (02:09)
done (merged): T01-T22, T24, T27, T29, T30, T31, T33, T35, T36, T39, T41, T43, T44 · reviews R01 R02 R03 R04
running -> queued next on the same worker:
- utkarsh-pc/codex-sol: T25 /api/compare -> T23 crash/fencing/incomplete-checkpoint ITs (M2 critical)
- vivek-pc/codex-sol: T54 Kubernetes + kind CI  [T48 merged: ci-e2e green, now blocking]
- upendra-pc/codex-sol: T55 Terraform IaC (in review, ci-terraform running)  [T45 savepoints merged]
- vivek-pc/codex-luna: T42 checkpoint timeline panel -> T52 scenario panel
- upendra-pc/codex-luna: T37 UI states + a11y -> T51 api scenario/metrics proxies
- utkarsh-pc/codex-luna: T47 example pipelines
- vivek-pc/grok: R05 review integration tests
- vimlesh-pc/claude: T26 UI INTEGRATION (one shell, one socket)
- OUT: vimlesh-pc codex x2 (reset 04:49) · vachana-claude (reset 03:10) -> give vachana T23 follow-ups / hard engine work
next ideas: mount timeline + scenario panels (after T26), compare UI wiring check, rescale button, late-event view,
  report/presentation polish, Grafana screenshots.

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
Runner: vivek-pc-deploy (labels self-hosted, aether-deploy), Scheduled Task "AetherStream-Deploy-Runner" at logon.
Deployed: compose project `aether` on vivek-pc, web http://localhost:8088, API :18080. Last good deploy 6fb5171 (02:04).
Public link: https://trance-cover-acrobat-submissions.trycloudflare.com (Scheduled Task "AetherStream-Tunnel" runs
C:/Users/Arena/bin/aether-tunnel.cmd; current URL is in C:/Users/Arena/aether-tunnel.log - changes on every restart).

## Open problems
- vivek-pc memory is tight (Docker VM ~8 GB). Do NOT build the full stack locally; CI builds, CD deploys. JVM heaps capped.
- Background wait loops can be killed by the memory reaper - re-check state with foreground commands after a kill.
- Local docker builds need proxy build args (DECISIONS 01:48).
- Manual conflict merges: run `swarm reject <id>` afterwards or the swarm re-queues the task.
