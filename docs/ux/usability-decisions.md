# Usability decisions

These are proposed design decisions for a planned interface, not results from an implemented UI or usability study. Apply them consistently and validate them in prototype testing.

| Decision | Principle | Application and rationale |
|---|---|---|
| Provide a visible system-status label and last update time. | Nielsen: visibility of system status. | Show execution state and data freshness in the operations view. A WebSocket reconnecting indicator is distinct from engine failure. |
| Make compiler errors actionable and tied to graph locations. | Nielsen: error recognition, diagnosis, and recovery. | Preserve the compiler's error text, identify relevant node/field where possible, and offer a plain-language next step. Do not invent a fix when none is known. |
| Keep graph structure and validation summary visible together. | Nielsen: recognition rather than recall. | Users can relate a message to its source/filter/window without remembering a separate modal. |
| Use separate regions for graph, configuration, monitoring, and administration. | Gestalt: proximity and common region. | Related controls and evidence share panels; admin tasks do not blend into immediate runtime controls. |
| Use repeated component shapes and labels for like operations. | Gestalt: similarity and consistency. | Operator nodes share visual grammar; distinct node type is still written as text, not only indicated by shape/colour. |
| Make primary actions large and near the task context. | Fitts's law. | Validate and Run are easy to target; destructive chaos actions are visually separate and require deliberate confirmation. On small screens, provide adequate spacing and target size. |
| Limit simultaneous choices and group advanced settings. | Hick's law. | Present the common operator set first; reveal detailed configuration in a predictable panel without hiding required fields. Avoid needless wizard steps. |
| Never convey execution state with colour alone. | Accessibility; contract 04. | Each state has text and a distinct colour treatment; include icons or shapes only as redundant cues. Ensure text/background contrast meets WCAG AA 4.5:1 for normal text and 3:1 for large text. |
| Keep compiler/API errors honest and specific. | Nielsen: help users recognize, diagnose, and recover from errors. | Display contract error `code` and plain-English `message`, with validation `errors` where supplied. Explain the next action when supported; do not suppress details or claim an error is fixed. |
| Animate only state changes. | Nielsen: aesthetic and minimalist design; motion sensitivity. | No continuous decorative truck movement is required to understand current state; truck movement may be present as product context but must be pausable/reduced and not carry essential information. Prefer restrained transition on a state change. |
| Respect reduced-motion preferences. | Accessibility and user control. | Read the platform reduced-motion preference; remove parallax, pulsing, and nonessential transitions. Keep state changes immediately visible in text. Provide a pause control for any ongoing nonessential animation. |
| Keep an accessible alternative to the city canvas. | WCAG: multiple ways to perceive information. | Provide truck/status rows and result tables with labels, keyboard access, and screen-reader names. Map coordinates alone are not a usable status summary. |
| Confirm high-impact actions before sending. | Nielsen: error prevention and user control. | Confirm worker-kill and stop actions with their scope; show pending, success, or failure feedback. Avoid implying an action completed before the API responds. |
| Show deployed identity from the API, not a UI constant. | Nielsen: match between system and real world. | Footer uses `/api/version` commit and optional `builtAt`; an empty time is shown as unavailable, not fabricated. |
| Make comparison scope and limits discoverable. | Nielsen: help and documentation; trust. | Explain the completed-checkpoint range and show match counts and mismatch types. A match is evidence for that contract scope, not a blanket engine guarantee. |

## Colour and text for state

The control contract requires text plus colour for running, backpressured, checkpointing, recovering, paused/stopped, and failed. Use the full state labels, not just green/amber/red dots. Backpressured can coexist with a running execution; show it on affected workers and flow indicators rather than silently replacing execution state. Show “Recovering from checkpoint #N” when checkpoint number is available. Distinguish absent data from a numeric zero.

## Contrast and focus

Check final rendered foreground/background pairs with a contrast analyser; token names alone do not prove compliance. Meet WCAG AA 4.5:1 for normal text, 3:1 for large text and meaningful non-text UI boundaries. Add a clearly visible keyboard focus indicator on dark and light surfaces. Do not put essential small text on translucent glass unless the underlying contrast remains sufficient across content variations.

## Interaction details by task

### Pipeline authoring

- Keep Validate separate from Run so the user can inspect compilation without starting an execution.
- Identify which pipeline version is being edited or submitted.
- On invalid input, preserve the graph and field values so correction does not require reconstruction.
- Place schema errors beside the relevant node and retain a complete validation summary.
- Use consistent node names from the supported operator list.

### Operations and recovery

- Keep execution-level status separate from worker-level status.
- A worker marked backpressured does not necessarily mean the whole execution has failed.
- Associate recovery text with a checkpoint ID when known, and with the execution generation where available.
- Show update freshness for values fed by snapshots or periodic updates.
- Use a timeline or event list for worker and execution changes; do not rely on a transient animation.

### Correctness and delivery

- Show comparison result counts and mismatch details within the same view.
- State that mismatch output is capped when the contract limits returned rows.
- Keep `commit` and optional `builtAt` sourced from `/api/version`; do not substitute a hard-coded or cached design value without freshness context.
- If comparison or version data cannot be retrieved, show unavailable and preserve the last known value with its timestamp only if that is safe and clear.

## Accessibility review checklist

- All actions work with keyboard input and have a visible focus indicator.
- Focus order follows the reading and task order; dialogs return focus to the invoking control.
- Controls have accessible names and instructions; icon-only controls are not unlabeled.
- Error messages identify the affected control and are announced without moving focus unexpectedly.
- Tables have headers and meaningful row labels; numbers include units and time context.
- State changes are conveyed by text and not only colour, shape, sound, or motion.
- Text contrast is measured in the final composited state, including hover, focus, and disabled variants.
- Reduced-motion settings remove nonessential continuous and transition effects.
- Zoom and narrow layouts do not hide required actions or force horizontal scrolling for ordinary tasks.

Review against the applicable WCAG version and assistive technologies during implementation; this document is not a conformance claim.
