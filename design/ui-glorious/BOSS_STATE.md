# BOSS_STATE — ui-glorious mission (20 Nov 2026)

Fresh boss session: read swarm/BOSS.md, then design/ui-glorious/BOSS_PROMPT.md (the mission), then this file. Continue from "Next".

## Where things are
- Boss clone (Vivek's, on main): D:\projects\AetherStream. Do NOT switch its branch.
- Boss worktree for ui-glorious: D:\projects\AetherStream-ui-glorious (branch ui-glorious, tracks origin). Commit BOSS_STATE here.
- Swarm CLI: `python D:/projects/AetherStream/swarm/swarm.py <cmd>` (no `swarm` on PATH in Git Bash).
- ALWAYS queue with `--base ui-glorious`. review/merge then read base from the queue file automatically.
- Scratch task files: C:\Users\Arena\AppData\Local\Temp\claude\9bd6fc60ba5a58ea\16d887ba-e384-4a57-acb6-8ed0a070706d\scratchpad\tasks (may vanish; tasks are also in swarm/queue on main).

## Vivek's rules for tonight (from chat)
- Nothing into main except the swarm --base commit (d6fef4e). Swarm queue bookkeeping commits on main (swarm/queue/**) are normal and CD ignores swarm/**.
- Leave T181, T185-T188 (t261119-1044-fd00, -1045-44f5, -1045-55b8, -1045-dba5, -1047-b1bb) UNMERGED.
- Keep boss context lean; delegate; summarise. Warn Vivek early if Opus is getting low and write this file so he can resume on vachana-claude.
- No blocking commands; timeouts everywhere; servers in background.
- vivek-pc: no builds. Sleep/hibernate on AC already 0 (checked 01:00, no change needed).
- Self-heal: requeue tasks of dropped workers elsewhere.

## Done
- 00:55 Step 0 report. All 4 PCs up. Codex left: utkarsh 77, vimlesh 55, upendra 85, vivek 76. Grok 12% (avoid). Claude usage unknown (omp anthropic login expired; Vivek may run `omp login anthropic`).
- 00:58 Branch ui-glorious created (f8901af): design/ui-glorious/{design-pack.md, home-mockup.png, liveworld-mockup.png, BOSS_PROMPT.md, home-loop.mp4 (= "the home video.mp4", 1264x720, 6 s, 10 MB)}.
- 01:05 Swarm --base change: local fake e2e test PASSED (t261120-0102-339b: branch from ui-glorious, review vs ui-glorious, merged into ui-glorious 52091e0, main untouched). Pushed to main as d6fef4e. CD did NOT trigger (cd.yml ignores swarm/**); live demo containers stayed up (14 h uptime).
- 01:08 Track D auth check: `CLAUDE_CONFIG_DIR=~/.swarm/profiles/vachana-claude/claude claude -p --model claude-haiku-5-5 --tools "" --strict-mcp-config --no-session-persistence --output-format json --system-prompt "..."` with prompt on stdin -> valid JSON in 5 s (Claude Code 2.1.328, Pro sub). Don't use --bare (may skip OAuth).

- 01:10 Real one-PC test PASSED: t261120-0108-1c34 (utkarsh-pc/codex-luna) stripped audio + made home-poster.jpg, merged into ui-glorious (b5fec7b); main has only swarm/** commits. All listeners on code d5d007b0.
- Note: workers may refuse to write swarm/reports/<id>.md (CLAUDE.md swarm rule); the fallback report captures their final output. Fine.

## Vivek's notes 02:29 (binding)
1. Docker deadline 03:00 on utkarsh-pc. Then move full stack to vimlesh/upendra if Docker works there. Else TELL VIVEK; he decides whether the preview may read live data from vivek-pc's stack. Do NOT start the vivek-pc read-only fallback without his OK. Live data is top priority; nothing is "done" until seen working with real data.
2. Shipped UI must never silently fall back to mock data (audited 02:29: none in web/src/glorious; classic MockBadge only with VITE_USE_MOCK=1; check:no-mock on dist). Labelled non-data fallbacks: AI saved example, editor static field list.
3. Dead hold 3 s is display only; all times shown in log/history are real event times (audited: entry.at / completedAt shown; observedAt only for hold).
4. Breakdown is real (Simulation.java:120-139), one-way action via POST /api/simulator/scenario, auto-resumes; no toggle-off exists.
5. Rerun clean-checkout check after map+recovery merges (queued t261120-0229-b8ea).
6. When stack is up: FIRST real kill test (worker kill 2 -> dead -> recovering -> rejoined, screenshots), THEN full demo-path pass.

## Vivek 03:30 (binding)
- Make technical decisions yourself and TELL him. Only ASK about anything touching main (except swarm-tool changes) or the live demo.
- ui-glorious -> main: NOT tonight. Bring it with the 08:30 final report.
- Timeline: 06:00 Pipelines checkpoint, 07:00 freeze + full tests, 08:30 final report, 09:00 stop.
- Grok watermark: crop it (b).
- CORRECTION 03:40: vivek-pc workers stay ENABLED (disable reverted: main 1574ad8). Instead vivek-pc has "max_busy": 1 (main 1111cd1, listener caps concurrent tasks per machine). Boss session + live demo must never be starved; do not queue heavy work (builds/stacks) to vivek-pc yourself.

## Vivek 04:33 (binding, replaces earlier vivek-pc limits)
- vivek-pc Codex workers are FULL workers: max_busy and no_auto_pick removed (main bf3f361); "light only / no builds" rule removed. Still: do not start a second Docker stack on vivek-pc (live demo ports) and never touch the live demo.
- ONLY thing to protect: the boss session's tokens (ran out briefly tonight). Keep context lean, delegate, warn Vivek early if low.
- GOTCHA: pushing swarm .py code while long tasks run stalls dispatch on that PC until they finish (_first_runnable waits for restart). Config JSON pushes are safe.

## 05:13
- Merged 04:56-05:12: visual polish 933d, demo doc r2 28b4, preflight a41a (+ boss PS5.1 fix 770024d; live run: workers PASS, new endpoints FAIL as expected on main), demo-path spec 71e8, gap10+tests 25bb (boss kept 39d4 recovery fix; tsc OK, glorious vitest 92/92).
- Verified: full web suite 256/256 twice on 770024d (55b7, closed); Java 786 pass 0 fail on JDK 21 (3a95, closed). Container ITs (EngineMatchesReferenceIT, WorkerCrashRecoveryIT, CoordinatorRestartIT, IncompleteCheckpointIT) NOT run yet -> need Docker (utkarsh) at 07:00.
- HELD (merge only if demo path green, before 07:00): mobile 5796 (vimlesh shell), 2db4 (upendra map+pipelines, DONE), aed1 (vivek analytics+recovery).
- Running: utkarsh stack 2aa3 (~35 min; slow listener loops under build load, heartbeat up to ~3.7 min), pipelines-live 0830; then kill test 1e14. Others: analytics polish 6443 (vimlesh claude), check-all e348 (vimlesh luna), AI robustness e92b + pipelines polish eb78 (upendra), final docs 65c1 (vivek luna).
- Watcher: DOWN must hold 90 s before alerting (utkarsh slow loops caused false DOWNs 05:09).
- 06:00 PIPELINES CHECKPOINT evidence so far: AI generation real + valid (3/3 on vivek-pc bridge, 5-6 s); backend executor unit-tested; RUNNABLE on a live stack still unverified (pipelines-live 0830 pending). Switch: web/src/glorious/pipelines/mode.ts.

## 04:56
- Merged since 04:45: code-split (a603, --base!) + boss lazy classic (main chunk 1.7 MB -> 311 kB), guarded recovery spec (c064), pipelines mode switch (28c9: web/src/glorious/pipelines/mode.ts full|editor|hidden), deploy parity (c972: k8s SIM_INITIAL_TRUCKS 6), recovery test fix (39d4: real race; full web suite 256 pass).
- ANDROID STRETCH queued but HOLD MERGE until demo path is green (kill test + pipelines live pass) and before 07:00: t261120-0455-5796 vimlesh/sol shell mobile; t261120-0455-2db4 upendra/sol map+pipelines mobile. Desktop must be pixel-identical (proof/mobile/).
- Running: utkarsh stack 2aa3 + pipelines-live 0830 (waits for stack); kill test 1e14 queued after stack; vimlesh claude visual polish 933d; vimlesh luna preflight a41a; upendra luna demo doc round 2 28b4; vivek luna gap10 25bb; vivek sol full test rehearsal 3a95.

## 04:45
- DOCKER UP on utkarsh-pc (t261120-0408-c1a7 merged; ~21 min, wsl --shutdown + restart, no human click). C: only 4.63 GB free after prune -> stack build may hit disk; stack task t261120-0409-2aa3 reports.
- Preview :8089 now serves build #3 (ui-glorious 8679754: map polish + watermark). VERIFIED LIVE: Live World with 6 real trucks moving on polished city map, Running ~40 ev/s (screenshot sent to Vivek).
- Merged: a11y pass fca7 (conflict in AnalyticsPanel resolved by boss 664f9c1: <ul> + currentTrucks); gaps 8/9 partial dd12 (no tests) -> t261120-0440-25bb vivek-pc/luna does #10 + tests; DONE-CHECKLIST.md.
- vimlesh-pc/luna t261120-0441-c064 guarded Playwright recovery spec (ALLOW_KILL=1, refuses :8088).

## DUPLICATE code-split (04:38)
- t261120-0355-a603 is RUNNING on upendra-pc/codex-sol (started ~04:32, just before my reject landed) AND its requeue t261120-0432-ec77 runs on vimlesh-pc/codex-sol. The swarm cannot kill a running agent remotely. Merge whichever finishes first, discard the other.
- DANGER: a603 has no queue file any more -> `swarm merge t261120-0355-a603` would default to MAIN. Always use `swarm merge t261120-0355-a603 --base ui-glorious` (or `swarm reject`).
- utkarsh-pc/codex-sol idle with 2 queued: its listener holds dispatch until codex-luna's Docker task ends (new .py code pending since my 04:22 push).

## 04:37 queue
- utkarsh-pc: luna t261120-0408-c1a7 Docker (running) -> t261120-0411-0830 pipelines live; sol (held until luna restarts) t261120-0409-2aa3 stack -> t261120-0410-1e14 kill test
- vimlesh-pc (back since ~04:27): sol t261120-0432-ec77 code-split; luna t261120-0432-dd12 gaps 8-10; claude t261120-0432-933d visual polish
- upendra-pc: luna t261120-0355-fca7 a11y (auto-moved from vimlesh); sol idle -> feed
- vivek-pc: luna t261120-0433-a18d DoD checklist; sol t261120-0435-fd0d dist #3 -> t261120-0436-3a95 full test rehearsal
- Merged 04:24-04:36: gap review GAPS.md, frontend review fix (6c6f), gap6 analytics->map (ab7f), OFFLINE-CHECK.md (no external fetches), GAP7.md (1-min label honest).

## 04:22 status
- vimlesh-pc DOWN since ~04:15 (heartbeat >5 min; Vivek push-notified 04:20 to call Vimlesh). Its running tasks t261120-0355-fca7 (a11y, claude) and t261120-0355-a603 (code-split, luna) are stranded; the swarm auto-moves after 10 min. NEVER let a moved build task run on vivek-pc: if one lands there, reject + requeue elsewhere at once (vivek-pc max_busy=1 delays its start).
- main dd0f8ee: routing "no_auto_pick_machines": ["vivek-pc"] (pick_worker skips it for kind-queues and moves; explicit targets still work). Listeners use it after idle restart.
- Fed: upendra-pc/codex-sol t261120-0418-25cb gap review -> design/ui-glorious/GAPS.md; upendra-pc/codex-luna t261120-0422-6c6f frontend bug review (moved from vimlesh 4443); vivek-pc/codex-sol t261120-0416-fa24 PS 5.1 script review (light).
- Merged 04:13-04:21: presenter script docs/expo/DEMO-NEW-UI.md (3041), merge guide docs/expo/MERGE-UI-GLORIOUS.md (7c48), docs accuracy fixes (d1a2), DEMO/README/CHANGELOG pointers (6662). Closed e100 (build #2 artifact; preview html already extracted).
- Watcher (Vivek 04:15 rules): queue empty = IDLE alert (must feed); DOWN needs 2 checks (~30 s).

## Docker + live tests chain on utkarsh-pc (queued 04:08-04:11, Vivek: TOP PRIORITY)
- utkarsh-pc/codex-luna: t261120-0408-c1a7 Docker up (start DD, fix safely, prune; report exact click if human needed) -> then t261120-0411-0830 pipelines live test (3 examples via API, Ask AI->Run, editor Validate/Run, screenshots in design/ui-glorious/proof/pipelines/)
- utkarsh-pc/codex-sol: t261120-0409-2aa3 full stack (preview-up.ps1, :8089 + tunnel, glorious + classic Playwright) -> then t261120-0410-1e14 REAL kill test (worker kill 2 + 1, timed screenshots, recovery history JSON, events late) -> proof in design/ui-glorious/proof/kill/
- If any reports a needed human click: tell Vivek exactly (he calls Utkarsh). Never test against vivek-pc live demo.
- Merged 03:56-04:00: map polish (t261120-0323-e480), contract fix (t261120-0355-12e2).

## Watcher + tasks 04:00
- Idle-worker watcher (Vivek's rules): scratchpad\watcher\watcher.py, READ-ONLY, reads only ~/.swarm/state/view.json, every 15 s, lock port 47991, log capped 200 KB, alerts -> scratchpad\watcherlerts.txt (DOWN/DEAD/IDLE-no-reason). Boss reads ONLY alerts.txt (Monitor tail) and clears it after acting. Restart if dead: `python -I watcher.py` in that folder (single instance). `--once` prints a classification.
- AVOID list in watcher: vivek-pc/grok (12%), vivek-pc/vachana-claude (reserved for Ask AI, no kinds). vivek-pc max_busy=1.
- Queue-empty counts as an OK idle reason -> boss must keep the backlog filled.
- Queued 03:55 (all --base ui-glorious): t261120-0354-3041 utkarsh/luna presenter script docs/expo/DEMO-NEW-UI.md; t261120-0355-e8e2 utkarsh/sol backend correctness review (executor + recovery); t261120-0355-fca7 vimlesh/claude a11y pass; t261120-0355-a603 vimlesh/luna code-split (lazy screens); t261120-0355-12e2 upendra/luna pipelines contract check (upendra Codex ~12%).
- Running since 03:23: t261120-0323-e480 vimlesh/sol map polish.

## Progress 03:50
- Preview :8089 now serves build #2 (task/t261120-0323-e100, from ui-glorious 8e84499): basemap renders with live trucks (map fix verified with live data). Old html kept in scratchpad\preview\html.old. Rejected 9f5b (build #1) and 4b72 (failed docker diag).
- Watermark cropped + merged (t261120-0332-b2f9; verified corner clean). utkarsh disk task merged (C: 6.0 GB free; report design/ui-glorious/disk-utkarsh.md).
- PIPELINES CHECKPOINT evidence (generation half): bridge on vivek-pc, vachana config, claude-haiku-5-5, 3 rehearsal prompts -> all source=ai, valid (local validator; live API lacks D1), 4.8-6.1 s. Runnable half still needs a full stack (D1 executor + Kafka).

## Running / queued — 03:33
- t261120-0323-e100 vimlesh-pc/codex-luna  web build #2 artifact (after map fix) -> load into :8089 preview, re-check map with live data, then reject 9f5b + e100 (artifacts never merged)
- t261120-0323-e480 vimlesh-pc/codex-sol   map polish (fit city, icons, overlap)
- t261120-0332-b2f9 vimlesh-pc/claude      crop Grok watermark from home-loop.mp4 + poster
- t261120-0332-4990 utkarsh-pc/codex-luna  disk report + clear regenerable caches (C: 3.96 GB free after Utkarsh's 03:15 cleanup; Docker still not running, Vivek asking Utkarsh to start it)
- upendra-pc: back online ~03:05; Codex 18% left (resets 05:50). Upendra ran a recovery with his own agent (details unsure).
- t261120-0224-4b72 (utkarsh docker diag) FAILED on full disk: close with reject when convenient.
- Preview A on vivek-pc: container glorious-preview, 127.0.0.1:8089, GET-only to live API (see 03:00 notes above in history / scratchpad\preview).
- Still unverified with live data: real kill test (needs separate stack), Ask AI generate->run, editor run, recovery history endpoint, map tiles+live trucks (build #2).

## Key recon decisions
- Worker-card bug: killed worker restarts + re-registers before 4 s timeout, never DEAD server-side; old UI used client overlay only on its own button -> terminal kill invisible. C1 adds server-side recovery history (/api/recovery/history + WS).
- Engine CAN run DSL pipelines but replaces the demo execution -> Track D runs its own executor in control-api (own Kafka consumer group).
- control-api is in Docker -> cannot use host claude login -> D3 host bridge on :18090, nginx proxies /api/glorious/ai/.
- Breakdown exists (/api/simulator/scenario breakdown, auto-resumes, no restore). Pause/resume/speed only on simulator directly (no API proxy) -> not in scope unless time.
- SIM_INITIAL_TRUCKS: "6" in docker-compose (Track A). Track D contract: scratchpad\tasks\CONTRACT-D.md (copied into D task bodies).

## Open problems
- none

## Next
1. Review/merge recon, shell, preview. Read RECON.md (summarise to Vivek in ~10 lines per item).
2. If the swarm --base ever breaks: `git revert d6fef4e` on main, push, tell Vivek.
3. Write track tasks referencing RECON.md + web/src/glorious/README.md (shell API).
4. Tracks A (vimlesh), B (utkarsh #2), C (utkarsh), D (upendra), all --base ui-glorious.
5. 03:30 update to Vivek: one screenshot per track + preview link.
