# Boss decisions (one line each: what — why)

- 00:40 Renamed "handoff (2).md" to handoff.md and committed — prompt says handoff.md is the engine source of truth.
- 00:42 Pinned Spring Boot 4.1.2, kafka-clients 4.3.2, Jackson 2.22.4, Testcontainers 2.0.5, Gradle 9.6.0, Java 21 — newest stable versions checked on Maven Central; Testcontainers 2.x needed for Docker 29.
- 00:44 Object store = pgsty/minio (community MinIO fork, same S3 API) — official minio/minio images are no longer published (pull fails).
- 00:45 Operator semantics live once in :compiler (ops package), used by engine AND reference — guarantees same function; engine adds distribution/checkpoints.
- 00:45 Coordinator<->worker protocol = HTTP/JSON with commands piggybacked on heartbeat replies (not gRPC) — no worker-side server needed, fast to build, still versioned.
