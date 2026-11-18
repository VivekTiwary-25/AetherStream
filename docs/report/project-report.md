# Design and Implementation of a Fault-Tolerant Distributed Stream Processing Engine with Stateful Recovery and Visual Workflow Compilation

> **Project:** AetherStream  
> **Course:** Collaborative Development and DevOps (CDD)  
> **Student:** [name / registration number]  
> **Institution:** [institution]  
> **Supervisor:** [name]  
> **Submission date:** [date]
>
> Complete the bracketed fields and replace result placeholders only with evidence from actual project runs.

## Abstract

AetherStream is a Java-based project for authoring and running stateful stream-processing pipelines. Its repository defines a modular engine, deterministic reference execution, a React workflow interface, a logistics event simulator, persistence and checkpoint services, and automated verification workflows. The design focuses on event-time windows, late data, bounded worker queues, consistent checkpoints, worker recovery, and comparison against a reference executor. Docker Compose provides a local multi-service stack. GitHub Actions definitions cover Java, integration, web, images, mock-server and Compose checks. The repository contains definitions and implementation evidence, but a checked-in workflow is not proof of a successful run, and image build capability is not proof of deployment. Deployment and live-run results must be filled from verifiable evidence.

## Problem statement

Continuous event sources can deliver delayed or repeated records, outpace consumers, and be interrupted by worker failures. A useful processing system needs explicit rules for event time, replay, state snapshots, output consistency, and recovery. AetherStream addresses this as a demonstrable logistics workload: simulated truck events flow through validation, partitioned window calculations and alerts into queryable results, while a visual interface presents execution and failure evidence.

## Objectives

- Define a typed event and pipeline contract and validate graphs before execution.
- Provide deterministic operator semantics shared by distributed and reference execution.
- Run partitioned processing with bounded queues and observable backpressure.
- Persist checkpoint state and source positions so recovery can resume from a completed consistent checkpoint.
- Demonstrate event-time, late-event, duplicate, slow-sink and worker-failure behavior.
- Provide a visual editor/operations UI and a repeatable containerized development environment.
- Automate build, tests, image checks, and full-stack smoke verification through GitHub Actions.

## Background and related work

**Stream processing** evaluates unbounded event sequences continuously. A stream engine must coordinate event ordering, state, throughput, persistence and failures rather than treating input as a finite batch.

**Event time and watermarks.** Event time reflects when a domain event occurred; processing time reflects when the system handled it. A watermark is a policy-based estimate of input progress, not proof that no older event will arrive. AetherStream's contract defines a per-source-partition watermark as maximum observed timestamp minus allowed out-of-orderness; late events for closed windows are recorded as dead letters. This makes the lateness policy and idle-partition behavior explicit.

**Chandy–Lamport snapshots.** The coordinator/worker contract uses aligned barriers: the source places a barrier after preceding records, the operator snapshots state at the barrier, and the sink flushes preceding output before saving worker state and acknowledging. The coordinator marks a checkpoint completed only after all partition acknowledgements and a manifest upload. Restore selects only a completed checkpoint. This is a Chandy–Lamport-style consistent-cut approach adapted to the pipeline queues and source positions specified in the contract.

**Apache Flink as a reference.** Flink provides a mature conceptual and operational reference for distributed stateful streams, event time, and checkpoint recovery. AetherStream uses it as background, not as its execution runtime. Its operators, compiler, coordinator, worker protocol and recovery logic are project components; parity with Flink is not claimed.

## System design

The system separates definition/validation, runtime execution, control and persistence. A pipeline compiler validates and plans a DAG. A reference executor evaluates the same operator semantics used by the engine. The coordinator assigns Kafka partitions to workers and manages generations, checkpoints and recovery. Workers consume Kafka records, run operator state through bounded stages, and write results to PostgreSQL. Checkpoint objects and manifests use S3-compatible storage. A Spring-based control API exposes pipeline, execution, result, chaos and comparison operations; the React UI uses REST and WebSocket updates. The deterministic simulator supplies logistics events.

See [contracts](../contracts/README.md), especially [pipeline semantics](../contracts/02-pipeline-and-semantics.md), [worker and checkpoint protocol](../contracts/03-coordinator-worker-protocol.md), and [control API](../contracts/04-control-api-rest-ws.md). The requested `docs/architecture.md` is not present in the inspected repository; these contracts are the available architecture evidence.

## Implementation by module

The pinned stack in `docs/contracts/00-stack-and-layout.md` specifies Java 21, Gradle, Spring Boot, Kafka, PostgreSQL, MinIO, React/TypeScript and the following modules. The repository contains these module trees; consult source and test reports for exact implemented behavior.

- **`:common` (`common/`)** — shared Java records for event, pipeline, protocol and API contracts.
- **`:compiler` (`compiler/`)** — pipeline JSON validation and deterministic execution planning; operator semantics are shared with both executors.
- **`:reference` (`reference/`)** — deterministic single-process executor used as a correctness comparison baseline.
- **`:engine` (`engine/`)** — coordinator and worker runtime, with Kafka consumption, bounded processing queues, state snapshots, checkpointing and recovery specified by the protocol contract.
- **`:control-api` (`control-api/`)** — Spring Boot REST and WebSocket API for pipelines, executions, workers, results, chaos operations, and reference comparison.
- **`:simulator` (`simulator/`)** — Java logistics/truck event generator and HTTP interface.
- **`:tests` (`tests/`)** — integration-test module; checked-in tests include Kafka offset lookup, PostgreSQL execution repository, and S3 checkpoint store (`tests/src/test/java/io/aetherstream/tests/`). Fault/recovery and distributed comparison coverage should be demonstrated from actual test code and run reports, not inferred from the module name.
- **Web (`web/`)** — React/TypeScript pipeline editor, logistics city view, API client, live panels, and chaos/reference comparison panels. UX documents are proposals, not proof of user research or usability testing.

## Testing strategy

The repository defines multiple layers of verification:

1. **Unit/build checks:** `.github/workflows/ci-java.yml` runs `./gradlew build --console=plain`, building and unit-testing modules and uploading test reports.
2. **Integration checks:** `.github/workflows/ci-integration.yml` runs `./gradlew :tests:integrationTest --console=plain`; checked-in integration tests exercise Kafka, PostgreSQL and S3-compatible storage with Testcontainers. Record the actual run URL and report before asserting success.
3. **Web checks:** `.github/workflows/ci-web.yml` runs npm install validation, lint, tests and build in `web/` and uploads `web-dist`.
4. **Image checks:** `.github/workflows/ci-images.yml` builds custom images without publishing, runs Java entrypoint smoke checks, and checks revision labels.
5. **Compose smoke:** `.github/workflows/ci-compose.yml` starts the full stack, runs `infra/scripts/smoke.sh`, and captures service status/logs. The script is the integration smoke scenario; its actual output determines success.
6. **Reference comparison:** the API contract describes replaying records up to a completed checkpoint through `ReferenceExecutor`, and comparing window outputs. Show the actual comparison response and corresponding execution/checkpoint.
7. **Fault/recovery tests:** planned acceptance scenarios include worker termination, incomplete checkpoint rejection, stale-generation fencing, slow-sink backpressure and recovery correctness. Report only cases present in tests and supported by a passing run.

**Results to complete from evidence:**

- Java workflow: [fill in from CI run #...; URL, commit, conclusion]
- Integration workflow: [fill in from CI run #...; URL, commit, conclusion]
- Web / image / Compose workflow results: [fill in from CI run #...]
- Test totals and failures: [fill in from uploaded reports]
- Reference comparison: [fill in from actual response, checkpoint, and commit]
- Recovery demonstration: [fill in from logs and observed generation/checkpoint]
- Performance measurements: [fill in from reproducible benchmark, workload, hardware, and method; otherwise state not measured]

No benchmark values are asserted here.

## DevOps and delivery

### Git workflow

Task instructions describe task branches and reviews as the team workflow. Use branch history and reviewed pull requests as evidence; do not claim review or merge practices from a plan alone. CI YAML triggers include pushes to `main` and `task/**` and pull requests to `main` for most workflows. The web workflow filters pushes to web paths. The documented contract also mentions `cd.yml`, but no such file was found in the inspected workflow directory; actual CD must therefore be demonstrated separately if it exists outside this checkout.

### GitHub Actions

| Workflow | Defined behavior | What the examiner should verify |
|---|---|---|
| `ci-java.yml` | Gradle build and unit tests; test reports artifact | A completed run on the presented commit and its test reports |
| `ci-integration.yml` | Testcontainers integration task; reports artifact | Run conclusion and integration report |
| `ci-web.yml` | npm ci, lint, test, production build; dist artifact | Run conclusion and artifact |
| `ci-images.yml` | Build engine, simulator, API, optional web images; smoke and inspect revision labels | Log evidence; it explicitly uses `push: false` |
| `ci-compose.yml` | Build/start complete stack, execute smoke script, print status/logs, tear down | Smoke PASS output and service logs |
| `ci-mock.yml` | Install and smoke-test web development mock server | Run log; mock data is not live-engine evidence |

The definitions show automation intent, not successful executions. No run IDs or benchmark values are invented here.

### Docker and local stack

`infra/docker/` contains custom multi-stage Dockerfiles for engine, simulator and control API, plus a web build/NGINX image. The Java runtime images use Temurin JRE 21, a non-root `aether` user, health checks, and OCI source/revision labels. `docker-compose.yml` defines Kafka, PostgreSQL, MinIO, coordinator, three workers, simulator, control API and web, with persistent volumes, health checks and a shared network. The default credentials in Compose are development defaults; supply secrets appropriately outside a local demo. `ci-images.yml` builds but does not push these images.

### Continuous deployment

A working deployment requires evidence that the built version was published, deployed to a running target, and smoke-tested. The inspected `.github/workflows/` listing contains no `cd.yml`, and the inspected `infra/` contains Dockerfiles, database schema and a smoke script, but no self-hosted-runner deployment workflow or Terraform/Kubernetes deployment configuration. Therefore CD to a self-hosted runner, a live deployed commit, GHCR publication, and runner availability are **not evidenced in this checkout**; treat them as planned/unverified until the team supplies direct workflow and environment evidence. Do not equate image build with deployment.

## User experience

The UX materials in [docs/ux](../ux/README.md) include proto-personas, proposed journeys, design decisions, a design system and a Figma plan. They identify a pipeline author, operator, administrator and evaluator. The documents explicitly label personas and journeys as assumptions/proposals, not observed research, implemented journeys or usability-test results. The product direction pairs the logistics city visualization with readable tables and status labels; error, recovery and backpressure states should remain inspectable without relying on colour or animation alone.

## Limitations and planned work

- The supplied handoff says it was written at proposal stage; repository implementation now exists, but a report author must verify every claim against the current source and run artifacts.
- The architecture contracts specify a single active coordinator; high availability, durable leadership and fencing across coordinator failover are planned extensions.
- The handoff lists savepoints, controlled rescaling, joins, schema evolution, permissions, autoscaling and advanced scheduling as staged or future work. Do not claim these unless implementation evidence is available.
- A local Compose stack and CI workflow definitions do not prove an external deployment, self-hosted runner, GHCR package publication, or production readiness.
- UX artifacts are not user interviews, tested prototypes, or conformance certification.
- No measured throughput, latency, recovery duration, or benchmark result is included; populate only from reproducible measurements.
- Confirm the current official CDD syllabus before claiming exact syllabus coverage; handoff section 11 says its mapping requires confirmation.

## Conclusion

AetherStream provides a technically substantial stream-processing project structure and explicit contracts for compiler semantics, partitioned execution, checkpoints, recovery, APIs, tests and containerized operation. The strongest examination narrative is to connect those contracts to a source file, an actual passing CI log, a running local or remote stack, and a reproducible reference/recovery result. Claims about deployment, benchmark performance, user validation, or unfinished extensions require additional evidence and should remain marked planned or unverified.

## References

1. AetherStream project handoff, §§ 1–17, particularly §§ 3, 9–11 and 17.
2. AetherStream repository contracts: [stack](../contracts/00-stack-and-layout.md), [pipeline semantics](../contracts/02-pipeline-and-semantics.md), [coordinator/worker protocol](../contracts/03-coordinator-worker-protocol.md), [control API](../contracts/04-control-api-rest-ws.md).
3. Apache Flink, [Overview](https://flink.apache.org/).
4. Apache Flink, [Flink applications](https://flink.apache.org/what-is-flink/flink-applications/).
5. Apache Flink, [Flink operations](https://flink.apache.org/what-is-flink/flink-operations/).
6. Apache Flink, [Event time and watermarks, 1.19 docs](https://nightlies.apache.org/flink/flink-docs-release-1.19/docs/concepts/time/).
7. Apache Flink, [Documentation entry point](https://flink.apache.org/q/stable-docs.html).
8. GitHub, [Deploying with GitHub Actions](https://docs.github.com/en/actions/how-tos/deploy).
9. GitHub, [Publishing Docker images with GitHub Actions](https://docs.github.com/en/actions/tutorials/publish-packages/publish-docker-images).
10. Docker, [Test before push with GitHub Actions](https://docs.docker.com/build/ci/github-actions/test-before-push/).
11. Docker, [Compose CLI reference](https://docs.docker.com/reference/cli/docker/compose).
12. Kubernetes, [Workload controllers](https://kubernetes.io/docs/concepts/workloads/controllers/).
