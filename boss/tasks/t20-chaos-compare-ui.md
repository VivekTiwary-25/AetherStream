# T20: Chaos panel + "Compare with reference" view

Lane 6 (web/app). Two glass panels that drive the live demo, using contract 04 endpoints:
`POST /api/chaos/late-events`, `/api/chaos/duplicates`, `/api/chaos/malformed`, `/api/chaos/slow-sink`,
`/api/chaos/kill-worker`, `POST /api/compare`, `GET /api/workers`, `GET /api/executions/current`.

Files (create only these):
- web/src/app/chaos/** (ChaosPanel, ComparePanel, chaosApi.ts with fetch helpers + local types named exactly as contract 04)
- web/src/app/chaos/__tests__/**
- Do NOT edit web/src/api/**, web/src/app/shell/**, web/src/app/panels/**, web/src/app/editor/** or web/src/city/**.
  Export `ChaosPanel` and `ComparePanel` as named exports from web/src/app/chaos/index.ts; a later integration task mounts them.

ChaosPanel:
- "Send late events" (count 20, lateBy 3 sim-min) -> shows "20 late events sent" toast-like inline confirmation.
- "Send duplicates" (20) and "Send malformed" (5), smaller ghost buttons.
- "Slow down output" toggle (off / 50 ms / 200 ms per record) -> POST slow-sink; while on, the toggle glows amber and the panel
  says "Output slowed: queues fill up (bounded at 1,000) and the engine pushes back".
- "Kill worker" (danger clay button) -> opens an inline confirm ("Kill worker w-...? It will crash without warning.") with a
  worker selector defaulting to "busiest", then POST kill-worker; show the killed worker id.
- Buttons disable while a request is in flight; errors are shown inline in plain English.

ComparePanel:
- "Compare with reference" clay primary -> POST /api/compare (can take a few seconds: show a progress shimmer).
- Result: big "4,812 / 4,812 results match" (numbers formatted with thousands separators) in green when `match`, else
  amber/red with "X mismatches" and a small table of the first mismatches (type, sink, key, window start, detail).
  Subline: "Checked up to checkpoint #N - replayed R events in D ms". Explain in one muted line what the reference is
  ("a single-process executor running the same operators on the same Kafka events").

Use tokens.css variables, GlassPanel / ClayButton / StatusPill from web/src/app/ui/ (import them, do not edit them).
Motion only on state changes; respect prefers-reduced-motion.

Tests (Vitest + Testing Library, mock fetch): kill flow requires confirmation; slow-sink toggle posts the right delays;
compare renders "4,812 / 4,812 results match" for a matching response and lists mismatches for a failing one.

Acceptance check: `ci-web` workflow green on your branch.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql. If a contract looks wrong, follow it anyway and say so in your result.
- Do not change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only create/edit the files listed under "Files". If you truly need another file, say so in your result instead of editing it.
- Your PC may not have Java, Node or Docker. The acceptance check runs in GitHub Actions on your task branch (CI runs on push to task/**). Write code carefully so it compiles first time; keep it simple and readable.
- Do not commit secrets, build outputs, node_modules or .gradle/ folders.
- In your result, list what you built, how the acceptance check proves it, and anything left undone.
