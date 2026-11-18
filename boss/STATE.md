# Boss STATE (single source of truth)

Updated: 19 Nov 02:52 IST. M1 DONE. M2 (07:30): waiting only on T23 crash ITs vs reference. M3 (12:30): UI integration T26 running.

## Plain-language update for Vivek (newest first)
- 02:52 On the live deployed system (auto-deployed by CD, commit 8c17b21): "Compare with reference" says 159 / 159 results
  match (checkpoint #63, 26,959 events replayed). Kubernetes (kind) CI is green: deploys all services, kills a worker pod,
  recovers. Merged: checkpoint timeline panel, scenario/metrics API, example pipelines API, UI states + accessibility pass.
- 02:30 Stricter correctness test (16 trucks, all 6 partitions, 3 workers): 811 / 811 results match the reference and
  rejected/late events match exactly (25/25). "Compare with reference" endpoint merged. Terraform IaC merged (CI does a real
  apply + ready check + destroy). Savepoints merged.
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

## Task board (02:52)
done (merged): T01-T22, T24, T25, T27, T29-T31, T33, T35-T37, T39, T41-T45, T47, T48, T51, T54, T55, T57-T60 · R01-R06
running -> queued next:
- utkarsh-pc/codex-sol: T23 crash/fencing/incomplete-checkpoint ITs (M2 critical)
- utkarsh-pc/codex-luna: T61 editor example picker
- upendra-pc/codex-sol: T63 benchmark harness
- upendra-pc/codex-luna: T26 UI INTEGRATION + R06 fixes (M3 critical; moved off vimlesh-pc after its push failure)
- vivek-pc/codex-sol: T65 Playwright UI e2e vs real stack
- vivek-pc/codex-luna: T52 scenario panel
- vivek-pc/grok: T66 docs refresh
- vimlesh-pc/claude: idle but its last push failed - needs `swarm fix` on vimlesh-pc (in NEEDS_VIVEK)
- OUT: vimlesh-pc codex x2 (reset 04:49) · vachana-claude (reset 03:10) -> next: final UI polish pass after T26
## Lanes
1 engine: coordinator merged; worker T14 running · 2 compiler/reference: ops+compiler merged; T13 running ·
3 control-api: core merged; T15, T18 running · 4 simulator: merged · 5 city: v1 merged; T30 polish running ·
6 app: scaffold merged; T11, T19, T20 running · 7 infra: compose infra + Dockerfiles merged; T16, T21 in review; T29 running ·
8 tests: T17 running · 9 docs: ux merged; T24, T27 queued

## Last integration result
02:52 live deployed stack (8c17b21): POST /api/compare -> 159/159 match, checkpoint 63.
02:27 CI: EngineMatchesReferenceIT 811/811 match + dead letters 25/25 equal (16 trucks, 6 partitions, 3 workers).
02:10 CI ci-e2e (full compose): kill worker-3 -> recovered in 7 s; late events, backpressure, version checks PASS.
ci-k8s (kind) green; ci-terraform (apply/ready/destroy) green.

## Deploy
Runner: vivek-pc-deploy (labels self-hosted, aether-deploy), Scheduled Task "AetherStream-Deploy-Runner" at logon.
Deployed: compose project `aether` on vivek-pc, web http://localhost:8088, API :18080. Last good deploy 8c17b21 (02:50); earlier 6fb5171 (02:04).
Public link: https://trance-cover-acrobat-submissions.trycloudflare.com (Scheduled Task "AetherStream-Tunnel" runs
C:/Users/Arena/bin/aether-tunnel.cmd; current URL is in C:/Users/Arena/aether-tunnel.log - changes on every restart).

## Open problems
- vivek-pc memory is tight (Docker VM ~8 GB). Do NOT build the full stack locally; CI builds, CD deploys. JVM heaps capped.
- Background wait loops can be killed by the memory reaper - re-check state with foreground commands after a kill.
- Local docker builds need proxy build args (DECISIONS 01:48).
- Manual conflict merges: run `swarm reject <id>` afterwards or the swarm re-queues the task.
