# T140: Kafka consumer lag per partition - metric, API field, Grafana panel  [engine + infra, hard]

Lanes 1/7. Backpressure is a demo moment ("Slow down output" -> amber, bounded queue). Make the lag visible as a number:
1. Worker: expose a Prometheus gauge aether_worker_consumer_lag{partition} = log-end offset - current position, per assigned
   partition (refresh every few seconds without blocking the processing loop; use the consumer's endOffsets / metrics), next to
   the existing metrics in engine/src/main/java/**/worker/WorkerMetrics.java. Unit test for the calculation.
2. Add the per-worker total lag to the worker status the coordinator already reports, only if contract 03 allows an additive
   field; otherwise leave the protocol alone and say so in the report.
3. Grafana: add a "Consumer lag per partition" time-series panel to infra/monitoring/grafana/dashboards/aetherstream.json.
   ci-monitoring checks that every dashboard metric exists - keep it green.

Files (edit only these): engine/src/main/java/io/aetherstream/engine/worker/**, engine/src/test/java/io/aetherstream/engine/worker/**,
infra/monitoring/grafana/dashboards/aetherstream.json.

Acceptance check: ci-java and ci-monitoring green on your branch.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql (if a contract looks wrong, say so in your report).
- Do not read or change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only edit the files listed. If you are on vivek-pc: do NOT start Docker containers or kind there (the live demo runs on it). Acceptance runs in GitHub Actions on your task branch.
