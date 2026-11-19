# Fix the failing recovery test (recovery.integration.test.tsx:45)

Files: web/src/glorious/recovery/** only. Do NOT read or describe swarm/. Node: if not on PATH prepend %USERPROFILE%\tools\node (Node 26: NODE_OPTIONS=--no-experimental-webstorage for vitest). Before finishing: in web/ npm run lint, the FULL npm test -- --maxWorkers=2, npm run build, npm run check:no-mock must all pass.

On current ui-glorious, the full web test suite fails at web/src/glorious/recovery/recovery.integration.test.tsx:45. A recent change made useRecoveryFeed.ts retry history/execution every 5 s and clear errors on success; it was merged without running tests. Find whether the test or the code is wrong (fake timers, retry interval, unmount cleanup, double fetch) and fix the real cause (do not just delete or skip the test). Report the cause in 2 lines.
