# Engine benchmark

Run from the repository root with Java 21 and a running Docker daemon:

```sh
./gradlew :tests:benchmarkTest --console=plain
```

The task uses the test source set and dependencies, a fresh fork per test class, a 2 GiB heap, and the shared database schema. It selects the `benchmark` JUnit tag; ordinary `test` excludes both `integration` and `benchmark`. Benchmark measurements are never considered up to date.

## Workload and environment

Exactly 200,000 input records are generated and produced to Kafka before execution timing starts. The base is `WorkloadGenerator` with seed 42, 16 trucks, and 40 simulated minutes, including duplicate, late, and malformed records. It is extended to exactly 200,000 records using `Simulation` with seed 43, 64 trucks, and two-second simulation steps, with event times after the base's 40-minute interval.

The benchmark uses six Kafka partitions and three in-JVM workers, with real Kafka, PostgreSQL, and MinIO Testcontainers. Checkpoints run every two seconds. This is a single CI runner experiment, not a distributed capacity test. There is no performance SLA; results depend on the runner, JVM, Docker, and backing services.

## Measurement definitions

- **Baseline throughput:** 200,000 records divided by elapsed monotonic time from the execution request until observation of a completed checkpoint whose next offsets cover every input end offset. Completion is polled every 100 ms, so the measurement includes startup, checkpoint completion, and observation delay; it is not a source-only processing rate. Input generation and production are outside this interval.
- **End-to-end window latency:** replay the operators separately in each source partition's offset order to identify the actual record that triggers each emitted window. Measure the PostgreSQL window row's `written_at` minus that triggering record's producer-send wall-clock time. This is not event-time lag or latency from the last record absorbed by the window. Report p50, p95, and p99 using nearest rank: sorted sample index `ceil(p * n)`, one-based.
- **Checkpoint duration:** p50 and p95 of persisted checkpoint durations for completed, positive-ID checkpoints of the baseline execution only. Checkpoint zero, pending/aborted checkpoints, and recovery-execution checkpoints are excluded. Percentiles use nearest rank.
- **Recovery time:** a separate execution replays the same pre-produced log from earliest offsets. A 1 ms sink delay creates backlog. After a completed checkpoint exists, input remains, and outputs have been persisted, stop one worker's runtime with non-graceful `TaskRuntime.stop` and cancel its heartbeat. This is an in-JVM worker-failure simulation: it does not halt the process and does not submit a new checkpoint acknowledgement for the stopped runtime. Surviving workers have their sink delay reset to zero. Measure monotonic elapsed time from crash initiation until the database execution is observed `RUNNING` in a newer generation, polling every 100 ms. This includes failure detection, restore, reassignment, and observation delay, not backlog-drain time.
- **Correctness:** compare both the completed baseline and completed recovered execution against the deterministic reference replay. Compare window values and counts, alerts including their details, and dead letters including their details. Recovery timing is not a substitute for verifying the final recovered outputs.

## Reports and CI

Reports are written at the repository root, not under `tests/build`:

- `build/reports/benchmark/report.md`
- `build/reports/benchmark/report.json`

Reports contain measured runtime results, machine/runtime configuration, status, errors, and available partial information when a run fails. Missing measurements are unavailable, not zero or invented estimates. Actual CI numbers are pending a benchmark run; this README publishes no fabricated metrics.

The `bench` GitHub Actions workflow runs on manual dispatch, relevant pushes to `main` and `task/**`, and pull requests to `main`. It runs the command above on Ubuntu with Temurin 21, always appends the Markdown report to the job summary (or explicitly reports that it is unavailable), and always attempts to upload the root benchmark reports plus benchmark test reports/results. A failing run remains a failing job even when partial artifacts are available.
