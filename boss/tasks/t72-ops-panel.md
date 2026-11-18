# T72: Operations panel: savepoint, restore from savepoint, rescale (controlled pause)

Lane 6 (web/app). Endpoints on main (control-api): `POST /api/executions/{id}/savepoints` -> `{savepointId, checkpointId,
createdAt}`, `GET /api/savepoints` -> list, `POST /api/executions/{id}/restore` `{savepointId, idempotencyKey}` (409
`INCOMPATIBLE_SAVEPOINT` with a plain-English message), `POST /api/executions/{id}/rescale` (controlled pause at a
checkpoint, then partitions spread over live workers). Current execution: `GET /api/executions/current`.

Files (create only these):
- web/src/app/ops/** (OpsPanel.tsx, opsApi.ts with fetch helpers + local types, index.ts exporting `OpsPanel`)
- web/src/app/ops/__tests__/**
Do NOT edit any other file (an integration task mounts the panel).

UI (glass panel, clay buttons, text + colour states): "Take savepoint" (shows "Savepoint sp-… at checkpoint #N"),
a short list of recent savepoints each with "Restore" (confirm first: "Restart the pipeline from this savepoint?"), and
"Rescale" with a one-line honest explanation: "Pauses at the next checkpoint, moves partitions, resumes - a controlled
pause, not live migration." Show INCOMPATIBLE_SAVEPOINT messages verbatim. Disable buttons while requests run.

Tests (Vitest, mock fetch): savepoint flow; restore requires confirm and posts savepointId; 409 message shown; rescale posts.

Acceptance check: `ci-web` green on your branch.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql.
- Do not change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only create/edit the files listed under "Files".
- If you are running on vivek-pc: do NOT start Docker containers there.
- In your result, list what you built and anything left undone.
