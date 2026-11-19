# T93: Compare-with-reference for executions restored from a savepoint; turn on extended e2e  [M3]

Lanes 3 + 8. T86 (merged) fixed restored executions so they complete checkpoints. Now ci-e2e step 9 (infra/scripts/demo-e2e.sh,
with E2E_EXTENDED=1) fails at the compare: for the restored execution every window shows
"eventCount differs: expected 27, actual 30" (engine 3 higher). The engine is right: it restored window state from the
savepoint, which already counted events from before the savepoint. The reference in CompareService only replays from the
restored execution's own start offsets. Fix the compare so a restored execution is compared correctly, for example replay the
reference from the lineage root (the original execution's start offsets, following restored-from links) up to the restored
execution's checkpoint offsets and compare the windows the restored execution wrote; or seed the reference from the savepoint's
source offsets. Pick the simplest correct option and explain it in your report. Add a control-api unit test for the
restored-lineage case. Then set E2E_EXTENDED: "1" in .github/workflows/ci-e2e.yml.

Files (edit only these): control-api/src/main/java/io/aetherstream/api/compare/**, control-api/src/test/java/**/compare/**,
reference/src/main/java/** (only if a small helper is needed), .github/workflows/ci-e2e.yml (only the E2E_EXTENDED line),
infra/scripts/demo-e2e.sh (steps 9-11 only, if a step's expectation is wrong; say why).

Acceptance check: ci-java and ci-e2e green on your branch with all 11 steps PASS (E2E_EXTENDED=1).

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql (if a contract looks wrong, say so in your report).
- Do not read or change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only edit the files listed. If you are on vivek-pc: do NOT start Docker containers or kind there (the live demo runs on it). Acceptance runs in GitHub Actions on your task branch.
