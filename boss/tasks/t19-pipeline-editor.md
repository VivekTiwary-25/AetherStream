# T19: Visual pipeline editor (drag blocks, connect, Validate with compiler errors, Run)

Lane 6 (web/app). Build the pipeline editor with @xyflow/react 12. It edits the pipeline JSON of contract 02 section 1
and talks to the REST API of contract 04 (`/api/pipelines/validate`, `/api/pipelines`, `/api/pipelines/demo`,
`/api/pipelines/{id}/executions`).

Files (create/edit only these):
- web/src/app/editor/** (PipelineEditor, BlockPalette, node components per type, ConfigInspector, ValidationList,
  pipelineModel.ts with pure conversion functions between xyflow nodes/edges and PipelineDefinition)
- web/src/app/editor/__tests__/**
- web/package.json + web/package-lock.json: add `@xyflow/react` ^12 only
- Do NOT edit web/src/api/**, web/src/app/panels/**, web/src/app/shell/** or web/src/city/** (other tasks own them). Use
  `fetch` directly with relative URLs inside web/src/app/editor/editorApi.ts and define the few types you need in
  web/src/app/editor/types.ts (field names exactly as contract 02/04). The shell will mount `<PipelineEditor />` in a later
  integration task: export it as the default export of web/src/app/editor/PipelineEditor.tsx with props
  `{ open: boolean; onClose(): void; onStarted?(executionId: string): void }` (it renders as a large glass overlay/drawer).

Behaviour:
1. Palette with 7 blocks: Source, Filter, Map, Key-by, Window, Rule, Sink (icons + short description). Drag onto the canvas
   to add (generates ids like `filter-2`). Connect handles to create edges; delete with Backspace/Delete.
2. "Load demo" loads GET /api/pipelines/demo and lays it out using node `position` if present, else a simple left-to-right
   layered layout computed from the edges.
3. Inspector panel: edit the selected node's config with small forms per type (filter conditions list with field/op/value,
   map ops, keyBy field select, window sizeMs/outOfOrdernessMs/aggregations, rule condition/forWindows/message,
   sink kind select, source topic/startFrom). Also a "JSON" tab showing the full pipeline JSON (read-only, monospace).
4. Validate button -> POST /api/pipelines/validate. Show errors in a ValidationList (plain-English message, code chip) AND
   highlight the offending nodes on the canvas (red outline + small badge); clicking an error selects the node. Show a clear
   "Valid - N operators, 6 partitions" success state with the plan order.
5. Run button (clay primary, disabled until valid): POST /api/pipelines then POST /api/pipelines/{id}/executions with a random
   idempotencyKey; show "Starting..." then call onStarted(executionId) and close.
6. Styling: glass overlay on the dark theme (tokens.css variables), nodes as soft clay-like rounded cards with a coloured
   left accent per type, animated edge only while running/validating, minimap + controls styled dark. Keyboard accessible.

Tests (Vitest): pipelineModel round trip (demo JSON -> flow -> JSON is equal, ignoring positions); layered layout gives
increasing x along edges; ValidationList renders messages and maps nodeIds; Run button disabled until a successful validate.

Acceptance check: `ci-web` workflow green on your branch.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql. If a contract looks wrong, follow it anyway and say so in your result.
- Do not change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only create/edit the files listed under "Files". If you truly need another file, say so in your result instead of editing it.
- Your PC may not have Java, Node or Docker. The acceptance check runs in GitHub Actions on your task branch (CI runs on push to task/**). Write code carefully so it compiles first time; keep it simple and readable.
- Do not commit secrets, build outputs, node_modules or .gradle/ folders.
- In your result, list what you built, how the acceptance check proves it, and anything left undone.
