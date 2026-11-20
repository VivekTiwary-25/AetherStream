# BOSS_STATE — ui-glorious mission (20 Nov 2026)

Fresh boss session: read swarm/BOSS.md, then design/ui-glorious/BOSS_PROMPT.md (the mission), then this file. Continue from "Next".

## 12:47 FINAL: demo frozen, swarm stopping (Vivek's order)
- DEMO READY since 12:33 on vimlesh-pc at ui-glorious fb333a3 (public link on the demo-link branch, never in public files). Kill + recovery verified live: checkpoint #37, 1803 ms. Nothing may be merged/refreshed onto vimlesh-pc without Vivek's OK.
- ui-glorious head after the freeze: 25b20f9 (+ this note). Merged after fb333a3, NOT on the demo host: fleet-poll timeout + listener-leak fix, PipelineEvaluator stall fix (control-api), AI 429 busy message (+ test fix), a11y tests + build fix, recovery/demo-path Playwright specs, CHANGELOG/docs (2-min demo, VISITOR-QA, STANDBY, troubleshooting table, README of scripts), check-all JDK21 finder. `npm run build` passes at 25b20f9.
- Rejected at the freeze (not merged; redo later if wanted): fae5 survivor "Rejoined"/stale Dead/endless rebalance banner (the only refresh-worthy fix), 34d8 README rewrite (had auto-failed-over to vimlesh-pc), 113f prefetch review + web gates, 5e82 web gate, 5d26 docs link check, 915d console-error hunt. Reject does NOT stop a running agent; `swarm down` on each PC does.
- Still held unmerged on purpose: T181, T185-T188, Android tasks incl. 0623-767d.
- Watcher and boss monitors stopped. utkarsh-pc: no Docker work (disk); its standby stack must be stopped by hand (compose down without -v, builder prune).
- Lessons: run `npm run build` after web merges (vitest does not type-check); the swarm auto-fails-over queued tasks from DOWN PCs to any online PC, including the demo host.

## 12:40 DEMO READY (since 12:33)
- Host vimlesh-pc at ui-glorious commit fb333a3; public link lives on the demo-link branch (never write it into public files). Kill + recovery verified live there: checkpoint #37, 1803 ms.
- RULE (Vivek): nothing is refreshed onto vimlesh-pc without Vivek's OK. Keep merging fixes into ui-glorious; tell Vivek only about fixes worth a refresh. Only current candidate: survivor "Rejoined" / endless rebalance banner fix (t261120-1122-fae5, utkarsh sol).
- Merged after fb333a3 (NOT on demo host): fleet poll timeout + abort-listener leak fix, evaluator stall fix (control-api), AI 429 busy message, build fix (a11y fixture broke tsc -b), recovery spec, docs. Always run `npm run build` after web merges.
- README rewrite t261120-1231-34d8 was auto-failed-over to vimlesh-pc/codex-luna (docs only); asked Vivek whether to move it.

## 12:07 boss state
- vimlesh-pc = DEMO HOST (works; tunnel blocked by college net -> localhost:8089 / LAN, see docs/expo/STANDBY.md). Its swarm is down; queue NOTHING there; watch for auto-failover moving tasks there and ask Vivek before moving (Vivek: "don't reroute without my permission").
- utkarsh-pc: NO Docker work any more (disk filled 24->7 GB). Standby stack to be stopped BY HAND by Utkarsh (compose down, keep volumes; builder prune). Never queue destructive/docker tasks that could fail over to vimlesh-pc.
- 11:44 sent Vivek "REFRESH NOW" for vimlesh at 110f770 (pipelines 45 km/h + event-time windows, editor Run, AI fallback, Dead hold, history by time, MSYS, preview PS5.1 ExitCode). Waiting for Vimlesh re-test results (kill + pipelines + expo-proofs offsets).
- Ask AI demo sentence: "Alert me when any truck goes over 45 km/h" (sim max 50). Presenter P1 line in DEMO-NEW-UI.md. Vivek decided: NO restart delay; demo honest kill (worker keeps its partitions).
- Running: utkarsh sol fae5 (survivor Rejoined / stale Dead / rebalance banner); vachana 9fc7 (vitest flakes); vivek sol ffd4 (a11y tests); upendra sol 8a6a (evaluator edge tests) -> 11e8 (stall drops backlog, review finding 3); upendra luna 2a4a (fleet poll timeout, finding 4).
- Merged since 11:44: AI bridge 429 drain fix, Failed Records no 30-row cap, demo-path spec 45 km/h, FINAL_REPORT, docs (2-min demo, VISITOR-QA, consistency), check-all JDK21 finder, review label-centre fix. Review: design/ui-glorious/proof/review/REVIEW-1145.md (finding 5: AI timeout retry may hit busy - minor).
- Workers drop report files (old prompt wording); new wording allows own report file. reject deletes the task branch. Hand-merge conflicts in the worktree (CHANGELOG mostly), then reject the task with "merged by hand".
- grok: 0% until 23:38. Watcher: scratchpad/watcher (this session), AVOID = vimlesh-pc.

## 10:33 new boss (DEFCON 1: deployed demo ASAP)
- Demo host: vimlesh-pc (Vimlesh's own Claude builds the stack; his swarm DOWN on purpose; queue NOTHING there). Vivek pastes its results; boss turns failures into fix tasks, merges, tells Vivek to run C:ether-preview\.preview\preview-refresh.ps1 there.
- HOT STANDBY utkarsh-pc: sol 1028-10bf stack (preview-up) -> 1028-3987 kill test; luna 1028-1ee9 pipelines live -> 1032-3483 e2e rerun. Proof in design/ui-glorious/proof/standby-utkarsh/. First ready host = demo.
- vivek-pc: luna 1030-7f9c check-all #4 -> db27 Home video test; sol bb53 Playwright locator fixes -> 91e6 fake-audit A (map/analytics/shell); vachana 4984 fake-audit B (recovery) -> efb6 kill-readiness review; grok 919a CHEATSHEET check -> a9bc glorious README check.
- upendra-pc: luna 1031-b527 DEMO-NEW-UI check -> d326 MORNING UI check; sol 1032-7f36 pipelines honest-failure tests -> 810f classic check.
- Merged 0647-2758 (MORNING fact-check), 0648-f804 (MERGE doc fact-check). Rejected failed 0645-e502, 0632-6c90 (replaced).
- Still held unmerged: T181, T185-T188, Android tasks incl. 0623-767d.
- Watcher restarted in this boss's scratchpad\watcher (AVOID now = vimlesh-pc only; vachana + grok are fed).

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

## 06:41
- STRETCH (Android) DECISION: NOT merged. Rule = only if demo path green before 07:00; live kill + live pipeline run still blocked. Merge-ready branch kept: task/t261120-0623-767d (all 3 parts + 8 design fixes; desktop byte-identical on 9 views; 289 tests). Do NOT reject it; Vivek may merge later with `swarm merge t261120-0623-767d --base ui-glorious` (queue file still on main).
- upendra-pc DEAD (Codex 3%, out of tokens). Its queued link check t261120-0639-ceee auto-moves.
- Preview :8089 = build #6 (c56c530, all fixes). e2e re-run t261120-0632-6c90 (vivek sol) pending. check-all #2 PASS. Morning runbook t261120-0638-27c1 (vivek luna).

## 06:22
- AI eval (boss-run): 14/15 valid real AI pipelines, median 5.8 s (proof/ai-eval). Worker eval cbd6 timed out (closed).
- Preview e2e on vivek-pc :8089 (build #4): smoke1 (6 markers) PASS, smoke2 (Truck Speeds drilldown) PASS LIVE; demo-path + logs smoke failed on test issues (moving-marker hover, missing partition-chip testid) -> fix t261120-0620-f32b (vivek sol). Generate showed 'Request failed (403)' (preview blocks POST) -> map to 'AI service unavailable'. Committed RESULTS.md + pngs only (51fe00c); 60 MB traces NOT committed.
- Merged: API-down backoff ee08 (5->10->15 s, recover <=15 s), microcopy cb85, docs label sync 62a3, check-all fix 4dda.
- vimlesh-pc DOWN again since ~06:07 (Vivek told 06:13 to call Vimlesh). Its tasks auto-moved: d18c recovery polish + 844a dist #5 -> upendra luna; 577f mobile bundle -> requeued t261120-0621-ffc7 vivek sol (HELD).
- Closed failed utkarsh tasks 2aa3 (stack) + 4f1f (pipelines live): requeue from design/ui-glorious/tasks/t-stack-up.md, t-killtest.md, t-pipelines-live.md when utkarsh has >=15 GB.
- Running: 22d2 spec copy (upendra sol), 133f check-all #2 (vivek luna), d18c + 844a (upendra luna).

## 06:08
- FULL CHECK PASS on vivek-pc (check-all.ps1, no Docker): web lint/test/build/no-mock, java-test (JDK21), ai-bridge. Docker ITs + Playwright suites not run (need a stack).
- Merged 05:56-06:08: build #4 on preview (Failed Records polished, live), microcopy cb85, report refresh d48d, merge-safety 4532 (+ boss 71ca527: classic local default demo.spec only, CI CLASSIC_ALL_SPECS=1), check-all PS5.1 fix 4dda, recovery fix 41cf, offline-expo 0360.
- Running: AI eval cbd6 (vivek luna, ~45 min, may time out), mobile final bundle 577f (vimlesh sol, HELD), API-down backoff ee08 (upendra sol), docs label sync 62a3 (upendra luna).
- Still BLOCKED: utkarsh disk full -> live kill test + live pipeline run. Waiting for Vivek option 1/2.

## 06:00 PIPELINES CHECKPOINT: DECISION = KEEP 'full' (Ask AI + editor), PIPELINES_MODE unchanged
- Valid: real Ask AI generation via vivek-pc bridge (vachana, haiku): 4/4 valid real pipelines (3 rehearsal + 'stuck/stopped' visitor prompt), 5-10 s; 20-prompt eval t261120-0526-cbd6 still running.
- Runnable: executor proven against a REAL in-JVM Kafka broker (PipelineRunsKafkaIntegrationTest, 3/3 example pipelines exact). NOT yet run on a live stack (utkarsh disk full).
- Safety net: honest saved-example fallback (also offline), manual editor, one-line switch web/src/glorious/pipelines/mode.ts -> 'editor' or 'hidden' if the live run fails when a stack exists.
- Recovery history fixed + proven vs real Coordinator (41cf merged): worker-2 attribution + restore checkpoint.

## 05:25 BLOCKER
- utkarsh-pc DISK FULL again (OSError 28): Docker image build consumed the ~4.6 GB. Stack t261120-0409-2aa3 FAILED, pipelines-live t261120-0514-4f1f FAILED (worktree add), kill test 1e14 running but will fail (no stack). Asked Vivek 05:25: (1) Utkarsh frees >=15 GB (Downloads 6.6 GB, Docker Desktop Troubleshoot > Clean/Purge data, old images) then `swarm fix`; or (2) Vivek OKs a ui-glorious stack on vivek-pc next to the live demo (compose -p aether-preview, NO host ports except web 8089, memory risk) -> needs his explicit OK (touches live-demo machine).
- Merged 05:15-05:23: AI robustness e92b (re-verified real generation 10 s), final docs 65c1, pipelines polish eb78, glorious README 9581, FINAL_REPORT.md skeleton 2690.
- HELD mobile: 5796, 2db4 (combine task 0d13 running), aed1 running.

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
