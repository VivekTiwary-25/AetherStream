# AetherStream presentation outline

14 slides. Replace bracketed run IDs and results with observed evidence; mark any unfinished feature **planned**. Speaker notes are prompts, not claims of completed experiments.

## 1. Title and project scope
- Design and Implementation of a Fault-Tolerant Distributed Stream Processing Engine with Stateful Recovery and Visual Workflow Compilation.
- AetherStream: a logistics stream-processing demonstration.
- CDD project; [student/team/institution].
- Scope: custom Java pipeline/runtime with a visual interface.

**Show live:** Title slide, then repository root.  
**Speaker notes:** State what the project is. Separate implemented repository contents from unverified deployment and performance claims.

## 2. The problem: streams fail in real ways
- Events can be delayed, repeated, malformed, or out of order.
- Processing can fall behind a slow sink.
- Worker loss interrupts partition processing.
- State, offsets, and external writes must remain consistent.

**Show live:** `docs/contracts/02-pipeline-and-semantics.md` and `03-coordinator-worker-protocol.md`.  
**Speaker notes:** Explain the logistics example without claiming that it models a commercial fleet.

## 3. Goals and measurable acceptance
- Validate a graph before execution.
- Process keyed, event-time windows and alerts.
- Recover using completed consistent checkpoints.
- Compare output with a deterministic reference.
- Show actual tests and CI evidence.

**Show live:** acceptance contract/test report or Actions run.  
**Speaker notes:** The goals are not results. Point to a real test/run before saying any criterion passed.

## 4. System architecture
- Compiler and shared operator semantics.
- Coordinator assigns partitions; workers process bounded stages.
- Kafka input, PostgreSQL result sink, S3-compatible checkpoint store.
- Control API and React UI; simulator emits logistics events.

**Show live:** `docs/contracts/README.md`, `docker-compose.yml`, module folders.  
**Speaker notes:** Describe the custom runtime boundary. Flink is a reference, not a dependency/runtime claim.

## 5. Pipeline compilation and event time
- Typed source/filter/key-by/window/rule/sink graph.
- Compiler collects validation errors and produces a deterministic plan.
- Per-partition watermark = max event time minus allowed out-of-orderness.
- Closed-window late records are routed to dead letters.

**Show live:** pipeline editor and validation response; `POST /api/pipelines/validate` if stack is running.  
**Speaker notes:** Explain that watermarks express a policy; an idle partition does not advance in the contract.

## 6. Reference execution and correctness
- Reference executor runs the shared operator semantics.
- Compare uses records bounded by checkpoint offsets.
- Window rows compare values and event count; mismatches are inspectable.
- A match is scoped evidence, not a blanket guarantee.

**Show live:** actual compare response and checkpoint IDs, or explicitly say comparison not run.  
**Speaker notes:** Do not show fabricated counts. Note the API contract caps returned mismatch details.

## 7. Worker protocol and backpressure
- Worker pipeline: source → bounded queue → operators → bounded queue → sink.
- Queue capacity and worker status are exposed by the protocol.
- Slow sink can demonstrate queue growth and upstream blocking.
- Execution generation fences stale workers.

**Show live:** worker panel and actual slow-sink observation; `POST /api/chaos/slow-sink` if safely available.  
**Speaker notes:** Distinguish worker backpressure from execution failure. State only observed outcomes.

## 8. Checkpointing: consistent state and offsets
- Source places an aligned barrier behind records already enqueued.
- Operator snapshots partition state; sink flushes prior outputs.
- Worker state/offset objects are acknowledged; coordinator writes manifest.
- Only COMPLETED checkpoints can be restored.

**Show live:** `docs/contracts/03-coordinator-worker-protocol.md` and actual checkpoint endpoint/list if running.  
**Speaker notes:** Walk through the contract sequence and identify the corresponding implementation/test evidence.

## 9. Failure and recovery demonstration
- Inject a worker failure only in the demo environment.
- Observe worker-dead and execution-recovering transitions.
- Show restored checkpoint and generation if reported.
- Verify output correctness after recovery using test or comparison evidence.

**Show live:** confirmation dialog, actual recovery logs, checkpoint history and post-recovery result.  
**Speaker notes:** If no successful run evidence exists, present this as a planned demo, not a completed result.

## 10. Visual workflow and UX
- Graph editor for pipeline creation and validation.
- Operations panels for workers, windows, alerts and dead letters.
- City view provides context; tables and labels carry evidence.
- UX files are design proposals, not user-study results.

**Show live:** running UI at `http://localhost:8088` and one accessible evidence panel.  
**Speaker notes:** Distinguish implemented UI from planned UX research, Figma work, or usability validation.

## 11. Testing strategy and results
- Gradle build/unit test workflow.
- Testcontainers integration workflow.
- Web lint/test/build and Docker image checks.
- Compose smoke script exercises the integrated services.

**Show live:** GitHub Actions run on presented commit; artifacts and test reports.  
**Speaker notes:** Fill in `[run #..., commit ..., conclusion ...]` from actual Actions. Do not invent totals or benchmarks.

## 12. Docker and local deployment
- Custom Java runtime images and web image.
- Compose includes Kafka, PostgreSQL, MinIO and application services.
- Health checks, persistent volumes, labels, and smoke verification.
- CI image workflow builds without pushing.

**Show live:** `docker compose ps`, `docker image ls`, image revision label and service logs.  
**Speaker notes:** Local containers prove local execution, not cloud deployment or production readiness.

## 13. CI/CD and delivery evidence
- Separate workflows for Java, integration, web, images, mock, and Compose checks.
- Workflow definitions are inspectable; successful run must be shown live.
- `cd.yml`, self-hosted deployment, and GHCR publication were not found in inspected checkout.
- Mark external CD as unverified/planned unless current direct evidence is available.

**Show live:** Actions page and `gh run list`; if a real deployment workflow exists, show its log and deployed version.  
**Speaker notes:** Explicitly distinguish build, publish, deploy, and smoke-test stages.

## 14. Limitations, future work, conclusion
- Single-active-coordinator baseline; HA is planned.
- Savepoints, rescaling, advanced operators, IaC, and external deployment need evidence before being called complete.
- No benchmark values or usability-test results are asserted.
- Summarize strongest verified behavior and next engineering step.

**Show live:** limitation list beside actual evidence and open gaps.  
**Speaker notes:** End with a defensible claim: show exactly what was built and tested; label everything else planned.
