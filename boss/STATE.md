# Boss STATE (single source of truth)

Updated: 19 Nov 08:05 IST. M1 + M2 DONE. M3 (12:30) in progress: T92 shell integration (redo T26) + T93 restored compare running.

## Plain-language update for Vivek (newest first)
- 07:30 Boss back after the 03:51-07:10 usage-limit gap. Reviewed the 10 finished tasks: merged 8 (docs refresh, live
  screenshots + visual QA, engine hardening T81, restore-from-savepoint fix T86, org-scoped access T84, editor examples,
  ops panel, presenter tour); 2 salvaged into new tasks (T75b ITs, T78b soak). Fixed main CI: ci-integration was red since
  03:16 because of a ';' in a schema comment (boss's mistake), and a test race in TaskRuntimeTest. Live stack healthy, public
  link works. 8 new tasks queued (M3 UI integration, restored compare, ITs, soak, mock-off guard, DEMO.md, UX docs, polish).
- 02:57 M2 DONE (4.5 h early). In GitHub Actions: a worker is crashed mid-stream; the coordinator recovers in 6.0 s from
  the last completed checkpoint; afterwards 1,646 / 1,646 results match the single-process reference (WorkerCrashRecoveryIT).
  Also passing: stale-worker fencing (old generation cannot write), interrupted checkpoint is never used for recovery.
  Found a live bug: after each deploy the simulator clock restarts so new events count as "late" and the panels go empty;
  urgent fix T69 running. Benchmark harness + Playwright browser test merged.
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

## Task board (08:05)
merged since 07:10 (23): T66 T91 T81 T86 T84 T61 T72 T80 T99 T93 T92 T95 T101 T97 T100 T104 T107 T109 T103 T102 (city) T110
  (RECOVERED durationMs) T89 (coverage; boss hand-merge + pipefail fix)
waiting review (CI running): T78c soak restart+rejoin (ci-soak) · T112 Prometheus+Grafana (ci-monitoring)
running / queued:
- vivek-pc/vachana-claude: T98 Claude polish pass · vivek-pc/codex-sol: T118 control-api contract tests
- vivek-pc/codex-luna: T111 test catalogue · vivek-pc/grok: T108 README final pass
- upendra-pc/codex-sol: T116 CD runs demo-ready.sh (verify on main after merge!) · upendra-pc/codex-luna: T119 syllabus mapping
- utkarsh-pc/codex-sol: T75b ITs + split ci-integration (35m+, split if >60m) · utkarsh-pc/codex-luna: T117 web API tests
- vimlesh-pc/claude: T115 visual QA round 2 (docs) · codex-sol: T114 simulator tests · codex-luna: T113 demo-day runbook
next: T115 P1 items -> a Claude fix task after T98 merges. M4: FINAL.md at 14:00.
deployed: 44dfe07 (07:58). ci-ui-e2e on main keeps getting cancelled by new merges (cancel-in-progress) - verified on task branches now (T107).

## Lanes
1 engine: coordinator merged; worker T14 running · 2 compiler/reference: ops+compiler merged; T13 running ·
3 control-api: core merged; T15, T18 running · 4 simulator: merged · 5 city: v1 merged; T30 polish running ·
6 app: scaffold merged; T11, T19, T20 running · 7 infra: compose infra + Dockerfiles merged; T16, T21 in review; T29 running ·
8 tests: T17 running · 9 docs: ux merged; T24, T27 queued

## Last integration result
07:25 live deployed stack d1714e3: health 200, /api/version = commit, public link 200, 10 containers healthy.
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

## HANDOVER (03:45) - resolved at 07:10 (boss restarted on Vivek's Opus).
RULES kept: after a manual merge push with `git push`; if rejected use `git pull --no-rebase`, never --rebase.
No ';' in SQL comments of infra/db/schema.sql (tests split on ';').
