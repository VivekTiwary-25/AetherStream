# Design thinking

## Empathise

The current persona set is a planning aid, not research. The handoff identifies pipeline authors, operators, organisation administrators, and evaluators as intended users. Each needs trustworthy feedback, but their primary tasks differ: graph creation, operational response, access management, and evidence review.

Before finalising the design, interview or observe representative users. Ask them to explain a pipeline error, identify a backpressured worker, interpret checkpoint recovery, and judge a comparison result. Record consented notes and distinguish quotes from interpretation. Do not report assumed frustrations as findings.

## Define

### Problem statements

- A pipeline author needs to find and fix graph and schema errors before execution, because a valid-looking graph may not be executable.
- An operator needs to distinguish active processing from backpressure and recovery, because a single colour or generic “healthy” badge can hide important states.
- An evaluator needs inspectable correctness and deployment evidence, because animation alone cannot show whether outputs match the reference or which commit is running.

### How Might We questions

- How might we connect compiler feedback to the exact graph location without hiding the compiler's useful detail?
- How might we make backpressure and checkpoint recovery understandable without suggesting that recovery has completed early?
- How might we make the logistics city engaging while keeping all operational data available in readable, non-animated forms?
- How might we show reference-comparison evidence and limits without implying a broader exactly-once guarantee?
- How might we identify the deployed build clearly when `builtAt` is empty or a connection is stale?

## Ideate

### City metaphor versus plain dashboard

| Alternative | Benefits | Risks | Decision |
|---|---|---|---|
| Plain metrics dashboard only | Dense, familiar operational data; straightforward tables and charts. | Less intuitive for the delivery workload; weak sense of where trucks and stops fit. | Retain as the data layer and offer table-first views. |
| City-only simulation | Strong visual story; makes vehicles and stops easy to locate. | Can obscure exact values, accessibility, and engine state; motion may distract. | Reject as the only view. |
| City scene plus glass-panel bento dashboard | Connects logistics events to engine results while preserving panels for exact metrics. | Requires careful visual hierarchy and responsive layout. | Preferred proposal; city is contextual, panels remain authoritative. |

### Other alternatives

- **Validation:** modal-only errors vs. inline graph-linked errors. Prefer inline links plus a persistent summary so errors are discoverable without blocking the whole workspace.
- **Pipeline editing:** free-form drawing vs. typed operator nodes and ports. Prefer typed nodes to expose supported graph structure and validation context.
- **Failure feedback:** colour/animation vs. state label, timestamp, and checkpoint detail. Prefer all relevant text and data; motion is supplemental.
- **Correctness:** a single “pass” badge vs. counts and mismatch details. Prefer summary plus inspectable evidence and explicit scope.

## Prototype

Low-fidelity sketches below are layout hypotheses, not screenshots or a completed product.

### Operations view

```text
+----------------------------------------------------------------+
| AetherStream  [Execution: RUNNING] [connection: live]          |
+-------------------------------+--------------------------------+
| CITY / LOGISTICS              | LIVE SUMMARY                   |
| depot  o---- truck ----> stop | speed avg | deliveries / min    |
|       route + status labels   | events/s  | alerts              |
+-------------------------------+--------------------------------+
| FLOW / WORKERS                | WINDOWS / ALERTS               |
| partition rates, backlog      | readable rows + timestamps    |
+-------------------------------+--------------------------------+
| CHECKPOINTS / EVENTS          | CHAOS (confirm before action)  |
+----------------------------------------------------------------+
| deployed commit: <commit>                                  |
+----------------------------------------------------------------+
```

### Pipeline editor

```text
+----------------------------------------------------------------+
| Pipelines / Draft vN           [Validate] [Save] [Run]          |
+----------------+-------------------------------+---------------+
| NODE PALETTE   | GRAPH                         | CONFIGURATION |
| Source         | Source -> Filter -> Map        | selected node |
| Filter         |                    \           | schema, fields|
| Map            | Key-by -> Window -> Rule -> Sink| help + errors |
| ...            |                               |               |
+----------------+-------------------------------+---------------+
| VALIDATION: 1 error  [node name] message and corrective action  |
+----------------------------------------------------------------+
```

### Figma plan

Build frames and component variants in [Figma plan](figma-plan.md). Start with desktop operations and pipeline editor, then test a narrower responsive layout and keyboard-only interactions. Prototype state changes only where they clarify an event; honour reduced-motion settings.

## Test

### Small usability test plan

Recruit five participants across the intended roles if feasible; document role mix and limitations. Five is a formative sample, not a statistically representative study. Use a working prototype or clearly label simulated interactions. Obtain consent, do not collect credentials or production data, and ask participants to think aloud without leading them.

| Task | Success criteria |
|---|---|
| 1. Fix an invalid pipeline and validate it. | Participant locates the error, makes a plausible correction, and identifies whether validation passed without facilitator direction. |
| 2. Find the latest average speed and delivery rate. | Participant finds both values and identifies their displayed units/time window. |
| 3. Identify a backpressured worker and explain its state. | Participant finds the worker, reads the text state, and distinguishes it from failed or recovering. |
| 4. Recover from a worker failure and name the checkpoint. | Participant identifies RECOVERING, finds checkpoint number, and waits for a separate recovered/running state. |
| 5. Compare results and identify what a mismatch means. | Participant finds match/mismatch summary and can inspect a mismatch or explain that no mismatch was reported. |

Record task completion, missteps, help requests, time as descriptive data, and participant wording. Report actual observations only after sessions; do not invent outcomes. Revise the prototype based on repeated or high-severity problems.

### SUS questionnaire

After tasks, administer the standard 10-item System Usability Scale (SUS) with the original response wording and 5-point agree/disagree scale. Alternate positive and negative item scoring as specified by the SUS method, convert the total to the 0–100 SUS score, and report the sample size and method. SUS is not a percentage and five participants do not establish a population score. Do not fill in scores before data collection.
