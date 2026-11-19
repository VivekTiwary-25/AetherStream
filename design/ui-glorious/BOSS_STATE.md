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

## Running / queued (all --base ui-glorious). Task specs: design/ui-glorious/tasks/*.md
- t261120-0205-2440  utkarsh-pc/codex-sol   C1-UI Logs & Recovery window + Truck breakdown in web/src/glorious/recovery/ (backend merged)
- t261120-0139-e72a  vimlesh-pc/claude      D2 pipelines frontend
- t261120-0139-4c1c  vimlesh-pc/codex-luna  B2: install portable Node + bring in Track B from task/t261120-0130-5be9 + verify/fix -> then t261120-0153-90ea preview on vimlesh (Docker probe)
- t261120-0130-3a5f  vimlesh-pc/codex-sol   A map (started without node -> needs verify task afterwards)
- t261120-0125-ed02  upendra-pc/codex-luna  D3 AI bridge
- UNMERGED, keep until B2 merged then `swarm reject t261120-0130-5be9`: original Track B branch.
- Merged: audio, recon, preview scripts, SHELL, classic Playwright fix (5cf9f62), C2 terminal cmds (+7633d6a ASCII), A0 sprite, D1 backend (+ce1982e nginx per-request resolve), C1 backend (recovery history /api/recovery/history + WS, coordinator audit-only additions), a9df0fa logs/->recovery/ rename.
- DOCKER: unavailable on utkarsh-pc (Desktop stuck, Vivek pinged 01:28, two 20-min waits failed) and upendra-pc. Preview + live kill proof + live pipeline run blocked. Probing vimlesh next.
- GOTCHA: root .gitignore `logs/` ignores any folder named logs -> C1's first UI was lost. Never name a folder logs.
- Known: vitest needs --maxWorkers=2 on loaded PCs. vimlesh-pc had NO node/npm in agent PATH. upendra-pc has Node 26 (needs NODE_OPTIONS=--no-experimental-webstorage for vitest).

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
