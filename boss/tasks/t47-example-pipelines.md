# T47: Example pipeline library (for the editor demo) + compiler tests

Lane 2 (compiler). The compiler is on main (compiler/src/main/java/io/aetherstream/compiler/PipelineCompiler.java) with the
demo pipeline at compiler/src/main/resources/pipelines/logistics-demo.json. Add more examples, following contract 02 exactly.

Files (create/edit only these):
- compiler/src/main/resources/pipelines/speeding-alerts.json: position events -> keyBy key -> 1-minute window with
  maxSpeedKmh -> rule "speeding" (maxSpeedKmh > 45 for 2 windows, message "{key} was speeding for {minutes} min") -> alerts sink,
  plus the window results to a windows sink.
- compiler/src/main/resources/pipelines/fleet-activity.json: all valid events -> map (scale payload.speedKmh by 0.27778 as
  payload.speedMs) -> keyBy payload.truckId -> 5-minute window (count, avg speedMs) -> windows sink.
- compiler/src/main/resources/pipelines/broken-for-demo.json: the demo pipeline with exactly 3 mistakes a presenter can fix live
  (window without key-by, a dangling filter, a rule sink of the wrong kind). Must produce exactly 3 errors.
- compiler/src/main/resources/pipelines/index.json: `[{"id","name","description","file"}]` for all four (demo first).
- compiler/src/main/java/io/aetherstream/compiler/PipelineLibrary.java: `static List<Entry> list()` and
  `static PipelineDefinition load(String id)` reading those resources with io.aetherstream.common.Json.MAPPER.
- compiler/src/test/java/io/aetherstream/compiler/PipelineLibraryTest.java: every example except broken-for-demo validates;
  broken-for-demo returns exactly the 3 expected codes with node ids; node positions are present for a left-to-right layout.

Every node in the new JSON files should have a `label` and a `position` ({x,y}, left-to-right, 220 px apart) so the editor
lays them out nicely.

Acceptance check: `ci-java` green on your branch.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql.
- Do not change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only create/edit the files listed under "Files".
- The acceptance check runs in GitHub Actions on your task branch. Write code carefully so it compiles first time.
- In your result, list what you built and anything left undone.
