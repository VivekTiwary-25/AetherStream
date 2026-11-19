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

## Running / queued — 03:00
- Vivek 02:52: approved + pushed swarm upkeep change (main 32d75a0); chose Docker fallback A.
- PREVIEW A RUNNING on vivek-pc: docker container `glorious-preview` (nginx:1.29-alpine, 127.0.0.1:8089 only, 64 MB, GET-only proxy to live API host.docker.internal:18080, POST -> 403). Files: scratchpad\preview\{html,nginx.conf} (html = dist from task/t261120-0228-9f5b built at ui-glorious 1c90f92). Rebuild: new dist task -> git archive FETCH_HEAD preview-dist | tar -x -C html --strip-components=1. Stop: docker rm -f glorious-preview. NEVER kill/breakdown against it.
- Walkthrough with LIVE data 02:50-02:58 (screenshots in C:\Users\Arena\AppData\Local\Temp\claude-chrome-screenshots-hgitD0\): Home OK (video, rail, pill Running ~40 ev/s); Live World: 6 markers + hover card real (truck-001 DRIVING 37.9 km/h, P5 W3 correct) BUT basemap blank: MapLibre "Worker failed to load" in prod build (fix queued t261120-0253-e411 vimlesh-pc/codex-sol: nginx .mjs MIME + maplibre classic-vs-module worker); Analytics Truck Speeds + row->chart same window OK; Failed Records OK live; Settings sub-menu OK; Logs & Recovery OK (3 workers, P0-P5, checkpoints; history 'endpoint not found' expected on live main); Pipelines panel renders (no backend on live).
- Fixed by boss: 20b2f8c strong glass 0.72 -> 0.9 (text over content was unreadable).
- Video has a 'Grok' watermark bottom-right (Grok Imagine). Ask Vivek; do NOT remove without his OK.
- t261120-0125-ed02 D3 AI bridge: upendra-pc down 22 min, swarm moved task to vivek-pc/codex-luna (running; light python). After it: consider disabling vivek-pc workers (ask Vivek).
- utkarsh-pc: disk 1.6 GB free after sweep (only 0.3 GB was worktrees) -> Utkarsh must run manual cleanup (commands sent 02:50) / Docker disk. Do not queue there.
- Integration rerun t261120-0229-b8ea PASSED (214 tests, build OK, map+recovery tracked) -> closed.

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
