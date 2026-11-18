# AetherStream UX

AetherStream's proposed interface helps pipeline authors build and validate stream-processing graphs, while operators and evaluators follow live logistics events, inspect engine state, inject controlled failures, and compare results with a reference. These documents are design proposals for a system that is still at the proposal/planning stage; they are not evidence of a built UI, user research, usability testing, or a Figma prototype.

## Documents

- [Personas](personas.md) — four proto-personas, goals, frustrations, needs, and assumed quotes.
- [Journeys](journeys.md) — table-based flows for authoring, monitoring, recovery, correctness checks, and delivery.
- [Design thinking](design-thinking.md) — problem framing, alternatives, low-fidelity sketches, Figma plan, and a usability test plan.
- [Usability decisions](usability-decisions.md) — principles behind interaction, feedback, accessibility, and motion.
- [Design system](design-system.md) — proposed colour, typography, spacing, elevation, state, and component tokens.
- [Figma plan](figma-plan.md) — frames, reusable components, variants, flows, and a build checklist.
- [Visual QA of the live app](visual-qa.md) — findings for each screenshot (contrast, overlap, clipping, mobile layout), each with a severity and a suggested fix. This feeds the final polish task.

## Live screenshots

These are real captures of the deployed app (headless Edge, 2026-11-19, builds `7c45ceb`/`59a374f`/`de12694`), stored in [screenshots/](screenshots/). The pipeline editor, chaos panel and compare result were not mounted yet, so they have no screenshots.

| View | Desktop 1440×900 | Mobile 390×844 |
|---|---|---|
| App after 60 s (viewport) | [desktop-01](screenshots/desktop-01-app-viewport-60s.png) | [mobile-01](screenshots/mobile-01-app-viewport-60s.png) |
| App after 60 s (full page) | [desktop-02](screenshots/desktop-02-app-fullpage-60s.png), [connection lost (7c45ceb)](screenshots/desktop-02b-fullpage-reconnecting-build-7c45ceb.png) | [mobile-02](screenshots/mobile-02-app-fullpage-60s.png) |
| Zoomed city | [desktop-03](screenshots/desktop-03-city-zoomed.png) | [mobile-03](screenshots/mobile-03-city-zoomed.png) |
| Recovering from checkpoint #N | [viewport](screenshots/desktop-04-recovering-viewport.png), [full page](screenshots/desktop-05-recovering-fullpage.png) | [panels](screenshots/mobile-04-recovering-panels.png), [pill overlap (7c45ceb)](screenshots/mobile-04b-recovering-pill-overlap-build-7c45ceb.png) |
| After recovery | [desktop-06](screenshots/desktop-06-after-recovery-fullpage.png) | [mobile-06](screenshots/mobile-06-after-recovery-fullpage.png) |
| Execution banner | [normal](screenshots/desktop-execution-banner.png), [recovered](screenshots/desktop-recovered-banner.png) | [normal](screenshots/mobile-execution-banner.png), [recovering](screenshots/mobile-recovering-banner.png) |
| Speed windows panel | [empty](screenshots/desktop-panel-speed-windows.png), [populated (7c45ceb)](screenshots/desktop-panel-speed-windows-populated-build-7c45ceb.png) | [empty](screenshots/mobile-panel-speed-windows.png) |
| Deliveries / min panel | [desktop](screenshots/desktop-panel-deliveries-min.png) | [mobile](screenshots/mobile-panel-deliveries-min.png) |
| Alerts panel | [desktop](screenshots/desktop-panel-alerts.png) | [mobile](screenshots/mobile-panel-alerts.png) |
| Dead letters panel | [desktop](screenshots/desktop-panel-dead-letters.png) | [mobile](screenshots/mobile-panel-dead-letters.png) |
| Workers panel | [before](screenshots/desktop-panel-workers.png), [after kill](screenshots/desktop-panel-workers-after-kill.png) | [before](screenshots/mobile-panel-workers.png), [after kill](screenshots/mobile-panel-workers-after-kill.png) |

## Shared assumptions

The product uses a dark navy/charcoal bento layout with restrained luminous accents, glass-like panels, and clay-like primary buttons. A logistics city scene shows simulated trucks travelling between a depot and stops; it is an orientation and context layer, not a replacement for accessible tables or controls. Pipeline building, worker status, results, chaos controls, reference comparison, and deployment identity must remain inspectable in ordinary interface components.

The UI talks to the same-origin `/api` REST routes and plain `/ws` stream described in [Control API contract 04](../contracts/04-control-api-rest-ws.md). REST supplies validation, execution, workers, checkpoints, results, chaos actions, comparison, and version data. WebSocket snapshots and updates keep the city and panels current. UI states must use text and colour together. See each document for details and unresolved assumptions.
