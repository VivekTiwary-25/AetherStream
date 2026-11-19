# Expo rehearsal 2: are the rehearsal-1 fixes live? (19 Nov 2026)

- **Target:** the live app through the public link in `boss/STATE.md` (`https://trance-cover-acrobat-submissions.trycloudflare.com`). It runs on the demo laptop. The run itself was driven from vimlesh-pc. No containers were touched, and the deploy freeze was not toggled.
- **Browser:** Playwright 1.65.2 (Python), headed Chromium, 1440x900. Headless Chromium has no GPU and shows **Live city paused: no GPU acceleration detected**, so headed mode was used. Screenshots and raw step logs are in [`rehearsal-2/`](rehearsal-2/) ([run-log.txt](rehearsal-2/run-log.txt), [short-log.txt](rehearsal-2/short-log.txt)).
- **Deployed commit:**
  - At 05:13 UTC, `/api/version` returned `737d1dca2127b8c633d055374e7f41415e935b1b`.
  - At 05:21 UTC, CD run [44463774331](https://github.com/VivekTiwary-25/AetherStream/actions/runs/44463774331) deployed `15ed205a77b59aedfda46990e271e86625ab5273` underneath me (see P1 #1).
  - **The run that counts is on `15ed205`.** The footer showed **Deployed version: 15ed205**, which matches.
  - This build contains T158, T173, T174, T175, T177 and T178. All six are ancestors of `737d1dc`, which is an ancestor of `15ed205`.
- **Runs:**
  - Full 12-minute script: 05:22:02–05:25:57 UTC.
  - DEMO.md **3-minute short version:** 05:27:32–05:28:23 UTC, used to capture the kill banner on screen.
  - DEMO actions done (and nothing else): Breakdown (truck-007), late events, slow output 200 ms → Off, Kill ×2 (one per script), Compare ×2, Take savepoint, Restore, Rescale, Rush hour, Reset, Tour.

## Rehearsal-1 items: verdicts

"Before" links point to rehearsal 1. "After" links point to this run.

### P1

| # | Item | Verdict | Before | After | Evidence |
|---|---|---|---|---|---|
| 1 | CD redeploys the live demo during the slot | **PARTLY** | (attempt 1, no shot) | [01](rehearsal-2/01-cd-deploy-502.png) | The freeze mechanism exists (T173): the CD `deploy` job now has a **Check demo freeze** step. **But the freeze is OFF.** CD deployed `737d1dc` at 05:11 and `15ed205` at 05:20. During the 05:21 restart, the public link returned **Cloudflare 502 Bad gateway** and `/api/ready` timed out (20 s) once just after. The same thing would happen during the slot. The swarm still merges to `main` every few minutes. |
| 2 | Stuck-truck alert never appears | **FIXED** | [03](rehearsal-1/03-dashboard.png) | [05](rehearsal-2/05-breakdown-truck-007.png), [05b](rehearsal-2/05b-alert-truck-007.png) | With **Breakdown truck** = `truck-007` and **Breakdown** clicked, `/api/simulator/state` showed truck-007 `STOPPED`. **Alerts** showed `truck-007 has not moved for 10 min` **66 s** later, also in `/api/results/alerts`. See new item N2 for the DEMO click order. |
| 3 | No **Recovered** banner after Kill | **FIXED** | [21](rehearsal-1/21-kill-state.png), [22](rehearsal-1/22-timeline-after-kill.png) | [S1](rehearsal-2/S1-workers-killed-restarting.png), [16](rehearsal-2/16-timeline-after-kill.png), [run-log](rehearsal-2/run-log.txt) | Run 1, after **Confirm kill**:<br>• +0.5 s: worker card `Killed · restarting`, `2 alive`.<br>• +2.8 s: banner `Recovering from checkpoint #381`.<br>• +6.1 s: banner **`Running / Recovered in 2.0 s`** (green). It held for about 10 s, through the next checkpoint, then went back to `Checkpoint #382 saved`.<br>Short run: Recovering at +4.2 s, **Recovered in 2.0 s** at +7.3 s. T178 (server-side recovery events) works. The banner is above the fold while the presenter is on Chaos controls, so see N3. |
| 4 | Queue capacity 32 vs 1000 | **PARTLY** | [16](rehearsal-1/16-workers-while-slowed.png) | [07](rehearsal-2/07-workers-panel.png), [13](rehearsal-2/13-slow-output-backpressure.png) | The screen is now consistent. Workers show `/32`, and the hint reads **Output slowed: queues fill up (bounded at 32) and the engine pushes back.** Backpressured appeared after 3.1 s. Compose is still 32 and contract 03 §1 still says 1000. DEMO.md still says the hint reads "bounded at 1,000" (N4). |
| 5 | Grafana and Prometheus not running | **NOT VERIFIED (likely fixed)** | [48](rehearsal-1/48-grafana.png) | [06](rehearsal-2/06-grafana-via-tunnel.png) | `infra/deploy/deploy.sh:12` now starts the stack with `--profile monitoring`, and the 15ed205 `deploy` job and **Verify live demo** step are green. However, Grafana (`localhost:13000`) is only reachable on the laptop. The tunnel serves the web app for `/grafana/`. Someone on vivek-pc must run `curl -fsS localhost:13000/api/health` before the slot. |

### P2

| # | Item | Verdict | Before | After | Evidence |
|---|---|---|---|---|---|
| 6 | GitHub logs need a login | **NOT FIXED** | [43](rehearsal-1/43-gh-cd-run-summary.png) | [36](rehearsal-2/36-gh-cd-run-15ed205.png) | The signed-out CD run page still says **Sign in to view logs** and has no step summary with the PASS line. DEMO.md now points at rehearsal screenshots instead (T175). The presenter must still sign in beforehand. |
| 7 | Kill confirm cut off | **FIXED** | [17](rehearsal-1/17-kill-confirm-dialog.png) | [14](rehearsal-2/14-kill-confirm.png) | **Confirm kill** box at y = 487–528 of 900, fully visible. Confirm text matches DEMO exactly. |
| 8 | Rescale result hidden | **FIXED** | [29](rehearsal-1/29-ops-rescale.png) | [23](rehearsal-2/23-ops-rescale.png) | Directly under the buttons: **Refused (409 CONFLICT): execution exec-25ea1ee8-… does not need a rebalance**. |
| 9 | Restore gives no feedback, native confirm | **PARTLY** | [27](rehearsal-1/27-ops-restore-clicked.png), [28](rehearsal-1/28-ops-restore-result.png) | [21](rehearsal-2/21-ops-restore-confirm.png), [22](rehearsal-2/22-ops-restore-result.png) | Confirm is now in-page (**Restart the pipeline from this savepoint?** / **Confirm restore** / **Cancel**). No native dialog fired. The list is capped at 3, newest first. **But after Confirm restore, no `Restored from checkpoint #N → new execution …` line is shown**: the panel returns to the plain list. The API confirms the restore: `exec-25ea1ee8…`, generation 1, `restoredFromCheckpoint: 386`. The banner read `Running / Running`. Root cause and fix: N1. |
| 10 | Show me zoom carries over | **FIXED** | [10](rehearsal-1/10-editor-good-validated.png) | [10](rehearsal-2/10-editor-show-me.png), [11](rehearsal-2/11-editor-good-validated.png) | After **Show me** → good example → Validate, all **11** nodes are rendered. `Valid - 11 operators, 6 partitions`. |
| 11 | Timeline after recovery | **PARTLY** | [22](rehearsal-1/22-timeline-after-kill.png) | [15-t20](rehearsal-2/15-kill-t20.png), [16](rehearsal-2/16-timeline-after-kill.png) | Fixed:<br>• Checkpoint labels no longer overlap.<br>• A recovery band, `✕ worker lost`, and a `✓ Recovered in 2.0 s` marker appear.<br>• The footer says `Restored from #381 · Recovered in 2.0 s`.<br>Still wrong (N5):<br>• The band title is clipped to a single **"R"** and runs into `worker lost`.<br>• Two markers (`✓ Recovered` and `✓ Recovered in 2.0 s`) sit side by side and overlap at the right edge (`Reco✓Recovered in 2.0 s`). |
| 12 | Worker card never reflects the kill | **FIXED** | [23](rehearsal-1/23-workers-after-kill.png) | [S1](rehearsal-2/S1-workers-killed-restarting.png) | Red **Killed · restarting** on worker-1, header **2 alive**, back to **Running / 3 alive** at about +5 s. |
| 13 | Only 2 of 3 validation errors visible | **FIXED** | [08](rehearsal-1/08-editor-broken-validated.png) | [09](rehearsal-2/09-editor-broken-validated.png) | All three rows and three **Show me** links are visible without scrolling. Order on screen: alertSink, del, speed1m, matching DEMO.md. Run was disabled before Validate and enabled after the good one. |

### P3 (not targeted by the fixes; re-checked)

| # | Item | Verdict | Evidence |
|---|---|---|---|
| 14 | DEMO.md out of date | **PARTLY** | T175 fixed the rehearsal-1 wording, but it was written before T158 went live, so several lines now describe the *old* behaviour. See N4. |
| 15 | Scenarios show **Active: Reset** | **NOT FIXED** | [24-reset](rehearsal-2/24-scenario-reset.png): `Active: Reset` while `/api/simulator/state` says `scenario: none`. DEMO already warns about it. |
| 16 | Intro **Skip** barely clickable | **NOT FIXED** | [02](rehearsal-2/02-page-load-intro.png): Skip is visible at +0.8 s, but Playwright could not click it in 30 s (never "stable"). A forced click also failed in the short run. DEMO already says not to rely on it. |
| 17 | Recovering pill purple | Unchanged on purpose | [S2](rehearsal-2/S2-kill-recovering-banner.png). |
| 18 | Tour card covers Speed windows | **NOT FIXED** | [26-step-2](rehearsal-2/26-tour-step-2.png). All 8 titles are present, in order, `STEP N OF 8`. |
| 19 | Footer under the legend bar | **NOT FIXED** | [22](rehearsal-2/22-ops-restore-result.png), bottom right: `Deployed version: 15ed205 · Expo build #0` sits under the legend. |

Other DEMO checks that passed:
- No **MOCK DATA** badge.
- `/api/ready` UP on all four checks (after the redeploy).
- **20 late events sent**, and **Late (20)** within 1.5 s ([12](rehearsal-2/12-dead-letters-late.png)).
- Compare: **626 / 626 results match**, "checked up to checkpoint #385 - replayed 371,511 events in 3,681 ms", in 4.5 s ([19](rehearsal-2/19-compare-result.png)). The short run gave 293 / 293.
- Savepoint line `Savepoint sp-exec-ad96…-386 at checkpoint #386` ([20](rehearsal-2/20-ops-savepoint.png)).
- `Active: Rush hour` ([24](rehearsal-2/24-scenario-rush-hour.png)).
- Tour steps 1–8 ([26-1](rehearsal-2/26-tour-step-1.png), [26-8](rehearsal-2/26-tour-step-8.png)).
- Footer = `/api/version` ([34](rehearsal-2/34-footer.png)).

## New problems

### P1

**N1. Restore success line is wiped at once, because the Operations panel remounts on every execution/state change.**
- **Location:** `web/src/app/shell/AppShell.tsx:88` renders `<OpsPanel key={`${execution?.id ?? 'none'}:${execution?.state ?? ''}`} />`.
- **What happens:** Restore creates a new execution id, so React throws away the OpsPanel that just set `Restored from checkpoint #N → new execution …` (`OpsPanel.tsx:78`). The key also includes `state`, so every RUNNING ↔ CHECKPOINTING flip (about every 10 s) remounts the panel. That can also wipe the savepoint/rescale line, or an open **Confirm restore** row, mid-sentence on stage.
- **Fix:** Drop `state` from the key and do not key on the execution id either. Pass `execution` as a prop or read it from the store, and have OpsPanel update its `executionId` in a `useEffect` without clearing `notice`.
- **Test:** In `web/src/app/__tests__`, check that the notice survives an execution-id change and a state change.

**N2. DEMO.md pre-show step 4 click order stops the wrong truck.**
- **Location:** DEMO.md "30 minutes before" step 4 says "click **Breakdown**, then set **Breakdown truck** to `truck-007`".
- **What happens:** The select defaults to `truck-005` (`web/src/app/scenarios/ScenarioPanel.tsx:11`) and is read only when **Breakdown** is clicked (line 35). Changing the select afterwards does nothing. Followed literally, the alert would name truck-005, or no truck at all if truck-005 was already unloading. Live, the first click applied the default truck. truck-007 was only `STOPPED` after a second **Breakdown** click ([04](rehearsal-2/04-breakdown-dom-order.png), [05](rehearsal-2/05-breakdown-truck-007.png)).
- **Fix:**
  - DEMO.md: "set **Breakdown truck** to `truck-007`, *then* click **Breakdown**".
  - Optional web fix: default `truckId` to `truck-007`.

**N3. During the kill, the banner the presenter is told to "watch" is off-screen.**
- **What happens:** At 1440x900, the execution banner (**Recovering… / Recovered in X s**) sits at the top of the page. Once **Chaos controls** is scrolled into view to click **Kill worker**, the banner is out of view: none of the run-1 kill screenshots ([15-t02](rehearsal-2/15-kill-t02.png), [15-t08](rehearsal-2/15-kill-t08.png)) show it. **Recovered in X s** only lasts about 10 s, so a presenter who scrolls up late misses it.
- **Fix:** One of:
  - Make the status bar sticky (`position: sticky; top: 0` on the `Execution status` region in the shell CSS).
  - Repeat the recovery line under the kill status in `ChaosPanel.tsx` (it already shows `Worker worker-1 killed.`).
- **Until then:** DEMO.md should say to point at the Workers card (**Killed · restarting**) and the timeline footer (**Recovered in X s**), which are on screen next to Chaos.

### P2

**N4. DEMO.md now contradicts the fixed UI.** T175 rewrote DEMO.md before T158 was live. Update these lines:
- **Kill step and 3-minute row 1:10:** "no **Recovered** banner appeared in rehearsal", "Do not expect a **Dead** row … it stayed **3 alive**/**Running**", "Do not wait for **Recovered** …". Now **Recovered in 2.0 s** appears about 6–7 s after Confirm kill. The card shows **Killed · restarting** and **2 alive**.
- **Hint quote:** "Output slowed: queues fill up (bounded at 1,000)…" in the kill step and Q4. The screen says **bounded at 32**.
- **Restore:** "presents the native confirmation… showed no new execution id". It is now an in-page **Confirm restore** / **Cancel** row. Feedback is still missing because of N1.
- **Rescale:** "the UI does not show HTTP 409 or CONFLICT … may be below the fold". It now shows **Refused (409 CONFLICT): …** under the buttons.
- **Editor:** "the editor remains zoomed … may show one large **Window** block" and "scroll this region to see all three". Both are fixed.
- **Pre-show step 3:** "The stream and engine are covered by the dashboard … Do not rely on collapsing panels". The city and engine are visible ([26-step-2](rehearsal-2/26-tour-step-2.png)).

**N5. Timeline recovery band is unreadable.**
- **Location:** `web/src/app/timeline/CheckpointTimeline.tsx`.
- **What happens:** The band is a few pixels wide, so its title ("Recovering from checkpoint #381 (generation 24 → 25)", present in the DOM) renders as **"R"** on top of `✕ worker lost`. The band's `✓ Recovered in 2.0 s` marker and the separate `✓ Recovered` marker under the previous checkpoint both render, and collide near the right edge ([15-t20](rehearsal-2/15-kill-t20.png)).
- **Fix:**
  - Give the band a minimum label width, or put the title above the track.
  - Render only one Recovered marker per recovery (prefer the one with a duration).

**N6. The freeze is not engaged, and the swarm keeps merging.**
- **What happens:** This is P1 #1 again. It is listed here as an action, not a code fix: during this rehearsal one deploy took the public link down (502) for about a minute.
- **Fix:** Before the slot, the boss runs `bash infra/scripts/demo-freeze.sh on` (DEMO pre-show step 1) and pauses swarm merges. I did not toggle it, per the task.

**N7. After Restore or Reset, the stuck-truck alert does not come back by itself.**
- **What happens:** The Breakdown hold is 15 sim-minutes from when it is applied. After **Restore** (new execution), `/api/results/alerts` was `[]`. The Alerts panel kept showing the *old* execution's `truck-007 … 16 min ago` until reload ([26-step-2](rehearsal-2/26-tour-step-2.png)). **Reset** ends the breakdown.
- **Fix:** DEMO.md: after Restore or Reset, click **Breakdown** (truck-007) again and allow about 70 s.
- **Optional:** The Alerts panel should clear when the execution id changes.

### P3

**N8. "Recovered in 2.0 s" was exactly 2.0 s in both kills,** while the wall clock from Confirm kill to the green banner was 6–7 s (Recovering → Recovered about 3.3 s). It reads as a fixed or heartbeat-bound value. Check how `durationMs` is measured in `control-api/.../ws/ExecutionEvents.java` (T178), or say "about 6 s from kill to recovered" on stage.

**N9. Stale reference-panel subtitle.** During the kill, **Reference comparison** still said "Latest completed checkpoint: #368" while the banner was at #381 ([15-t02](rehearsal-2/15-kill-t02.png)). It refreshes only on Compare. Either refresh it from the execution store or drop the line.

**N10. After a restore, the timeline footer mixes executions.** It showed `Generation 1 · Last checkpoint #0 · Restored from #386 · Recovered in 2.0 s` ([22](rehearsal-2/22-ops-restore-result.png)). The "Recovered in 2.0 s" belongs to the previous execution's kill. Clear `lastRecovery` when the execution id changes.

## Timing

| Run | Automated time | Notes |
|---|---|---|
| Full 12-minute script | **233.9 s** (3 min 54 s) | Includes the pre-show alert wait (66 s, normally done 30 minutes before), a 20 s `/api/ready` timeout just after the redeploy, a 30 s failed **Skip** click, and 45 s of watching the kill. The scripted part (panels through tour) took 98.7 s. |
| 3-minute short version | **50.3 s** | Recovery: Recovering at +4.2 s, Recovered at +7.3 s. Compare took about 4 s. |

**Human estimate with the Say lines: about 11:30–12:00** for the 12-minute script, compared with 12:05 in rehearsal 1. Kill → Recovered is now about 7 s instead of an endless wait. Risks to the clock:
- The presenter waits for restore feedback that never shows (N1).
- The presenter scrolls up to find the banner (N3).
- A deploy lands mid-demo (N6).
