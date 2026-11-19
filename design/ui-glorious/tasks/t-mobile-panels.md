# Android stretch, part 3: analytics and recovery windows on phones

Files: web/src/glorious/analytics/**, web/src/glorious/recovery/** (CSS/markup only). Do NOT read or describe swarm/. Do NOT start Docker. Before finishing: in web/ npm run lint, npm test -- --maxWorkers=2, npm run build, npm run check:no-mock must pass. STRETCH GOAL: desktop must stay pixel-identical: every change inside @media (max-width: 768px). Prove with production-build Playwright screenshots at 1440x900 before/after (must match) and 390x844 after, saved to design/ui-glorious/proof/mobile/.

On phones (windows become full-width bottom sheets via the shell, built in parallel): make the Analytics windows (tables, charts, Failed Records detail) and Logs & Recovery (worker cards stack vertically, partition chips wrap, history list readable, checkpoint table scrolls horizontally) readable at 390 px wide with 44 px touch targets. No logic changes.
