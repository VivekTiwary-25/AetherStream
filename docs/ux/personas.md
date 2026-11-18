# Proto-personas

These are **proto-personas** based on the handoff's intended-user roles, not findings from interviews or observation. The quotes are invented placeholders that express design assumptions; do not present them as real user statements. Validate or revise every assumption with actual users when possible.

## 1. Pipeline author

- **Context:** Defines a logistics stream graph from source through processing operators to a sink.
- **Goals:** Connect Source, Filter, Map, Key-by, Window, Rule, and Sink nodes; understand schema and event-time semantics; validate before saving or running.
- **Frustrations (assumed):** Graph errors can be hard to locate; configuration fields may use unfamiliar engine terms; a successful-looking graph may still have incompatible schemas.
- **Needs from the UI:** Clear node ports and connections; keyboard-accessible editing; inline field help; validation tied to the relevant node and edge; compiler error text with a practical next step; visible distinction between validate and run.
- **Assumed quote:** “Show me exactly which connection is invalid before I start workers.”
- **Design implication:** Keep validation results close to the graph and preserve the draft after errors.

## 2. Operator

- **Context:** Watches running executions, workers, throughput, checkpoints, and output health.
- **Goals:** Spot slow output or worker failure; understand what the engine is doing; select a safe recovery action; verify that a run resumed.
- **Frustrations (assumed):** A single green indicator can conceal backpressure or stale data; recovery terminology may not explain the selected checkpoint; alerts without time or source are difficult to act on.
- **Needs from the UI:** Persistent labelled execution state; worker health and flow panels; checkpoint history; clear confirmation before disruptive chaos or stop actions; a timeline for RECOVERING and RECOVERED events.
- **Assumed quote:** “Tell me whether the pipeline is moving, waiting, or recovering—and from which checkpoint.”
- **Design implication:** Show state, last update time, and relevant checkpoint together; avoid relying on animation alone.

## 3. Organisation administrator

- **Context:** Oversees team membership, ownership, and access to pipelines and execution evidence.
- **Goals:** Know who owns a pipeline; manage membership; understand who can change or run it; keep important actions attributable.
- **Frustrations (assumed):** Access boundaries may be invisible; ownership changes can be ambiguous; administration controls mixed into the live operations view are easy to misuse.
- **Needs from the UI:** A distinct administration area; explicit role and ownership labels; confirmation and outcome feedback for access changes; audit context where supported by the product.
- **Assumed quote:** “Make it obvious who can change this pipeline and what will happen when I update access.”
- **Design implication:** Separate administration from immediate execution controls and disclose permission scope before submission.

## 4. Evaluator / examiner

- **Context:** Observes a repeatable demonstration and inspects evidence of correctness, failure recovery, and deployment.
- **Goals:** Follow a coherent end-to-end story; see a deterministic workload; understand late-event treatment, backpressure, recovery, comparison, and deployed version.
- **Frustrations (assumed):** A dramatic animation is not proof; labels can overstate guarantees; evidence is hard to find when spread across screens.
- **Needs from the UI:** A guided sequence with visible inputs and outcomes; reference-comparison result and mismatch details; checkpoint and worker events; deployed commit shown in the footer; plain explanations of limits.
- **Assumed quote:** “Let me inspect the evidence behind the demo, not just watch it move.”
- **Design implication:** Pair the city visualization with inspectable tables, timestamps, and contract-aligned explanations.

## Validation questions

- Which terminology is familiar to pipeline authors and operators?
- Do people understand that a stopped execution is not the same as a paused one?
- Which evidence best helps evaluators judge correctness without implying unsupported guarantees?
- Can administrators complete access tasks without entering the operational control path?
 
## Cross-persona design tensions

| Tension | Design response |
|---|---|
| Authoring depth vs. quick start | Put common operator fields first; keep schema and event-time explanations discoverable. |
| Operational density vs. clarity | Use a stable hierarchy and allow detail inspection without hiding the current execution state. |
| Engaging city view vs. accessible evidence | Keep the city contextual and provide equivalent text, tables, and keyboard-accessible controls. |
| Demonstration narrative vs. honest limits | Show the same states and evidence used by the actual engine; label assumptions and unavailable data. |

## Assumptions to validate

- Authors can understand typed nodes and ports without prior exposure to this editor.
- Operators can distinguish a backpressured worker from a failed execution.
- Administrators prefer access controls outside the live run/chaos area.
- Evaluators want to inspect checkpoint identity, mismatch details, and deployed commit.
- Quotes on this page are illustrative only and must never be cited as interview evidence.
