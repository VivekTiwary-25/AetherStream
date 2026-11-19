# T155: Full expo rehearsal of DEMO.md against the LIVE deployed app  [expo safety, Claude]

You are rehearsing the expo demo for 21 Nov exactly as the presenter will do it. Live app: the public link in boss/STATE.md
("Public link", a trycloudflare.com URL; read the latest from boss/NEEDS_VIVEK.md if they differ). Check /api/version first and
note the deployed commit.
1. Follow DEMO.md step by step, in order, with a timer. Drive the browser with Playwright (headed Chromium if you can, else
   headless with GPU flags) at 1440x900. Take a screenshot after EVERY step and every visible state change (kill -> red ->
   Recovering from checkpoint #N -> Recovered; compare "N / N results match"; backpressure amber; late events in Dead letters;
   footer version). Save them in docs/expo/rehearsal-1/NN-step-name.png.
2. Do the steps a human would do in a browser too (GitHub Actions pages, Grafana if reachable) only as far as DEMO.md says.
3. Write docs/expo/rehearsal-1.md: for each DEMO.md step - expected (quote DEMO.md), what actually happened, time taken,
   screenshot link, verdict OK / BROKEN / SLOW / UGLY / CONFUSING. Then a ranked fix list: P1 (would embarrass us on stage),
   P2, P3, each with the exact place (file/component if you can tell) and a concrete fix. Note total demo time vs DEMO.md's
   12-minute plan.
Do NOT change app code in this task (another Claude fixes the list). Docs and screenshots only.

Files (edit only these): docs/expo/rehearsal-1.md (new), docs/expo/rehearsal-1/** (new).

Acceptance check: ci-mock green on your branch; every DEMO.md step has a row and a screenshot.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql (if a contract looks wrong, say so in your report).
- Do not read or change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only edit the files listed. If you are on vivek-pc: do NOT start Docker containers or kind there (the live demo runs on it). Acceptance runs in GitHub Actions on your task branch.
