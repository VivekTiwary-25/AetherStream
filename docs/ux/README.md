# AetherStream UX

AetherStream's proposed interface helps pipeline authors build and validate stream-processing graphs, while operators and evaluators follow live logistics events, inspect engine state, inject controlled failures, and compare results with a reference. These documents are design proposals for a system that is still at the proposal/planning stage; they are not evidence of a built UI, user research, usability testing, or a Figma prototype.

## Documents

- [Personas](personas.md) — four proto-personas, goals, frustrations, needs, and assumed quotes.
- [Journeys](journeys.md) — table-based flows for authoring, monitoring, recovery, correctness checks, and delivery.
- [Design thinking](design-thinking.md) — problem framing, alternatives, low-fidelity sketches, Figma plan, and a usability test plan.
- [Usability decisions](usability-decisions.md) — principles behind interaction, feedback, accessibility, and motion.
- [Design system](design-system.md) — proposed colour, typography, spacing, elevation, state, and component tokens.
- [Figma plan](figma-plan.md) — frames, reusable components, variants, flows, and a build checklist.

## Shared assumptions

The product uses a dark navy/charcoal bento layout with restrained luminous accents, glass-like panels, and clay-like primary buttons. A logistics city scene shows simulated trucks travelling between a depot and stops; it is an orientation and context layer, not a replacement for accessible tables or controls. Pipeline building, worker status, results, chaos controls, reference comparison, and deployment identity must remain inspectable in ordinary interface components.

The UI talks to the same-origin `/api` REST routes and plain `/ws` stream described in [Control API contract 04](../contracts/04-control-api-rest-ws.md). REST supplies validation, execution, workers, checkpoints, results, chaos actions, comparison, and version data. WebSocket snapshots and updates keep the city and panels current. UI states must use text and colour together. See each document for details and unresolved assumptions.
