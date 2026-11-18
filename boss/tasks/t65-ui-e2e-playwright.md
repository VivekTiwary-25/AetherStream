# T65: Browser end-to-end test of the real UI against the real stack (Playwright in CI)

Lane 8 (tests). Prove in CI that the deployed-style UI shows ENGINE-computed numbers and the demo flows work in a browser.
The whole stack starts with `docker compose up -d --build` (root docker-compose.yml; web on http://localhost:8088, API on
18080). infra/scripts/demo-e2e.sh shows how to start the demo pipeline via the API. Read web/src/app/** and web/src/city/**
to find stable selectors (prefer roles/labels/text; add NO data-testids to app code - you may not edit web/src).

Files (create only these):
- tests/ui/package.json (Playwright ^1, type module), tests/ui/playwright.config.ts (baseURL http://localhost:8088,
  chromium only, video + trace on failure, screenshots always), tests/ui/demo.spec.ts, tests/ui/README.md
- .github/workflows/ci-ui-e2e.yml

demo.spec.ts (each step soft-asserted with clear messages; take a full-page screenshot after each step):
1. Page loads; title "AetherStream"; footer shows the deployed version = env EXPECT_SHA's first 7 chars; NO "MOCK DATA" badge.
2. Start the demo pipeline (via the UI button if present, else via the API with fetch) and wait until the speed panel shows
   at least one truck row with a km/h number and the workers panel shows 3 workers.
3. Cross-check: one km/h value shown in the UI equals (to 1 decimal) the matching row from GET /api/results/windows - the
   panel must show engine numbers, not invented ones.
4. Pipeline editor: open it, load the demo, Validate -> success; (if an example "broken-for-demo" is offered, load it and see
   3 errors).
5. Chaos: click "Kill worker", confirm; within 20 s the UI shows "Recovering from checkpoint #N" (or the execution banner
   shows RECOVERING), then back to running.
6. Compare: click "Compare with reference"; within 60 s "N / N results match" appears.
Upload screenshots, videos and traces as artifacts (always).

ci-ui-e2e.yml: on push to main (paths web/**, control-api/**, engine/**, tests/ui/**, the workflow) and task/**,
workflow_dispatch; ubuntu-latest; Node 22; `GIT_SHA=${{ github.sha }} docker compose up -d --build --wait`;
`npx playwright install --with-deps chromium`; run with EXPECT_SHA; always dump compose logs + down -v. Use current action
majors (checkout@v7, setup-node@v7, upload-artifact@v7). Timeout 35 min. Make the job `continue-on-error: true` for now and
say in your result which steps pass (the UI integration task may still be landing).

Acceptance check: ci-ui-e2e runs on your branch, produces screenshots as artifacts; report per-step pass/fail in your result.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql.
- Do not change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only create/edit the files listed under "Files". Do not edit web/src (report UI bugs in your result instead).
