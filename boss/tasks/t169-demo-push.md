# T169: The live CI/CD moment - one safe demo push, and a fast push-to-deployed path  [expo safety]

Lane 7. In the demo the presenter pushes a commit, GitHub Actions builds and deploys to vivek-pc, and the footer version
changes. The examiner will be waiting, so it must be safe and quick.
1. infra/scripts/demo-push.sh: makes a harmless visible change (bump a counter in web/public/expo-build.txt or a similar file
   the footer can show next to the commit, e.g. "Expo build #7"), commits "demo: expo build #N", pushes to main, prints the
   Actions URL for that commit, then polls (gh if available, else the public API) until the cd deploy job for that SHA finishes,
   and finally polls http://localhost:18080/api/version until it equals the new SHA. Print elapsed time per phase.
2. Measure the current push-to-deployed time from recent main runs (public API: cd workflow created_at -> completed for the
   deploy job) and make that path faster where it is safe: e.g. a paths filter so a web/public/expo-build.txt-only push does not
   trigger the long test workflows (ci-integration, ci-e2e, ci-soak, ci-longrun, ci-kill-repeat, ci-k8s, ci-terraform, bench),
   build only the web image for such a push and reuse the last deployed SHA's other images (only if cd.yml's pull-by-SHA logic
   allows it cleanly - otherwise skip and explain), and use the BuildKit cache.
3. Show the counter in the footer (web/src/app/shell/Footer.tsx) next to the commit, read at runtime from /expo-build.txt.
4. Add the expected timing to DEMO.md's CI/CD step ("push -> deployed in about N min, say this while waiting").
Do not change deploy security rules (main only, runner label, pull by SHA).

Files (edit only these): infra/scripts/demo-push.sh (new), web/public/expo-build.txt (new), web/src/app/shell/Footer.tsx,
.github/workflows/*.yml (paths filters only, plus cd.yml image-selection logic if clearly safe), DEMO.md (CI/CD step only).

Acceptance check: ci-web green on your branch; report the measured current push-to-deployed time and the expected new time.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql (if a contract looks wrong, say so in your report).
- Do not read or change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only edit the files listed. If you are on vivek-pc: do NOT start Docker containers or kind there (the live demo runs on it). Acceptance runs in GitHub Actions on your task branch.
