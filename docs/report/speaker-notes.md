# Speaker notes

Use the outline's live-show cues; these are spoken notes, not a substitute for the screen or run logs. Each slide's talk track is paced for roughly 45–60 seconds. The stress line is one repository-backed fact; do not add an unverified live number. Examiner answers are two sentences.

## Presenter A — slides 1–3

### 1. Title and what will be shown

**Say:** Welcome. This is AetherStream, a stream-processing engine presented through a live truck-city demo. I’ll show the city and its engine panels, then the pipeline editor, failure controls, and reference comparison. We’ll also look at deployment and test evidence, but I’ll distinguish a live page from a log: a workflow definition is not proof that a run passed. The Operations, City scenarios, and Tour panels are not mounted in this checkout, so those parts are API calls or a narrated walk. First, let’s confirm we are looking at the real page, not mock data.

**Stress:** The dated CI snapshot records `ci-mock` success in run 44440449358.

**Examiner:** How do you know the page is connected to the real system?  
**Answer:** I check that the visible MOCK DATA badge is absent and show the live page. If a live action fails, I identify the log or screenshot used instead of calling it a live success.

### 2. The failure modes the demo hits

**Say:** The demo is organized around four concrete failure cases. First, repeated events are deduplicated within an open keyed window, while an event arriving after its window closes can become a LATE dead letter. Second, a slow sink lets bounded queues fill and blocks upstream work; that is backpressure, not a throughput claim. Third, a worker can stop abruptly, and recovery uses the latest completed checkpoint with a newer generation. Finally, input can be replayed at least once. The exactly-once statement is deliberately narrower: it applies effectively to deterministic Postgres upserts, not every possible sink.

**Stress:** The documented worker queues have capacity 1,000 each.

**Examiner:** Is this equivalent to Flink's guarantees?  
**Answer:** No; we make no Flink-parity claim. The guarantee here is at-least-once input replay, consistent operator state and offsets at completed checkpoints, and effectively exactly-once Postgres upserts.

### 3. Architecture in one picture

**Say:** Follow one event through the system. The simulator emits position and delivery events to Kafka, keyed by truck ID. The compiler checks and plans the pipeline; the engine and the reference executor share the operator semantics, so the comparison uses the same defined function. The coordinator assigns partitions to workers, and checkpoints are stored in MinIO. Results land in Postgres. The control API serves the UI, and nginx proxies the browser's API and WebSocket traffic. The Compose stack is what we will use for the demo; kind and Terraform runs are separate CI evidence, not services I’m starting here.

**Stress:** The integration proof described in `docs/testing.md` uses three workers with Kafka, Postgres, and MinIO.

**Examiner:** Why compare against a reference executor?  
**Answer:** It gives us an independent execution path over the checkpoint's Kafka offset range. The engine and reference share the operator implementation, so this checks distributed execution and persisted results, not an independently implemented operator algorithm.

**Handoff:** That is the system behind the page. Presenter B will show what its live panels and pipeline mean.

## Presenter B — slides 4–6

### 4. City and live panels

**Say:** Here the city is a view of the running workload, and the panels summarize results and worker state. The speed windows and delivery counts come from engine rows; alerts reflect the rule output, and the worker panel reports the runtime. Hovering a truck exposes its speed, route, and partition. The stuck-truck rule is configured around low average speed over consecutive windows, but I’ll only point to a particular truck alert if the corresponding row is visible. The deployed-version footer comes from the API, so we can compare it with the commit shown by the deployment evidence rather than infer a version from the artwork.

**Stress:** The catalogue lists 313 test declarations across Java, web, and Playwright sources.

**Examiner:** Are these panels just a simulation animation?  
**Answer:** No; the panels display engine results and worker state, while the city visualizes truck activity. I can point to the corresponding row or API response on screen rather than treating an animation as proof of processing.

### 5. Pipeline editor

**Say:** The editor exposes the graph the compiler validates. I’ll open Examples and select the deliberately broken logistics example first. Its validation errors identify what needs repair, and Run remains disabled while validation fails. Then I’ll select the valid truck-city pipeline, show its validation result, and explain that Run saves and starts it, replacing any currently running execution. I won’t restart if the existing execution is needed for the alert later in the demo. The point is that the UI does not treat a graph as runnable merely because it can be drawn.

**Stress:** The broken example is expected to produce three independent validation errors.

**Examiner:** What prevents an invalid graph from starting?  
**Answer:** The editor enables Run only after successful validation. The controller also rejects invalid pipelines before saving them, so the client-side button is not the only check.

### 6. Chaos, backpressure, kill, recover

**Say:** I’ll inject late events and inspect the LATE dead-letter tab, then slow the output to create pressure in the bounded queues. The amber backpressure state means a queue has crossed its documented threshold; if the signal is late, I won’t wait indefinitely for it. Next, I’ll confirm a worker kill and watch the recovery banner and worker state. Recovery should name the completed checkpoint and show the generation change. This sequence demonstrates the behavior visible in this run; if a live click fails, I’ll use the cited run log or screenshot and say that I switched to the fallback.

**Stress:** `CoordinatorStressTest` checks invariants across 200 simulated kill, rejoin, and checkpoint cycles.

**Examiner:** Does recovery restore from a partially written checkpoint?  
**Answer:** No; recovery selects the latest COMPLETED checkpoint, not a pending or aborted one. A new generation fences stale workers after reassignment.

**Handoff:** We’ve seen the failure path. Presenter C will check output parity, then separate operational features from deployment evidence.

## Presenter C — slides 7–9

### 7. Compare with reference

**Say:** Now I’ll run Compare with reference and read the result from this click. The comparison replays Kafka records between checkpoint zero and the latest completed checkpoint, then checks reference window results against persisted Postgres rows. A successful heading reports the number matched for this execution; that live number can change, so I won’t borrow a count from another run. If the request fails, I’ll use the documented API call or open the named CI run, and I’ll make clear which evidence we’re looking at.

**Stress:** `ResultComparatorTest` covers window identity, numeric tolerance, and exclusion of open windows.

**Examiner:** Does a matching result prove every possible input is correct?  
**Answer:** No; it proves the compared output for the records and checkpoint range in that execution. The unit and integration tests cover additional defined cases, but they do not establish correctness for every possible workload.

### 8. Savepoint, rescale, scenarios

**Say:** These controls are not mounted in this shell, so I’ll demonstrate them through the documented API calls rather than pretending there are buttons here. Savepoint, restore, rescale, and simulator scenarios are implemented routes. Rescale can return HTTP 409 when the current assignment is already balanced; that response means no rebalance is needed. The extended e2e path is the place to inspect for restore and rescale evidence. A skipped step is not a pass, so I’ll distinguish the configured CI run from any shorter run that skipped those operations.

**Stress:** The test catalogue says CI sets `E2E_EXTENDED=1` for the 11-step demo script.

**Examiner:** Did the ordinary demo prove savepoint restore?  
**Answer:** Not if its log says the extended steps were skipped. The dedicated extended CI path runs those steps, and I would cite its result rather than infer success from the route existing.

### 9. One push: CI, GHCR, vivek-pc, smoke

**Say:** This is a recorded deployment, not an instruction to push during the presentation. I’ll open the cited Actions run and check that both publish and deploy succeeded, then compare the deployed-version footer with the SHA reported by the API. The deploy path publishes immutable SHA-tagged images, runs them on the self-hosted deployment runner, and smoke-checks health plus the reported commit. If the footer and run do not agree, I’ll say so; a different task-branch workflow is not evidence that this deployment completed.

**Stress:** The deployment smoke check requires `/api/version` to report the requested commit SHA as well as health `UP`.

**Examiner:** Does a green task-branch CD run prove deployment?  
**Answer:** No; task-branch CD does not perform the main-branch publish-and-deploy path. Deployment evidence requires the publish and deploy jobs and the smoke check for the requested SHA.

**Handoff:** The deployment is one proof, not the whole test story. Presenter D will cover the remaining evidence and limits.

## Presenter D — slides 10–12

### 10. Containers, Grafana, and the test logs

**Say:** I’ll show the running Compose services and images, then Grafana if its monitoring profile is available. Empty Grafana panels stay empty; I won’t turn a blank graph into a metric. For automated evidence, I’ll open the unit, Testcontainers, e2e, kind, and Terraform runs named in the outline. Each proves a different scope: unit tests do not start the distributed stack, integration tests use real service containers, and the demo e2e exercises Compose. kind and Terraform are CI proofs, not something I’m starting on this machine.

**Stress:** The dated CI snapshot records `ci-java` success for run 44439772370.

**Examiner:** Does `./gradlew build` run the Testcontainers suite?  
**Answer:** No; the build runs module unit tests, while the integration suite is a separate `:tests:integrationTest` task. I’ll identify the job and its run when showing integration evidence.

### 11. Limits, said out loud

**Say:** Before questions, here are the boundaries of the claims. The coordinator is single-active, windows are tumbling, and key-by is limited to the supported Kafka key fields. The effectively exactly-once statement applies to Postgres upserts only. Savepoint restore and rescale have implementations, but that is not the same as being covered by the short e2e path. The Tour, Operations, and scenario panels are not mounted in this shell. These limits matter: I’d rather state exactly what this build and its evidence demonstrate than imply broader production guarantees.

**Stress:** `docs/testing.md` explicitly says it does not report throughput, latency, or checkpoint times.

**Examiner:** What throughput can you claim from this presentation?  
**Answer:** No measured throughput figure is established by the testing catalogue. I would need an observed benchmark report with its workload and conditions before quoting a rate.

### 12. Close

**Say:** To close, we have shown the truck city, engine panels, editor, chaos path, comparison, and the available Compose and monitoring views. The recorded CI and deployment runs are separate evidence: I’ll name each run and its scope rather than combining their results. A Testcontainers comparison, an e2e comparison, and a deployment smoke check answer different questions. If a click failed, I’ll name the fallback I used; if a step was skipped, I won’t call it a pass. Thank you. I’m ready for questions, and I’ll keep answers within the guarantees and evidence we’ve shown.

**Stress:** The CI snapshot is explicitly point-in-time and notes that in-progress runs are not passing conclusions.

**Examiner:** Which single result best supports the distributed-engine claim?  
**Answer:** The `EngineMatchesReferenceIT` entry describes a three-worker Kafka, Postgres, and MinIO run compared with reference at a completed checkpoint. I would also show its actual run result before calling that particular execution a pass.
