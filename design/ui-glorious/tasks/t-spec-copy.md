# Playwright specs must match the new UI wording (after the microcopy pass)

Files: tests/ui/glorious.spec.ts, tests/ui/glorious-demo-path.spec.ts, tests/ui/glorious-recovery.spec.ts (selectors only). Do NOT read or describe swarm/. Do NOT run against any live server or :8088.

A microcopy pass changed visible strings in web/src/glorious (e.g. 'Truck Breakdown', Failed Records tabs 'Rejected'/'Late', history labels 'Worker died', 'Partitions reassigned', 'Checkpoint saved', 'Kill requested (not yet confirmed)', Live World subtitle). Find every text- or role-name-based selector/assertion in the three specs and make it match the CURRENT code exactly; prefer data-testids or aria roles where they exist so future copy changes do not break tests. Keep the safety guards (ALLOW_KILL, :8088 refusal) unchanged. Verify: typecheck the specs and npx playwright test -c playwright.glorious.config.ts --list (Node: prepend %USERPROFILE%\tools\node if needed). Report the changed selectors.
