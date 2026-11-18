# Boss decisions (one line each: what — why)

- 00:40 Renamed "handoff (2).md" to handoff.md and committed — prompt says handoff.md is the engine source of truth.
- 00:42 Pinned Spring Boot 4.1.2, kafka-clients 4.3.2, Jackson 2.22.4, Testcontainers 2.0.5, Gradle 9.6.0, Java 21 — newest stable versions checked on Maven Central; Testcontainers 2.x needed for Docker 29.
- 00:44 Object store = pgsty/minio (community MinIO fork, same S3 API) — official minio/minio images are no longer published (pull fails).
- 00:45 Operator semantics live once in :compiler (ops package), used by engine AND reference — guarantees same function; engine adds distribution/checkpoints.
- 00:45 Coordinator<->worker protocol = HTTP/JSON with commands piggybacked on heartbeat replies (not gRPC) — no worker-side server needed, fast to build, still versioned.
- 00:50 Wrote :common records and the Gradle wrapper myself — binary wrapper can't be made on worker PCs, and records are the contract; unblocks all Java lanes at once.
- 00:52 One CI workflow file per concern (ci-java, ci-web, ci-integration, ci-compose, ci-images, ci-mock, cd) — lanes never edit the same workflow file.
- 00:53 control-api pipeline endpoints split out of T07 — they need PipelineCompiler (T04, in flight); avoids a branch that can't compile.
- 00:55 Runner: self-hosted "vivek-pc-deploy", label aether-deploy only (no default labels), started by a logon Scheduled Task as Arena; repo set to require approval for ALL external contributors' workflows.
- 00:56 ci-java ignores pushes that only touch swarm/boss/docs, and cancels superseded runs — swarm queue commits were triggering a full build each.
- 00:57 vimlesh-pc/claude is down (no agent): its UI share goes to vachana-claude (city) and codex-luna (panels) until fixed.
- 01:10 Spring Boot 4.1.2 -> 3.5.16 — Boot 4 auto-configures Jackson 3; our :common records/JsonNode are Jackson 2, mixing breaks controller JSON. Fixed T07 compile errors on its branch myself (rejecting would delete the work).
- 01:23 Queue-ahead policy (Vivek's request): every busy worker always has 1 task queued behind it; a pre-queued task must never depend on the task running before it (it branches from main when it starts). STATE.md rewritten every loop.
- 01:30 Manual conflict merges: after pushing the merge, run 'swarm reject <id>' (no requeue) so the swarm drops the task instead of re-queuing it.
- 01:34 Bumped all GitHub Actions to current majors (checkout v7, setup-java v6, setup-node v7, upload-artifact v7, setup-gradle v6, buildx v4, build-push v7) — node20 runtime is deprecated and failed the CD lint.
