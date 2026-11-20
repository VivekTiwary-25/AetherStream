# Changelog

All notable changes to this project are documented here.

## [Unreleased]

### Added

- Added the Glorious UI as the default, with Home and live status, the Live World map and truck details, analytics for speeds and failed records, AI-assisted pipeline review and execution, editor results, and Logs & Recovery history. The classic UI remains available at `?ui=classic`.

### Fixed

- Ask AI now offers an explicitly labelled saved pipeline for exact suggestion prompts when the bridge is offline or generation exceeds 60 seconds. Unmatched prompts retain the error with a suggestion hint; saved workflows still use the real Run API.
- Distinguished pipeline-results request errors from pipeline validation failures and added editor Validate → Run regressions using captured API responses, including the non-negative initial cursor and real result rows.
- Prefetch the Live World chunk once after Home renders during browser idle time, with a timer fallback and reduced-data opt-out.
- Fixed Glorious walkthrough interaction with moving truck markers and retained analytics dialogs, restored worker partition-chip test IDs, and mapped AI generation HTTP/network failures to unavailable while preserving validation and busy messages.
- Fixed production Live World basemap loading by bundling MapLibre's worker through Vite and serving `.mjs` as JavaScript in nginx.
- Fixed live pipeline result cursors across runs, consumer stop/close races, and failure reporting during stop and shutdown.
- Fixed chained window finalization, grouped threshold cooldown identity after field projection, and recovery audit history attribution, timestamps, retention, and replay deduplication.

## [1.0.0] - 2026-11-19

### Engine
- Added partitioned event processing with validation, filtering, mapping, keyed windows, rules, and deterministic output semantics shared with the reference executor.
- Added coordinator/worker assignment, generation fencing, aligned checkpoints, recovery, worker rejoin, and checkpoint restore/rescale support.
- Added a pipelined worker runtime with backpressure, fenced batched sinks, sink-delay and worker-kill fault injection, and worker/coordinator metrics.
- Hardened crash handling, checkpoint completion, savepoint compatibility, and recovery behavior; fixed simulation-time continuity across simulator restarts.

### Compiler & reference
- Added pipeline validation, deterministic execution planning, and a single-process reference executor using shared operator semantics.
- Added engine-versus-reference comparison and compiler/reference coverage for validation, operators, and restored executions.

### Control API
- Added REST endpoints for health/readiness, pipeline validation and management, execution lifecycle, results, workers, chaos controls, and reference comparison.
- Added WebSocket snapshots and live updates for execution, workers, simulator, flow, windows, alerts, and dead letters.
- Added savepoint, restore, rescale, scenario, metrics, and pipeline-example APIs; added organization-scoped access and recovery duration reporting.

### Simulator
- Added deterministic truck-city event generation, simulator state/control APIs, and late, duplicate, and malformed event injection.
- Added rush-hour, breakdown, surge, and reset scenarios; made simulation time monotonic across process restarts.

### Web city
- Added the PixiJS truck-city visualization and refined its visuals, interaction, responsive layout, and touch guidance.

### Web app
- Added the React/TypeScript application shell, shared API client, design system, and live operations views.
- Added the pipeline editor, example picker, scenario and chaos controls, comparison view, and presenter tour.
- Added execution, worker, accessibility, checkpoint/recovery timeline, savepoint/restore/rescale, and results panels.

### Infra & CI/CD
- Added Docker Compose services, container images, smoke checks, and continuous integration for Java, web, integration, compose, images, mock server, and UI end-to-end workflows.
- Added GitHub Container Registry publishing, self-hosted deployment, post-deploy checks, Kubernetes manifests, Terraform infrastructure, and monitoring with Prometheus and Grafana.
- Added CI safeguards for mock-free production builds, web coverage, demo readiness, and task-branch workflow filtering.

### Tests
- Added unit, contract, integration, and fault tests for the simulator, coordinator/worker protocol, Kafka source, fenced sinks, checkpoints, recovery, savepoints, rescaling, and engine/reference equivalence.
- Added end-to-end coverage for comparison, savepoint/restore, rescaling, simulator scenarios, and real-stack UI flows; added benchmark reporting and restart/rejoin soak checks.

### Docs
- Added architecture, UX, testing, runbook, FAQ, glossary, syllabus, demo-day, and expo presentation materials, with pipeline and UI usage guidance.
- Added API and CI/CD documentation, evidence pages, README badges, project reporting, and low-fidelity wireframes with user flows.
- Added repository contribution guidance, pull request and issue templates, CODEOWNERS, and dependency update configuration.
