# Expo rehearsal 1: DEMO.md against the live app (2026-11-19)

- **Live app:** public link from `boss/STATE.md` / `boss/NEEDS_VIVEK.md` (same URL in both, the trycloudflare quick tunnel).
- **Deployed commit (`/api/version`):** `038370142ee02caa9334021076231f75b8bcb061`. The footer shows **Deployed version: 0383701**, which matches. `builtAt` is empty.
- **`/api/ready`:** `UP` (postgres, kafka, coordinator, simulator).
- **Browser:** Playwright 1.65 (Python), headed Chrome (`channel="chrome"`), 1440x900, driven over the public tunnel from a teammate PC (not vivek-pc).
- **Run 1:** a full scripted pass of every DEMO.md step, 337 s of automation. Log: [rehearsal-1/run-log.txt](rehearsal-1/run-log.txt). Screenshots 01-52.
- **Run 2:** a focused re-check of the Late tab and the recovery banner, polled every 0.15 s. Log: [rehearsal-1/run-log-2.txt](rehearsal-1/run-log-2.txt). Screenshots 53-57.
- **Caveat:** the live stack was shared. Between my probe and run 1 another client restarted the execution: the checkpoint went from #24 to #1, and Rejected/Late went from 25/20 to 1/0. Some numbers below may come from that, not from the app.

Steps that need a local shell on vivek-pc (`docker compose ps/images/inspect`, `curl localhost:…`, Grafana `localhost:13000`) can't be done over the tunnel. They are marked **N/A (remote)**, and I checked their API equivalents through the public URL.

## Pre-flight ("30 minutes before")

| # | DEMO.md expects | What happened | Time | Screenshot | Verdict |
|---|---|---|---|---|---|
| P3 | "Confirm no **MOCK DATA** badge. Confirm **AETHER CITY**, trucks, and a non-zero `events/s`" | No MOCK DATA. AETHER CITY "2 km grid · 8 trucks", `40.9 events/s`, all 7 legend entries. The intro splash showed "a fault-tolerant stream engine" and **Skip**. The page took 6.0 s to reach network-idle over the tunnel. | 6 s | [01](rehearsal-1/01-pre-api-version.png), [02](rehearsal-1/02-pre-intro-splash.png), [03](rehearsal-1/03-pre-app-loaded.png) | OK |
| P4 | "If the banner says **No execution**, click **Start demo pipeline** … **Alerts** can show the stuck-truck line" | An execution was already running, so I didn't need **Start demo pipeline**. **The stuck-truck alert never appeared** during about 25 min of observation. `/api/results/alerts` = `[]` and summary `alerts:0`. truck-007 drove normally (e.g. 23-36 km/h, `DRIVING`). | — | [08](rehearsal-1/08-s2-alerts.png) | **BROKEN** |
| P5 | "`/api/ready` and `/api/version`. Write down `commit`" | ready UP, commit 0383701… (above). | 2 s | [01](rehearsal-1/01-pre-api-version.png), [04](rehearsal-1/04-s1-api-ready.png) | OK |
| P6 | "Open Grafana `http://localhost:13000`" | N/A (remote): not exposed on the tunnel. `/grafana/` returns the SPA's index.html, not Grafana. | — | — | N/A |
| P7 | "Open these runs in tabs. They were green" | All 7 linked runs return 200 and show **Success**. Anonymous viewers see **Sign in to view logs**, so the presenter must be signed in to GitHub to open the log lines DEMO quotes. | 25 s | [46](rehearsal-1/46-s9-cd-run.png), [47](rehearsal-1/47-s11-unit.png)-[52](rehearsal-1/52-s11-compose.png) | OK (sign in first) |

## 12-minute script

| DEMO step | Expected (quoted from DEMO.md) | What actually happened | Time (automation) | Screenshots | Verdict |
|---|---|---|---|---|---|
| **0:00-0:45 Stack is up** | "`docker compose ps` … Healthy app containers. Init containers exited." | N/A (remote). Equivalent: `/api/ready` all UP. The page footer says "3/3 workers · healthy". | 5 s | [04](rehearsal-1/04-s1-api-ready.png) | N/A (remote) |
| **0:45-2:00 City, stream, engine panels** | "Legend **Driving** … **Late**. Speed rows like `32.4 km/h`. Deliveries: **N deliveries in the last completed sim minute** … Workers show **Running**, **Source queue**, **Sink queue**, capacity 1000. … **Alerts** contains `truck-007 has not moved for 10 min`." | Legend complete. Speed rows (`7.4 km/h` … `29.5 km/h`), "4 deliveries in the last completed sim minute" and "sim minute · latest highlighted" all OK. Workers: 3x **Running**, but the queues read **`0/32`, not capacity 1000**. Alerts: **No alerts — every truck is moving.** (no stuck line). Truck hover: the scripted mouse sweep didn't land on a truck, so no **Speed/Route/Partition** tooltip; DEMO already says "if you can". After any page scroll, the city canvas draws offset down-right with a large black void top-left (compare [05](rehearsal-1/05-s2-city-dashboard.png) to the probe), and the engine column is covered by the panels. | 16 s | [05](rehearsal-1/05-s2-city-dashboard.png) [06](rehearsal-1/06-s2-speed-windows.png) [07](rehearsal-1/07-s2-deliveries-min.png) [08](rehearsal-1/08-s2-alerts.png) [09](rehearsal-1/09-s2-workers.png) [10](rehearsal-1/10-s2-truck-hover.png) | **CONFUSING** (32 vs 1000; no alert) + UGLY (city offset) |
| **2:00-3:30 Pipeline editor** | "**Broken logistics demo** … **This pipeline has 3 mistakes - press Validate** … three rows and three **Show me** … `Valid - 11 operators, 6 partitions`, then **Plan order:**" | All as scripted. Palette has all 7 operators. The hint is shown, **Run** stays disabled before Validate, and there are 3 errors (TYPE_MISMATCH alertSink, DANGLING_NODE del, TYPE_MISMATCH speed1m) with 3 **Show me**. **Show me** focuses the node. The good example prints `Valid - 11 operators, 6 partitions` and `Plan order: src → del → kb2 → … → alertSink`, and **Run** is enabled. I didn't click Run, per DEMO. Minor: the error order on screen is alertSink, del, speed1m, the reverse of DEMO's list. Only 2 of the 3 rows fit above the fold at 900 px; the third needs a scroll in the errors region. | 12 s | [11](rehearsal-1/11-s3-editor-open.png) [12](rehearsal-1/12-s3-broken-loaded.png) [13](rehearsal-1/13-s3-broken-validated.png) [14](rehearsal-1/14-s3-show-me.png) [15](rehearsal-1/15-s3-good-validated.png) [16](rehearsal-1/16-s3-editor-closed.png) | OK (minor UGLY: 3rd error below the fold) |
| **3:30-5:15 Late events** | "Status **20 late events sent**. Tabs **Rejected (N)** / **Late (N)**." | "20 late events sent" appeared at once. The tabs updated live to **Late (20)** without a reload; DEMO's fallback warns it may not. The Late tab is `role="tab"`. `/api/results/dead-letters?kind=LATE` returned rows with reason `window [...) already closed (watermark …)`. | 3 s | [17](rehearsal-1/17-s4-late-sent.png) [18](rehearsal-1/18-s4-dead-letters-late.png) | OK |
| **3:30-5:15 Slow output** | "Hint: **Output slowed: queues fill up (bounded at 1,000) and the engine pushes back.**" / fallback: "It did not appear within 75 s" | Hint shown verbatim. **Backpressured** (amber) appeared on all 3 workers and on Chaos controls **within ~9 s**, with queues `32/32`. The hint says "bounded at 1,000" while the bars next to it say `/32`. Setting **Off** drained it. | 30 s (incl. 24 s waiting) | [19](rehearsal-1/19-s4-slow-200.png) [20](rehearsal-1/20-s4-workers-under-slow.png) [21](rehearsal-1/21-s4-slow-off.png) | **CONFUSING** (1,000 vs 32); DEMO fallback is out of date (it works now) |
| **3:30-5:15 Kill + recover** | "Confirm text: **Kill worker worker-1? It will crash without warning.** … **Busiest worker (worker-1)** … **Worker worker-1 killed.** Banner **Recovering from checkpoint #N**, then **Recovered**." | Confirm text and **Busiest worker (worker-1)** are exact. Run 1 timeline: +0.6 s **Worker worker-1 killed.**; +3.5 s banner **Recovering from checkpoint #25**, Generation 1→2; +15.6 s banner back to **Running / Checkpoint #N saved**. **The banner never showed "Recovered".** Run 2 (0.15 s polling) was the same: Recovering #23 from +3.6 s to +15.6 s, then straight to "Checkpoint #24 saved". The timeline panel showed **Generation 2 · Restored from #25** but no "Recovering … (generation G → G+1)" title and no "Recovered in" line. Worker cards stayed at 3x Running, with no visible dip. The success line "Worker worker-1 killed." stays in Chaos controls for the rest of the session, even after Restore. | ~16 s recovery (80 s observed) | [22](rehearsal-1/22-s4-kill-confirm.png) [23](rehearsal-1/23-s4-kill-clicked.png) [24](rehearsal-1/24-s4-recovering.png) [25](rehearsal-1/25-s4-after-kill-top.png) [26](rehearsal-1/26-s4-workers-after-kill.png) [27](rehearsal-1/27-s4-timeline-after-kill.png) [55](rehearsal-1/55-r2-banner-recovering.png) [56](rehearsal-1/56-r2-workers-after-kill.png) [57](rehearsal-1/57-r2-timeline-after-kill.png) | **BROKEN** (no "Recovered" state; DEMO tells presenter to use it as the proof) |
| **5:15-6:15 Compare with reference** | "Heading **N / N results match** … **Checked up to checkpoint #N - replayed … events in … ms**" | **598 / 598 results match**, "Checked up to checkpoint #33 - replayed 210,405 events in 1,671 ms". **Comparing…** was visible for under 1 s. Problem: after the later Restore the panel kept showing this old 598/598 for the old execution. | 4.5 s | [28](rehearsal-1/28-s5-comparing.png) [29](rehearsal-1/29-s5-compare-result.png) | OK (stale after restore: see P2) |
| **6:15-7:15 Savepoint / Restore / Rescale** | "A `savepointId` and checkpoint number … **Restart the pipeline from this savepoint?** … Rescale … HTTP 409 … `execution ID does not need a rebalance`" | **Take savepoint** gave `sp-exec-38e74bc1-…-35 at checkpoint #35`. **Restore** showed the native confirm "Restart the pipeline from this savepoint?" and started new execution `exec-ad96d2d2…`, restoredFromCheckpoint 35. **Rescale** gave 409 (the console logs a 409 error) and the message "execution exec-ad96d2d2-… does not need a rebalance" plus the "Pauses at the next checkpoint …" sentence. UGLY: the Operations list holds **6 savepoints** from earlier sessions (incl. checkpoint #1788, #426), so the list needs an inner scroll and pushes **Rescale** and its result below the panel fold. Right after Restore the timeline reads **Last checkpoint #0 (0 ms)**. Restore also resets Late to 0 and empties Alerts again. | 18 s | [30](rehearsal-1/30-s6-savepoint.png) [31](rehearsal-1/31-s6-restore.png) [32](rehearsal-1/32-s6-rescale.png) | OK / UGLY |
| **7:15-8:00 Scenarios** | "Panel status **Active: Rush hour** (or the label you clicked)" | Rush hour, Breakdown, Surge and Reset each showed **Active: <label>** within 2 s. Clicking **Reset** shows **Active: Reset**, while `/api/simulator/state` says `scenario: "none"`, which reads oddly ("Reset" isn't a scenario). The default Breakdown truck in the select is truck-005. | 35 s | [33](rehearsal-1/33-s7-rush-hour.png) [34](rehearsal-1/34-s7-breakdown.png) [35](rehearsal-1/35-s7-surge.png) [36](rehearsal-1/36-s7-reset.png) | OK (minor CONFUSING: "Active: Reset") |
| **8:00-8:45 Tour + footer** | "**The city**, **The stream into the engine**, **Live panels**, **Pipeline editor**, **Chaos**, **Kill a worker**, **Compare with reference**, **DevOps** … **Step N of 8**" / footer = 7 chars of commit | All 8 steps are in the right order, shown as "STEP N OF 8" (CSS uppercase), with Close/Back/Next/Finish. Steps 1-2 ("The city", "The stream into the engine") spotlight a mostly black area because of the city offset bug, and the engine is hidden behind the panels. The tour card sits on top of the Speed windows panel. Footer **Deployed version: 0383701** = `/api/version`. | 59 s (~7 s/step) | [37](rehearsal-1/37-s8-tour-1.png)-[44](rehearsal-1/44-s8-tour-8.png), [45](rehearsal-1/45-s8-footer.png) | OK / UGLY (steps 1-2) |
| **8:45-10:00 CD run** | "jobs **publish** and **deploy** … `PASS: health is UP and deployed commit is d1714e3…`" | Run 44438884629 is green with lint-cd, publish 5m35s and deploy 1m23s. It is for **d1714e3, but the live footer is 0383701**, so the presenter must say "that run is one older push". The logs need GitHub sign-in to read the PASS line. | 5 s | [46](rehearsal-1/46-s9-cd-run.png) | CONFUSING (stale run id) |
| **10:00-10:45 Containers + Grafana** | "`docker compose ps/images/inspect` … Grafana **AetherStream engine**" | N/A (remote): vivek-pc shell and localhost Grafana are not reachable over the tunnel. Not verified. | — | — | N/A (must be checked on vivek-pc) |
| **10:45-12:00 Tests** | Unit 44439772370, Testcontainers 44395770221, e2e 44410772993, kind 44398295492, Terraform 44395471523 | All 5 (plus compose 44439772374) show **Success**. Log lines not verified (sign-in needed). | 21 s | [47](rehearsal-1/47-s11-unit.png) [48](rehearsal-1/48-s11-testcontainers.png) [49](rehearsal-1/49-s11-e2e.png) [50](rehearsal-1/50-s11-kind.png) [51](rehearsal-1/51-s11-terraform.png) [52](rehearsal-1/52-s11-compose.png) | OK |

**Run 2 extra:** after a reload (post-Restore execution) the **Late** tab showed **Late (0) · "No late records yet."** and `/api/results/dead-letters?kind=LATE` = `[]`. Late dead letters belong to an execution, so a Restore or Run wipes them from the UI ([54](rehearsal-1/54-r2-dead-letters-late-tab.png)). DEMO's fallback "Reload once" would therefore *lose* them if a restore happened in between.

## Timing vs the 12-minute plan

- **Automated clicks only:** 337 s (5.6 min) for the whole script, including 24 s spent waiting for backpressure and about 80 s watching recovery.
- **Live part with speech** (presenter pace, DEMO "Say" lines read aloud, about 15-25 s each): roughly 10-11 min. That fits the 12-minute plan with about 1 min spare, as long as **Run**/**Restore** are skipped. If the presenter clicks Restore, the stuck-truck alert is gone for the rest of the slot (and it never showed for me anyway).
- **Slowest live moments:**
  - The kill → recovery window: about 16 s of "Recovering" with nothing new to point at.
  - The tour: 8 steps × ~7 s plus speech, close to 1 min.
  - The scenarios: 4 buttons × ~2 s plus talk.
- **First page load** over the tunnel took 6 s, plus the 2 s intro splash.

## Ranked fix list

### P1: would embarrass us on stage

1. **The "Recovered" banner never appears after a kill.** The banner goes **Recovering from checkpoint #N** → **Checkpoint #N+1 saved** after about 12 s. DEMO.md tells the presenter "Use banner **Recovered**" as the proof, twice, including in "If a step fails".
   - Where: `web/src/app/panels/LivePanels.tsx` ~line 177. The label is `Recovered` only while `lastExecutionEvent?.event === 'RECOVERED'`, and the next CHECKPOINT_COMPLETED event overwrites it straight away. Either the RECOVERED event isn't sent on the WS, or it is replaced within one frame.
   - Fix: keep a sticky "Recovered in X s" state for about 20 s after RECOVERED (ignore checkpoint events for the banner during that time). Check that control-api actually forwards a RECOVERED execution event (contract 04).
   - Same issue in `web/src/app/timeline/CheckpointTimeline.tsx` ~line 140: the "Recovered in" line never showed either.
2. **The stuck-truck alert never fires.** About 25 min on the live stack: `alerts: 0`, and truck-007 drives normally. DEMO's 30-min checklist and the 0:45 step promise `truck-007 has not moved for 10 min`.
   - Possible causes: the simulator's scripted truck-007 stop happens only once after simulator start, and other clients kept restarting executions/scenarios.
   - Fix: have the boss confirm on vivek-pc; check the simulator's stuck-truck schedule (contract 01).
   - Otherwise change DEMO.md: click **Breakdown** with **Truck truck-007** about 2 min before the slot, and say so in the script.
3. **The queue capacity shows `/32`, but the copy says 1,000.** The Workers panel bars read `0/32` and `32/32`. The Chaos hint says "bounded at 1,000", DEMO.md says "capacity 1000", and examiner Q4 says "capacity 1000 … **Backpressured** at 90% full". An examiner who reads the bars will catch this.
   - Fix one side: either set the engine queue capacity to 1000 in the deployed config (compose env / contract 03 value), or change the hint in `web/src/app/chaos/ChaosPanel.tsx:94` to use the live `capacity` value instead of the hard-coded "1,000", and fix DEMO.md Q4 and the 0:45 step.
4. **The live demo is shared and other people's actions change it.** During my 6-minute run someone else restarted the execution (#24 → #1) and dead-letter counts reset.
   - Fix (process): from 30 min before the slot, nobody but the presenter drives the public link: no swarm rehearsals, no `Send malformed`. Consider an access gate on the tunnel during expo hours.
   - Old savepoints from previous days also clutter Operations (see P2-2).

### P2

1. **The city canvas draws offset after any page scroll.** A large black void appears top-left, the grid moves down-right, and the engine core is hidden under the panels. Tour steps 1-2 then spotlight black space.
   - Where: `web/city` Pixi stage resize/scroll handling (the AppShell city layer).
   - Fix: recompute the stage size from `window.innerWidth/innerHeight`, not the document scroll height. Or make the city a `position: fixed` layer that doesn't change on scroll.
   - Presenter workaround: press **Reset view** / double-click the map before the tour.
2. **Operations lists every savepoint ever taken** (6 entries, incl. checkpoints #1788 and #426 from older executions). **Rescale** and its 409 message end up below the panel's inner scroll.
   - Where: `web/src/app/ops/OpsPanel.tsx`.
   - Fix: show the 3 newest savepoints for the current pipeline, with Rescale and its result above the list. Before the expo, delete the old savepoints on vivek-pc.
3. **Stale results after Restore.** "Worker worker-1 killed." (Chaos), "598 / 598 results match" (Reference) and the old checkpoint number stay on screen for the new execution. Right after Restore the timeline shows **Last checkpoint #0 (0 ms)**.
   - Fix: clear the Chaos and Compare result state when `executionId` changes.
   - Fix: hide "Last checkpoint" until the first checkpoint of the new execution.
4. **DEMO.md fallback text is now wrong in two places.**
   - "Backpressured … did not appear within 75 s at 200 ms per record": it appears in about 9 s now, so the presenter can wait for it.
   - "Late rows may stay **Late (0)** until a reload. Reload once": it updated live. A reload after a Restore shows 0 because late dead letters belong to the execution.
   - Fix: update `DEMO.md` 3:30 step.
5. **The CD run in DEMO.md (44438884629, d1714e3) is not the deployed commit (0383701).**
   - Fix: before the slot, replace it with the newest green `cd` run whose deploy smoke line matches the footer (`gh run list --workflow cd.yml --branch main --limit 5`). Sign in to GitHub in the demo browser, because anonymous viewers get "Sign in to view logs" and can't show the PASS lines.
6. **`builtAt` is empty in `/api/version`.**
   - Fix: pass a build timestamp build-arg in `cd.yml` / the control-api Dockerfile, or drop the field.

### P3

1. **Validation errors appear in a different order than DEMO.md lists them**, and only 2 of 3 fit above the fold at 1440x900.
   - Fix: make the validation panel taller (`web/src/app/editor/PipelineEditor.tsx` errors region), or list them in DEMO order (graph order).
2. **"Active: Reset"** after clicking Reset, while the simulator reports `scenario: "none"`.
   - Fix: show **Active: none** / "Normal traffic" in `web/src/app/scenarios/ScenarioPanel.tsx`.
3. **The truck hover tooltip is hard to hit** (the automated sweep missed every truck).
   - Fix: make the hit radius bigger in the city layer, or tell the presenter to skip the hover.
4. **The console logs `Failed to load resource: 409`** on Rescale. It's harmless, but visible if DevTools is open.
   - Fix: none needed; just keep DevTools closed.
5. **First load over the tunnel took about 6 s** to network-idle, plus the 2 s intro.
   - Fix: present from `localhost:8088` on vivek-pc, as DEMO says, and open the tab 1 min early.
