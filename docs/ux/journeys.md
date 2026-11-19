# User journeys

These are proposed journeys, not observed user research. Stages describe the interface intent; implementation and API availability must be verified before claiming a journey works end to end.

## Build, validate, and run a pipeline

| Stage | Actions | Thoughts | Emotions | Pain points | Opportunities |
|---|---|---|---|---|---|
| Start | Open the visual editor and choose a saved logistics draft or a blank graph. | “Where should I begin?” | Curious, cautious | An empty canvas gives no clue about required nodes. | Offer a small example, visible node palette, and keyboard instructions. |
| Build | Add Source, Filter, Map, Key-by, Window, Rule, and Sink; configure fields and connect ports. | “Does this connection and schema make sense?” | Focused | Dense settings and invalid edges interrupt flow. | Use inline schema summaries, undo, accessible connection controls, and clear node labels. |
| Validate | Select Validate; review `valid`, plan summary, and compiler errors. | “What exactly needs fixing?” | Hopeful, then frustrated if errors are vague | Errors can be detached from their graph location. | Preserve draft, link errors to nodes/fields, show plain compiler message and corrective context. |
| Save | Submit a valid definition to create or advance a pipeline version. | “Which version did I just save?” | Reassured | Accidental overwrite or unclear version changes. | Confirm name/version and show save outcome; do not imply validation started execution. |
| Run | Start an execution with an idempotency key-backed request; inspect returned execution state. | “Has it started, and what is running?” | Anticipatory | Network delay can look like a duplicate action. | Disable duplicate submission while pending, show execution identity and explicit RUNNING/FAILED outcome. |

## Watch live results

| Stage | Actions | Thoughts | Emotions | Pain points | Opportunities |
|---|---|---|---|---|---|
| Connect | Open the operations view; receive the `/ws` snapshot for execution, workers, summary, simulator. | “Is this view current?” | Oriented | Empty or stale values can look like zero. | Show connection state and last update time; distinguish unavailable from zero. |
| Observe | Follow trucks on the city map and read summary, windows, alerts, flow, and worker panels. | “What is changing and why?” | Engaged | Motion can distract; city view alone lacks exact values. | Keep tables and text alternatives available; animate only state changes. |
| Interpret | Inspect events/sec, partitions, late and rejected counts, and latest windows. | “Are results healthy?” | Confident or concerned | Metrics without units or time window are ambiguous. | Label units and update cadence; show timestamps and definitions. |

## Survive a worker failure

| Stage | Actions | Thoughts | Emotions | Pain points | Opportunities |
|---|---|---|---|---|---|
| Inject | In the chaos panel, choose kill worker and confirm the target/action. | “This will interrupt the run; is that intended?” | Deliberate, tense | Destructive actions may be clicked accidentally. | Explain demo impact; require confirmation and expose a safe reset path if implemented. |
| Detect | Observe worker event and execution transition to RECOVERING. | “Did the engine notice the failure?” | Concerned | State changes may arrive asynchronously. | Show event time, affected worker, and live connection status. |
| Recover | Read “Recovering from checkpoint #N” and inspect checkpoint history/generation. | “What state is being restored?” | Cautiously hopeful | Recovery progress may be mistaken for failure or completion. | Keep RECOVERING distinct from RUNNING and expose checkpoint identity. |
| Confirm | Observe RECOVERED event / RUNNING state and resumed flow. | “Did it resume, and what evidence supports that?” | Relieved | Green state alone does not establish output correctness. | Show generation, restored checkpoint, and subsequent output/compare route. |

## Verify correctness with Compare

| Stage | Actions | Thoughts | Emotions | Pain points | Opportunities |
|---|---|---|---|---|---|
| Prepare | Confirm an execution has a completed checkpoint and select Compare with reference. | “What exact range will be checked?” | Careful | Checkpoint prerequisites and replay scope may be unclear. | Explain the contract's checkpoint range and disable or explain unavailable states. |
| Run comparison | Submit `POST /api/compare`; show progress while the request is pending. | “Is it still working?” | Patient, then uncertain | Long comparison without feedback resembles a hang. | Use pending state and elapsed time; do not fabricate partial results. |
| Inspect | Review match status, counts, duration, replayed records, and capped mismatch rows. | “What differs, if anything?” | Confident or investigative | A match badge without details can overclaim correctness. | Provide mismatch type, sink/key/window, and contract limits; make details inspectable. |

## Ship a change through CI/CD and see the footer change

| Stage | Actions | Thoughts | Emotions | Pain points | Opportunities |
|---|---|---|---|---|---|
| Change | Submit a repository change and follow its CI checks. | “Did the tested change pass?” | Focused | UI may not know workflow status. | Link to authoritative workflow evidence if available; avoid inferred pass states. |
| Deploy | Follow image promotion and deployment verification in the actual delivery system. | “Is the running service the tested version?” | Cautious | A published image is not proof of deployment. | Keep release identity separate from deployment status. |
| Inspect | Read the footer commit supplied by `/api/version` (`commit`, `builtAt`). | “What build is serving this page?” | Reassured | Empty `builtAt` or stale UI cache may confuse. | Label commit as deployed build identity, show build time when supplied, and state when unavailable. |

## Cross-journey interaction requirements

- Keep the selected pipeline and execution identity visible when moving from the editor to operations.
- Preserve error context and unsaved draft state when validation fails.
- Use explicit timestamps and units for events, windows, checkpoints, and metrics.
- Distinguish loading, no results, unavailable service, and a genuine zero value.
- Make all workflows usable without interpreting the city visualization.
- Keep any pending API action visibly pending until a response arrives.
- Never treat WebSocket reconnection as proof of engine recovery.
- Keep destructive actions separate from observation and comparison tasks.

These tables describe intended interactions. They do not assert that CI/CD status is available in the UI or that every proposed screen is implemented.

Review the journeys against the REST and WebSocket contracts before implementation changes.

## Implemented feature journeys

These flows describe the current components, not an idealized end-to-end design. Names and states are grounded in the cited files; the 2026-11-19 screenshots in [visual QA](visual-qa.md) do not include these newer panels.

### Savepoint operations — operator

**Goal:** preserve a useful execution point, restore it deliberately, or request rescaling.

1. Open `OpsPanel` (`web/src/app/ops/OpsPanel.tsx`); it loads the current execution and recent savepoints together.
2. Choose **Take savepoint**. The action is disabled without an execution or while a request is pending; success announces the returned savepoint/checkpoint ID and retains at most five recent entries in the panel.
3. Choose **Restore** for a listed point. A browser confirmation names the restart-from-savepoint consequence; cancelling sends nothing. Confirming sends the execution ID, savepoint ID, and fresh idempotency key.
4. Choose **Rescale** to request it. Adjacent text says it pauses at the next checkpoint, moves partitions, and resumes—not live migration.

**Pain points and design response:** restore can disrupt active work, so it is gated by `window.confirm`; pending disables the panel actions and API errors are rendered as alerts. Empty savepoints show “No savepoints yet.” Initial load and action errors share an error message; there is no separate loading indicator or dedicated success message for restore/rescale. The component does not subscribe to later execution updates.

### City scenarios — operator / evaluator

**Goal:** change the simulator conditions and observe their effect.

1. `ScenarioPanel` (`web/src/app/scenarios/ScenarioPanel.tsx`) loads scenario choices and simulator scenario state; built-in choices cover Rush hour, Breakdown, Surge (+6 trucks), and Reset.
2. For Breakdown, choose one of the eight listed truck IDs; otherwise select the scenario button directly.
3. The selected choice is exposed with `aria-pressed` and an “Active” label when the simulator state resolves. During a request the controls are busy and buttons are disabled.
4. Read the inline `role="alert"` message if loading or applying fails.

**Pain points and design response:** scenario actions alter the simulated workload, so each option includes a short explanation and the active selection remains explicit. A 404 when loading the scenario list falls back to built-in choices; if simulator state is not reachable, an error is shown, not a fabricated active state. While an action is pending, further choices are blocked. There is no separate loading label or confirmation dialog.

### Presenter intro and guided tour — evaluator

**Goal:** orient to the city, panels, editor, chaos, recovery, comparison, and deployment identity.

1. `IntroSplash` (`web/src/app/tour/IntroSplash.tsx`) presents a title and Skip button for up to two seconds once per browser session; click or any key dismisses it. It is skipped when reduced motion is preferred.
2. Open `TourOverlay` (`web/src/app/tour/TourOverlay.tsx`) to step through the eight `tourSteps` (`web/src/app/tour/tourSteps.ts`); Next/Back, Close, Finish, ArrowRight/ArrowLeft, and Escape control navigation.
3. Where a selector resolves, a spotlight follows its target through resize and scroll; if it does not resolve, the explanatory card still appears without a spotlight.
4. The current step index persists in local storage and resumes when the tour is opened again.

**Pain points and design response:** selector drift cannot strand the tour because a missing target simply omits highlighting. Keyboard navigation and text descriptions supplement the spotlight; intro motion respects reduced motion. The tour is non-modal (`aria-modal="false"`), has no focus trap or automatic focus management, and its narrative can outpace feature availability; steps describe intended feature affordances, not proof that an API call succeeded.

### Pipeline example picker — pipeline author

**Goal:** start from a supplied example, validate its graph, and start a valid pipeline.

1. Open `PipelineEditor` (`web/src/app/editor/PipelineEditor.tsx`); its Examples select loads options from `editorApi.ts`.
2. Choose an example to replace the current graph; if the examples endpoint returns 404, the picker is replaced by **Load demo**. Other load failures appear in the feedback area.
3. Inspect and edit the selected definition using the operator palette, graph, and configuration inspector; choosing an example clears old validation feedback.
4. Select **Validate**. Compiler errors remain in a feedback region with code, message, and node ID; **Show me** selects and frames the associated node. A valid plan reports operator/partition counts and order.
5. **Run** is disabled until validation is valid. It saves the current definition and starts an execution; success reports the execution ID through the callback and closes the editor.

**Pain points and design response:** a starter graph reduces blank-canvas effort; errors remain tied to nodes and the editable graph is not replaced on validation failure. The editor offers no distinct Save-only action, no explicit restore of unsaved state after close, and no separate empty/loading/error panel for example retrieval (the select is disabled while choices load; errors use the feedback text). API error `message` text is shown verbatim, including a 409 message when one is returned; otherwise the editor shows the response status fallback. This is not a guarantee that every conflict is recoverable.

### Checkpoint and recovery timeline — operator / evaluator

**Goal:** identify recent checkpoint, worker-death, and recovery events and the checkpoint used.

1. `CheckpointTimeline` (`web/src/app/timeline/CheckpointTimeline.tsx`) loads current execution and up to 20 checkpoints, then refreshes every five seconds while the document is visible.
2. WebSocket snapshots seed execution and worker liveness; worker updates can record a transition from alive to dead, and execution events are added to a rolling event list capped at 100.
3. Read the two-minute horizontal timeline, status label, generation, latest completed checkpoint/duration, and restored-from checkpoint. Scroll is kept at the newest edge.
4. If polling fails, “Timeline refresh failed” is shown; if no execution exists, status reads “Waiting” and no checkpoints are listed.

**Pain points and design response:** recovery is shown as a time band with checkpoint and generation, alongside worker-loss and recovered markers; text details and an `aria-live` update complement the graphical track. The track has a minimum width and horizontal scrolling. Its canvas is exposed as `role="img"` with a label and marker names, but no separate equivalent event table is rendered. Empty state is implicit (“Waiting”), not a dedicated explanation. The display is bounded to two minutes and depends on received events plus checkpoint polling; it is not a durable full audit log.
