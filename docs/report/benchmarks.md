# Worker-count benchmark

## Evidence status

No GitHub Actions result is claimed here. The remote branch `task/t261119-0803-a752` does not exist (GitHub API returned HTTP 404), and `gh run list --workflow bench.yml --branch task/t261119-0803-a752` returned no runs. This task explicitly prohibits committing and pushing; publishing this checkout is the swarm's responsibility. Consequently there is no branch-run URL or observed GitHub-runner measurement to cite, and the task's green-branch-CI acceptance criterion is not yet satisfied. Results from another branch would not validate this harness.

## Local verification (not CI acceptance)

The corrected local `:tests:benchmarkTest` run passed all six executions (`BUILD SUCCESSFUL in 9m 44s`). The table below is copied from its generated `build/reports/benchmark/report.md`; it is local evidence only, not the required GitHub Actions measurement.

| Workers | Run | Events/s | Checkpoint p50 ms | Checkpoint p95 ms | CP samples | Kill → RUNNING ms | Workers after kill | Reference match |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | baseline | 4042.862 | 964.000 | 4929.000 | 19 | unavailable | unavailable | PASS |
| 1 | recovery | unavailable | 1793.000 | 6750.000 | 17 | 10607.238 | 1 | PASS |
| 2 | baseline | 2624.177 | 1331.000 | 6447.000 | 20 | unavailable | unavailable | PASS |
| 2 | recovery | unavailable | 826.000 | 7676.000 | 11 | 5458.831 | 1 | PASS |
| 3 | baseline | 1927.385 | 2310.000 | 10571.000 | 23 | unavailable | unavailable | PASS |
| 3 | recovery | unavailable | 1500.000 | 15364.000 | 17 | 11294.443 | 2 | PASS |

Observed local machine: Windows 11 / amd64, 16 logical processors, 16,944,848,896 bytes RAM (1,810,567,168 bytes free when recorded); Temurin OpenJDK 21.0.13+9-LTS, 2 GiB test heap and 512 MiB per worker. Measurement timestamps: `2026-11-19T02:51:58.197025600Z` through `2026-11-19T03:00:15.039091400Z`. The source is this unpublished task-branch checkout; the JSON identifies its commit and run URL as `local`. There is no Actions URL.

All three baselines remained in generation 1; all three injected kills recovered from checkpoint 1 into generation 2. Every execution replayed 200,000 records and matched 8,507 reference results, with matching alert payloads and dead letters. More workers decreased observed throughput on this shared local machine; these data do not support a positive scaling claim.

The first local attempt failed after its one-worker recovery when an HTTP heartbeat timeout terminated the replacement JVM. The corrected benchmark process logs transport failures and continues heartbeats, matching production `WorkerMain` behavior; the complete passing run above followed that correction.

## Reproduction and outputs

After the swarm publishes the task branch, run the `bench` workflow on that branch, or let its push trigger run it. The command is:

```sh
./gradlew :tests:benchmarkTest --console=plain
```

The workflow appends the exact `build/reports/benchmark/report.md` table to its step summary and uploads the `benchmark-reports` artifact, including `report.json`, `report.md`, `runner.txt`, worker logs and JUnit results. The JSON records the source commit and Actions run URL. A mismatch, failed recovery or missing full-input checkpoint fails the benchmark rather than producing a successful performance result. Unfinished measurements are reported as unavailable, never zero.

## Method

- Generate and preload exactly 200,000 Kafka records, six partitions, using the seed-42 workload (16 trucks, 40 simulated minutes, including malformed/late/duplicate inputs), extended with seed 43 and 64 trucks. Every execution consumes the same immutable offset range and the demo pipeline from earliest offsets.
- Run worker counts 1, 2 and 3 in that order. Each count gets a fresh coordinator and separate 512 MiB worker JVMs. Kafka, PostgreSQL and MinIO are real Testcontainers services shared on the runner. The coordinator runs in the Gradle test JVM, capped at 2 GiB. Checkpoints are scheduled every 2 seconds.
- **Baseline events/s:** 200,000 divided by monotonic elapsed seconds from the execution-start HTTP request through observation of a completed positive-ID checkpoint covering the complete input. This includes startup, sink writes, checkpoint waiting and polling; it excludes generation, Kafka production, reference replay and SQL comparison. It is end-to-end durable backlog-drain throughput, not producer-to-sink streaming capacity.
- **Checkpoint p50/p95:** persisted `duration_ms` for completed positive-ID checkpoints through that execution's final checkpoint, using nearest rank (`ceil(p*n)`, one-based). Show sample counts and separate baseline/recovery distributions; exclude checkpoint zero and aborted/pending checkpoints.
- **Kill → RUNNING:** a separate recovery execution at each worker count uses a 1 ms sink delay to ensure input remains after its first positive completed checkpoint. Time immediately before `Process.destroyForcibly()` kills an owning worker JVM, bypassing shutdown hooks. Reset sink delay to zero and stop timing only when the execution is observed RUNNING in a newer generation. Poll every 100 ms. Verify restore from a positive completed checkpoint, then drain to a complete checkpoint and compare outputs.
- **Single-worker policy:** after the coordinator declares the killed worker DEAD, start a new worker with a new ID. Recovery time includes failure detection and replacement JVM startup. Two- and three-worker runs use only their one/two surviving workers; they do not start replacements.
- **Results-match:** compare each of the six completed executions with the deterministic reference replay of all 200,000 records. Check window keys, values and counts, alert identities and payloads, and dead-letter payloads/multiplicities. PASS in the table requires all three comparisons, not only a recovered RUNNING state.

## GitHub runner specifications

The workflow selects GitHub-hosted `ubuntu-24.04`, x64, and Temurin Java 21. Actual CPU model, logical CPUs, RAM, disk, kernel, runner image/version, Java and Docker versions are captured from the runner in `runner.txt`; JSON also records observed processors, memory, JVM and heap limits. No CPU/RAM figures from generic runner documentation are substituted for observed machine data. There is currently no branch run from which to report these observations.

## Caveats

This is a single-host shared-resource experiment, not multi-host scalability evidence or a performance SLA. There is one measurement per worker count, no dedicated warmup, no repetitions and no confidence intervals; ordered runs can benefit from service/page caches while each worker JVM still starts cold. Checkpoint cadence and the 100 ms observation interval affect throughput and recovery measurements. Small checkpoint sample counts make p95 a weak tail estimate. Recovery excludes backlog-drain time and includes the normal heartbeat timeout; the one-worker replacement case is not directly comparable to survivor-only recovery. The recovery-only sink delay changes the workload before the kill. The benchmark process entry point uses the real Worker/runtime/protocol but omits production health endpoints and shutdown hooks; it is not a deployment benchmark. Input production and reference execution are outside the throughput timer, and the shared engine/reference operators do not provide an independently implemented semantic oracle.
