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
