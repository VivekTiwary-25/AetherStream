# AetherStream — Project Handoff

## 1. Purpose and current status

This document transfers the context and proposed technical direction for the user's Collaborative Development and DevOps (CDD) project.

**Latest working proposal:** AetherStream — a distributed real-time processing engine with visual pipeline design, stateful execution, checkpoint recovery and dynamic scaling.

**Formal title:** Design and Implementation of a Fault-Tolerant Distributed Stream Processing Engine with Stateful Recovery and Visual Workflow Compilation.

**Status:** Proposal and planning only. The user requested this handoff after the AetherStream proposal. No application code, repository, tests, containers, cloud infrastructure or deployments have been created or verified. Requesting this document establishes the subject of the handoff; it does not confirm every proposed technology or feature.

The technologies, protocols, API paths and repository structure below are implementation recommendations, not existing assets.

## 2. User intent and constraints

- Preferred name: Onion.
- Wants a substantial, highly advanced project with practical relevance and deep engineering.
- Explicitly excludes campus-related topics.
- Initially requested MIT-related ideas, then explicitly broadened the request to projects from any source.
- Explicitly instructed that the deadline must not constrain project selection or ambition.
- The project should cover the full CDD syllabus and the essential components in the faculty announcement.
- Wants a working implementation suitable for demonstration, rather than a concept-only presentation.
- Do not restart project brainstorming unless requested. Use AetherStream as the current working direction.

The faculty announcement names 21 November 2026 as the expo date. Retain that context, but do not use it to reduce scope against the user's later instruction.

## 3. Confirmed faculty requirements

The user supplied the following requirements directly:

| Requirement | Required evidence |
| --- | --- |
| Python, Java or Machine Learning as appropriate | A substantial application implemented using an appropriate permitted technology |
| Fully functional CI/CD | Automated build, testing and deployment of the application |
| GitHub Actions | Working workflow definitions and execution logs |
| Effective Docker use | Working custom images and running application containers |
| Technical depth and innovation | Meaningful features, practical use and complexity beyond a basic application |
| Complete demonstration | Running application, tests, CI/CD, GitHub Actions and Docker demonstrated to an external examiner |

The evaluation is **30 marks**. No official category-by-category allocation was provided. A rubric proposed earlier in the conversation was an assistant suggestion, not confirmed faculty policy.

Attendance is mandatory; the announcement states that an absent student receives zero marks. Venue and reporting time were not supplied.

## 4. Problem and product definition

Organisations receive continuous streams of delivery events, sensor readings, orders and application activity. These events may arrive late, repeat, exceed processing capacity or be interrupted by machine failures.

AetherStream lets a user define a computation visually, compiles it into an execution graph and runs that graph across workers. It must expose clear policies for time, ordering, replay, recovery and external outputs.

### Primary demonstration application

Use a simulated logistics event stream to make the engine understandable:

1. Generate vehicle and delivery events with deterministic identifiers and timestamps.
2. Validate records and route malformed records to a rejection stream.
3. Partition valid events by vehicle or delivery identifier.
4. Calculate windowed movement and progress summaries.
5. Detect configured delay patterns.
6. Write results to a tracking dashboard and alert history.
7. Repeat the workload with delayed events and worker failures.

Define delay rules explicitly. This is a demonstration application running on the engine; it does not require a commercial routing system.

### Intended users

| User | Main task |
| --- | --- |
| Pipeline author | Define schemas, connect operators, validate and submit a pipeline |
| Operator | Inspect workers, checkpoints, backlogs and failures; recover a pipeline |
| Organisation administrator | Manage membership, ownership and access |
| Evaluator | Observe a repeatable end-to-end execution and inspect evidence |

## 5. Original implementation boundary

Implement the pipeline compiler, execution graph, scheduler, worker runtime, routing, state coordination, checkpoint protocol, recovery logic and operations API.

Integrate established systems for durable input, metadata, authentication where selected, checkpoint object storage, monitoring and container orchestration.

Kafka is the replayable event source. It does not supply the custom operator runtime. Kubernetes manages containers. It does not implement the engine's stateful recovery protocol.

Apache Flink is an architectural and research reference. Building a UI around Flink alone would change the implementation boundary and should be described honestly if that approach is later chosen.

## 6. Proposed architecture

Separate the control plane, data plane and recovery coordination.

| Component | Responsibility |
| --- | --- |
| Web interface | Visual graph editor, validation feedback, execution and recovery views |
| Control API | Authentication integration, permissions, pipeline versions, submissions and audit records |
| Compiler | Graph validation, schema checking, logical planning and partitioned execution planning |
| Coordinator | Worker membership, task assignments, execution generations and recovery decisions |
| Workers | Operator execution, keyed state, event routing, flow control and snapshots |
| Checkpoint subsystem | Barrier coordination, snapshot manifests and checkpoint completion records |
| Kafka | Durable input partitions and replayable source positions |
| PostgreSQL | Pipeline metadata, configuration, execution records, access data and supported result sinks |
| Object storage | Immutable checkpoint state and manifests |
| Observability stack | Metrics, logs, dashboards and alerts |

The first implementation can use one active coordinator with durable metadata. Coordinator high availability is a later feature requiring a leadership mechanism and fencing. Multiple active coordinators must not independently assign the same execution.

### Proposed technology choices

| Area | Recommendation | Decision status |
| --- | --- | --- |
| Engine and workers | Java | Proposed |
| Control API | Spring Boot | Proposed |
| Frontend | React and TypeScript | Proposed |
| Worker communication | gRPC with explicit protocol versions | Proposed |
| Durable source | Kafka | Proposed |
| Metadata and reference sink | PostgreSQL | Proposed |
| Checkpoint objects | S3-compatible storage | Proposed |
| Local environment | Docker Compose | Required approach; exact services pending |
| Cluster runtime | Kubernetes | Proposed |
| Infrastructure as code | Terraform | Proposed |
| CI/CD | GitHub Actions | Required |
| Monitoring | Prometheus and Grafana | Proposed |
| Design | Figma | Syllabus-aligned |

Select compatible stable versions during implementation and pin them. No versions have been verified or selected in this handoff.

## 7. Functional scope

### Foundation

- Typed event envelope: event ID, schema version, source partition/offset, event timestamp, key and payload.
- Pipeline definitions represented as versioned JSON.
- Initial operator set: source, map, filter, key-by, tumbling window aggregation and sink.
- Compiler rejects missing sources/sinks, invalid references, incompatible schemas and unsupported cycles.
- Deterministic single-process reference executor.
- Worker registration, heartbeats, task assignment and execution status.
- Partition ownership scoped to an execution generation.
- Bounded channels and observable backpressure.
- PostgreSQL result sink with explicitly defined deduplication/commit behaviour.

### Advanced engine capabilities

- Event-time windows and watermark propagation.
- Explicit late-event handling and configurable allowed lateness.
- Consistent distributed checkpoints.
- Restore operator state and source positions from a completed checkpoint.
- Worker failure detection, reassignment and replay.
- Savepoints for controlled restarts and compatible upgrades.
- Partition reassignment and state transfer during rescaling.
- Sliding/session windows and bounded stream joins.
- Schema evolution rules and state compatibility checks.
- Pipeline ownership, permissions and audit records.

### Further research extensions

- Coordinator failover with durable leadership and fencing.
- Incremental or asynchronous snapshots.
- Skew-aware partitioning and workload-aware scheduling.
- Autoscaling based on measured lag and resource use.
- Checkpoint-based branching and historical replay.
- A limited SQL-like language compiled into the same execution graph.
- Pluggable inference operators with versioned models.

These are staged capabilities, not claims of completed work or a commitment to support every feature in the first release.

## 8. Correctness contracts to define before implementation

### Event time and late data

Specify timestamp units, source watermark policy, allowed lateness and whether late records update previous outputs, enter a side stream or are rejected. Define how the minimum watermark across inputs advances and how idle inputs are treated.

Watermarks express an input-progress policy. They cannot prove that no older event will ever arrive.

### Ordering and partition ownership

Promise ordering only within the supported partition/key model. Tag assignments and messages with an execution generation so a stale worker cannot continue committing after reassignment.

### Checkpoint protocol

A checkpoint must identify the pipeline version, execution generation, operator state, source positions and any pending sink-commit information required by the chosen protocol.

Persist a completed checkpoint manifest only after all required components have acknowledged the same consistent checkpoint. Ignore incomplete checkpoints during restore. The design must account for in-flight records; independent snapshots of arbitrary worker state are insufficient.

### Processing guarantees

Separate these concepts:

1. Input records may be replayed after a failure.
2. Operator state can reflect each input once when consistent state/source recovery is implemented correctly.
3. External output consistency additionally depends on the sink protocol.

Start with an explicitly documented at-least-once baseline. Claim exactly-once outcomes only for the particular source/operator/sink path supported by the implemented protocol and failure tests. General HTTP calls or notifications do not automatically become exactly once.

### Scaling

The first correct rescaling protocol may pause at a checkpoint, transfer state, change assignments and resume. Online state migration is a later extension. Do not describe a controlled pause as interruption-free scaling.

### Version compatibility

Track pipeline version, state serializer version, protocol version and container image digest. Reject incompatible restore requests. Container rollback alone cannot repair incompatible application state or database migrations.

## 9. GitHub Actions, CI/CD and Docker

### Pull request workflow

1. Compile Java modules and type-check/build the frontend.
2. Run unit tests and compiler validation tests.
3. Start dependency containers and run integration tests.
4. Build application Docker images and test their health endpoints.
5. Run a small deterministic distributed workload and compare outputs with the reference executor.
6. Publish reports and logs as workflow artifacts.

### Merge and deployment workflow

1. Build and publish immutable application images identified by commit and digest.
2. Deploy the tested image versions to staging.
3. Wait for readiness and verify the running version.
4. Submit a known pipeline and validate its output.
5. Promote the same image digests to the configured demonstration/production environment after release conditions pass.
6. Record deployment results and preserve the previous compatible release.

Publishing an image does not complete continuous deployment. The workflow must update a running environment and verify application behaviour.

### Extended verification workflow

Run longer chaos, recovery, scaling and benchmark scenarios on demand or for releases. Keep these isolated from any live demonstration environment.

### Docker evidence

- Custom Dockerfiles for coordinator, worker, API and frontend.
- Compose configuration for a complete local system with multiple workers.
- Explicit networks, persistent volumes, health checks and configuration inputs.
- Graceful shutdown and restart behaviour.
- Image version inspection and container logs.
- No credentials committed to images or the repository.

### Deployment choices

Use Compose for development and Kubernetes for the proposed distributed runtime. Provision infrastructure with Terraform.

The earlier syllabus specifically includes private Azure Container Registry and Azure Container Instances. Keep a separate ACR-to-ACI deployment exercise for a suitable stateless component or demo service if exact syllabus coverage is required. It is not a replacement for validating the full distributed engine deployment.

## 10. Verification and acceptance criteria

| Test | Required observation |
| --- | --- |
| Invalid pipeline | Rejected before workers start; actionable validation feedback |
| Reference correctness | Distributed results match a trusted deterministic executor |
| Duplicate source records | Behaviour matches the declared event deduplication policy |
| Out-of-order events | Window results match the configured watermark/late-data semantics |
| Worker termination | Work restores from a valid checkpoint and completes with the expected output |
| Interrupted checkpoint | Incomplete checkpoint is never selected as the recovery point |
| Stale worker | Old execution generation cannot commit new output |
| Slow sink | Buffers remain bounded and backpressure is observable |
| Rescale | No unsupported concurrent partition ownership; results remain correct |
| Coordinator restart | Single-coordinator baseline restores metadata and reconciles execution |
| Cross-organisation access | Authorisation denies access to another organisation's resources |
| Deployment | Running version matches the released image and smoke tests pass |
| Incompatible savepoint | Restore is rejected with a useful explanation |

Use fixed seeds, recorded inputs, known expected outputs and repeatable failure injection. Record input count, accepted/rejected records, output correctness, throughput, latency percentiles, checkpoint duration and recovery time. Do not invent benchmark results or claim parity with established engines.

## 11. Broader CDD syllabus coverage

This mapping is based on the syllabus reviewed earlier in the conversation; confirm against the current course document before final submission.

| Course area | Project evidence |
| --- | --- |
| DevOps culture, CI/CD and IaC | Shared team responsibilities, runbooks, GitHub Actions, Terraform and deployment topology |
| Git and Gitflow | Feature/release/hotfix branches, issues, reviewed PRs, release tags and collaboration history |
| Git practical operations | Demonstrate clone/fetch/pull/push, forks, merge/rebase and a controlled conflict-resolution exercise |
| Docker and automation | Java engine containers, Compose, image registry, tests and automated deployment |
| UX and design thinking | Research pipeline authors/operators, personas, journeys, prototypes, testing and revisions |
| Usability and interaction design | Explain usability decisions and evaluate the syllabus's UX factors, usability characteristics and interaction dimensions |
| Figma | Frames, reusable components, styles, pipeline creation flows and failure/recovery prototypes |
| Final mini-project | A complete running system with inspectable engineering evidence |

UI work should make failure states understandable: distinguish running, backpressured, checkpointing, recovering, paused and failed states. Use text labels as well as colour.

## 12. Suggested repository structure

All paths below are proposals; none have been created by this handoff task.

| Path | Purpose |
| --- | --- |
| `handoff.md` | Context, scope and implementation direction |
| `README.md` | Project explanation, setup and verified commands |
| `docs/architecture/` | Architecture decisions, protocols and correctness contracts |
| `docs/ux/` | Research notes, Figma links and usability findings |
| `engine-api/` | Events, schemas, operators and protocol definitions |
| `compiler/` | Graph validation and execution planning |
| `coordinator/` | Assignments, checkpoints and recovery |
| `worker/` | Operator execution and state |
| `control-api/` | REST endpoints, ownership and metadata |
| `connectors/` | Sources, sinks and supported commit semantics |
| `web/` | Visual editor and operations interface |
| `examples/logistics/` | Event generator and demonstration pipelines |
| `tests/integration/` | Multi-service correctness tests |
| `tests/faults/` | Recovery, partition and checkpoint scenarios |
| `benchmarks/` | Repeatable workload definitions and measurement scripts |
| `infra/compose/` | Local Docker environment |
| `infra/kubernetes/` | Cluster manifests or charts |
| `infra/terraform/` | Infrastructure provisioning |
| `.github/workflows/` | Build, test, release and deployment workflows |

## 13. Proposed API surface

- `POST /pipelines/validate` — validate a definition without deployment.
- `POST /pipelines` — create a versioned pipeline definition.
- `GET /pipelines/{id}` — retrieve an authorised pipeline.
- `POST /pipelines/{id}/executions` — submit an execution with an idempotency key.
- `GET /executions/{id}` — inspect execution state and progress.
- `POST /executions/{id}/pause` — request a controlled pause.
- `POST /executions/{id}/savepoints` — request a durable savepoint.
- `POST /executions/{id}/restore` — restore from a compatible checkpoint/savepoint.
- `POST /executions/{id}/rescale` — request controlled partition reassignment.
- `GET /executions/{id}/checkpoints` — inspect checkpoint history.
- `GET /workers` — inspect authorised cluster status.
- `GET /health` and `GET /ready` — separate process health from dependency readiness.

Treat asynchronous operations as tracked requests with status. Define access checks, state transitions, idempotency and error contracts before implementing these routes.

## 14. Implementation order without deadline-based scope cuts

1. Write architecture decisions and correctness contracts; choose compatible tool versions.
2. Create the monorepo, Java build, frontend scaffold, Docker setup and initial GitHub Actions checks.
3. Implement typed events, pipeline JSON, compiler validation and a deterministic local executor.
4. Add Kafka input, a reference sink and repeatable logistics fixtures.
5. Distribute execution across workers with fixed partitioning, fencing and bounded channels.
6. Add event-time processing and test watermark/late-data policies.
7. Implement consistent checkpoints and prove recovery through failure tests.
8. Connect the visual editor and operations interface to real engine behaviour.
9. Implement compatible savepoints and controlled rescaling.
10. Add permissions, audit records and the broader operator set.
11. Complete deployment, infrastructure provisioning, monitoring and release verification.
12. Pursue coordinator HA, online rescaling and compiler optimisation after the baseline guarantees are demonstrated.

Phases express dependencies, not calendar estimates. Do not conceal unfinished engine behaviour behind simulated UI responses.

## 15. Demonstration and final deliverables

### Demonstration sequence

1. Open a saved visual logistics pipeline and validate it.
2. Start a deterministic event generator and show the execution graph.
3. Show active workers, keyed aggregation and live results.
4. Inject delayed records and explain their treatment.
5. Slow a sink and show backpressure.
6. Terminate a worker; show the chosen checkpoint and recovery.
7. Compare final output against the reference result.
8. Rescale using the implemented protocol and describe any controlled pause accurately.
9. Show a repository change passing GitHub Actions and reaching the running deployment.
10. Inspect Docker images/containers, release version and audit history.

### Deliverables

- Working source repository and setup instructions.
- Dockerfiles, Compose configuration and deployed environment.
- GitHub Actions workflows and successful run evidence.
- Protocol/architecture documents and infrastructure code.
- Automated correctness and failure-recovery tests.
- Reproducible benchmark results with limitations.
- Figma prototypes, UX research and usability findings.
- Final report, presentation, demonstration script and runbooks.

## 16. Open decisions and next action

Still undecided: team size and responsibilities; hosting provider and budget; exact Java/build-tool versions; authentication provider; supported operator subset; first sink guarantee; checkpoint algorithm; UI library; and repository ownership/location.

These do not block handing over the proposal. Resolve them as implementation decisions when the user requests a build. Use reasonable defaults for reversible choices and ask only when account access, cost or a consequential scope decision requires it.

**Immediate next action if implementation is requested:** start with architecture contracts and an executable vertical slice: deterministic source → filter/map → keyed aggregation → verified sink, running locally in Docker with a real GitHub Actions test workflow. Then extend into distributed execution and recovery while retaining the overall advanced scope.

Do not claim deployment success, exactly-once guarantees, measured performance or completed tests until evidence exists.

## 17. Source references

These references informed the proposal. They do not imply affiliation or endorsement. Recheck version-specific details when implementing.

- [Apache Flink overview](https://flink.apache.org/) — distributed stateful stream processing reference.
- [Flink applications](https://flink.apache.org/what-is-flink/flink-applications/) — streams, state and time concepts.
- [Flink operations](https://flink.apache.org/what-is-flink/flink-operations/) — checkpoint recovery and source/sink conditions for consistency.
- [Flink event-time concepts](https://nightlies.apache.org/flink/flink-docs-release-1.19/docs/concepts/time/) — versioned conceptual reference for watermarks and late events; not a version recommendation.
- [Flink documentation entry point](https://flink.apache.org/q/stable-docs.html) — locate current stable implementation documentation.
- [GitHub Actions deployment documentation](https://docs.github.com/en/actions/how-tos/deploy) — deployment workflows.
- [Publishing Docker images with GitHub Actions](https://docs.github.com/en/actions/tutorials/publish-packages/publish-docker-images) — image publication.
- [Docker: test before push](https://docs.docker.com/build/ci/github-actions/test-before-push/) — container verification.
- [Docker Compose reference](https://docs.docker.com/reference/cli/docker/compose) — local multi-container execution.
- [Kubernetes workload controllers](https://kubernetes.io/docs/concepts/workloads/controllers/) — runtime workload management.

Course context previously reviewed: `CDD Syllabus.pdf` and `CDD Textbook.pdf` (Learning DevOps, second edition, Mikael Krief). Their current availability and any later course changes must be checked before relying on file paths or submitting exact syllabus claims.
