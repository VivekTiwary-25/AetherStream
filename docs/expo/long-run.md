# Long-run stability

The `ci-longrun` workflow starts the demo stack, checks readiness and reference parity, then samples it every 60 seconds for 60 minutes. It fails on a reference mismatch, checkpoint stagnation longer than 60 seconds, or any container memory increase above 30% from minute 10 to minute 60. The workflow step summary contains the per-minute measurements and ASCII memory trend; the raw per-container CSV is attached as the `longrun-csv-*` artifact.

## Latest run

- Result: pending the first completed `ci-longrun` run on this branch; no measurements are available yet.
- Workflow: [ci-longrun.yml](../../.github/workflows/ci-longrun.yml); link the successful Actions run here after it completes.
- Per-container peak memory: pending the run's CSV artifact.

Do not replace the pending values with estimates. After CI completes, link the successful run above and record each container's observed peak memory here.
