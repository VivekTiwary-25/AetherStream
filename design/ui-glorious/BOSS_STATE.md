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
- t261120-0224-4b72  utkarsh-pc/codex-sol   Docker Desktop diagnose + safe fix (wsl --shutdown, restart DD)
- t261120-0217-4cd6  utkarsh-pc/codex-luna  Home video seamless crossfade loop + web encode
- t261120-0217-0993  vimlesh-pc/claude      Integration check (clean checkout lint/test/build) — started before map + recovery merged; rerun later
- t261120-0218-1e31  vimlesh-pc/codex-sol   New-UI Playwright smoke tests (tests/ui/glorious.spec.ts, GLORIOUS_URL default :8089)
- t261120-0125-ed02  upendra-pc/codex-luna  D3 AI bridge
- Merged (all tracks' main code IN): shell, A0 sprite, A map (t261120-0130-3a5f), B analytics (verified B2), C2 terminal, C1 backend + C1-UI (recovery/), D1 backend, D2 frontend.
- DOCKER: no worker PC has working Docker (utkarsh stuck, upendra none, vimlesh engine down + 3 GB free RAM). Vivek pinged 01:28 (no reply yet).
- PLAN for 03:30 screenshots if Docker still down: worker builds web dist into a task branch (never merged); boss serves it on vivek-pc via throwaway nginx container on :8089 proxying /api,/ws to live API localhost:18080, READ-ONLY (no kill/breakdown/tunnel). New endpoints (recovery history, pipelines) will show 'unavailable' there because live API is main.
- GOTCHA: root .gitignore `logs/` ignores any folder named logs. Never name a folder logs.
- Known: vitest needs --maxWorkers=2 on loaded PCs. vimlesh-pc now has portable Node at %USERPROFILE%\tools\node (not on listener PATH). upendra-pc has Node 26 (needs NODE_OPTIONS=--no-experimental-webstorage for vitest).

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
