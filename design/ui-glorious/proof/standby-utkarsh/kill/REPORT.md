# Real worker kill test — utkarsh-pc:8089

## Result

**Partial pass.** Both requested kills produced coordinator-confirmed death, restart, recovery and resumed processing. Checkpoint restoration, retained failure history and 20 injected late records were observed. **Partition reassignment was not observed**, and the UI labels surviving workers “Rejoined” without corresponding new worker-restart events.

## Scope and method

- Tested only `http://localhost:8089`; initial health was `UP` and execution `exec-20261120-051856-8aa5` was `RUNNING`, generation 2. Final health remains `UP`; final captured execution is `RUNNING`, generation 4.
- Playwright Chromium (`channel: chrome`), headed, fresh browser context, 1440×900, device scale 1, **no storageState**. Opened `/`, then Settings → Logs & Recovery through actual buttons. No page-state manipulation, mocked responses or fabricated data.
- Each PNG has a same-stem JSON containing `/api/recovery/history`, `/api/workers`, `/api/executions/current`, HTTP statuses, observation timestamps and the rendered body text. API bodies are parsed JSON, not byte-for-byte HTTP transcripts.
- Kill-relative timing is anchored to the server’s `KILL_REQUESTED.at`, not command startup. JSON `at`/`elapsedMs` identify capture initiation; `screenshotAt` is screenshot completion. Fetches, DOM reading and screenshot are sequential, **not atomic**. Screenshot completion lag was approximately 1.1–3.1 seconds; notably `03-after-3s` began at +3.148 seconds and completed at +6.246 seconds. Images and earlier DOM text can therefore show different transient labels.
- Additional API observation ran on a 200 ms timer, skipping overlapping requests. `worker-1-transitions.json` and `worker-2-transitions.json` retain changed observations, not every poll. Shorter transitions can be missed; no sampled `/api/workers` response had `alive=false` or `state=DEAD`.
- Commands used PowerShell `-NoProfile -NonInteractive`, explicit `AETHER_URL`, a 20-second process timeout and scripts’ 10-second HTTP timeouts. Browser actions and API requests also had finite timeouts. No swarm commands, port 8088 requests, remote-host operations, Docker starts, branch changes, commits or pushes were performed. No application code changed.

## Exact terminal output

All four commands exited 0 with empty stderr. `commands.json` preserves exact stdout, CRLF line endings, commands and start/finish UTC timestamps.

```powershell
$env:AETHER_URL = 'http://localhost:8089'
.\scripts\expo\worker.cmd status
```
```text
worker-1: alive=True state=RUNNING partitions=[0,3]
worker-2: alive=True state=RUNNING partitions=[1,4]
worker-3: alive=True state=RUNNING partitions=[2,5]
```
```powershell
.\scripts\expo\worker.cmd kill 2
```
```text
Killed worker-2 (engine will recover; watch Settings -> Logs & Recovery)
```
```powershell
.\scripts\expo\events.cmd late
```
```text
Sent 20 late events (they appear in Analytics -> Failed Records if the engine classifies them late)
```
```powershell
.\scripts\expo\worker.cmd kill 1
```
```text
Killed worker-1 (engine will recover; watch Settings -> Logs & Recovery)
```

The command’s “Killed” wording alone is not proof of death; the API initially records “Kill command accepted; worker death is not yet confirmed.”

## Capture timeline

All times below are UTC on 2026-11-20. Times identify **capture initiation**, not an instantaneous screenshot. UI observations include rendered text saved in JSON; significant screenshot/text races are explicit. Ownership remained worker-1=P0/P3, worker-2=P1/P4, worker-3=P2/P5 throughout all observations.

| Capture / UTC start | Relative time | What the UI showed | What the API said |
|---|---:|---|---|
| `01-before` / 05:25:38.430 | Before | Three Running workers, ~10 records/s each; six owned partitions | All alive/RUNNING; execution generation 2 |
| `02-after-1s` / 05:26:23.850 | W2 +1.039 s | All Running | Kill requested; no confirmed death yet; all alive/RUNNING |
| `03-after-3s` / 05:26:25.959 | W2 +3.148 s | W2 Dead; DOM initially labels W1/W3 Recovering. Screenshot later labels W1 Rejoined, W3 Running; displayed rates 0 | W2 alive/IDLE at 0 records/s; execution RECOVERING, generation 3, checkpoint 44; history confirms death/restart |
| `04-after-6s` / 05:26:29.167 | W2 +6.356 s | W1 and W2 Rejoined; W3 Running; processing resumed | All alive/RUNNING; generation 3 restored from checkpoint 44 |
| `05-after-12s` / 05:26:34.811 | W2 +12.000 s | Rendered text: W1/W2 Rejoined, W3 Running | All alive/RUNNING; checkpoint 44 restoration retained |
| `06-after-25s` / 05:26:47.819 | W2 +25.008 s | All Running, ~10 records/s | All alive/RUNNING; last completed checkpoint 46 |
| `07-after-45s` / 05:27:07.823 | W2 +45.012 s | All Running | All alive/RUNNING; last completed checkpoint 48; death remains in history |
| `07-history-retained` / 05:27:29.240 | W2 +66.429 s | Scrolled history: recovery finished, W2 restarted, recovery started; checkpoint 44, pause 1.1 s | Original death/restart/recovery entries still present |
| `08-late-events` / 05:27:58.267 | +15.017 s after late command returned | Analytics → Failed Records: 20 LATE records in rendered list alongside rejected records; first LATE card is partly clipped in default viewport | All workers RUNNING; late-record endpoint subsequently confirms 20 LATE entries |
| `08-late-filtered` / 05:28:20.679 | +37.429 s after late command | Actual Late filter selected; visible LATE cards with closed-window reason, event time, partition and offset | Workers/execution RUNNING |
| `09-before` / 05:28:24.285 | Before repeat | All Running | All alive/RUNNING; generation 3 |
| `09-after-1s` / 05:28:50.553 | W1 +1.081 s | All Running | Kill requested; all alive/RUNNING |
| `09-after-3s` / 05:28:52.498 | W1 +3.026 s | W1 Dead. Screenshot: W2 Rejoined, W3 Running; rates 0 | W1 alive/IDLE, 0 records/s; execution RECOVERING, generation 4, restored checkpoint 58 |
| `09-after-6s` / 05:28:55.475 | W1 +6.003 s | W1 still marked Dead but now processing; W2 Rejoined; W3 Running | All alive/RUNNING; execution RUNNING, generation 4, checkpoint 58 |
| `09-after-12s` / 05:29:01.475 | W1 +12.003 s | Rendered text: W1/W2 Rejoined, W3 Running | All alive/RUNNING; generation 4 |
| `09-after-25s` / 05:29:14.486 | W1 +25.014 s | All Running | All alive/RUNNING; recovery checkpoint 58 |
| `09-after-45s` / 05:29:34.541 | W1 +45.069 s | All Running | All alive/RUNNING; failure still in history |
| `09-history-retained` / 05:29:57.956 | W1 +68.484 s | Recovery finished, restarted, died, recovery started and kill requested all visible for W1; checkpoint 58, pause 1.3 s | Same events retained; generation 4 |
| `07-worker2-death-retained` / 05:30:53.122 | W2 +270.311 s | Scrolled specifically to W2’s Worker died entry after the second kill | Both tests’ failure records remain; all workers/execution RUNNING |

## Authoritative event timeline

These are server history event times, not screenshot times. Actor is `api:demo` for kill requests and `coordinator` for other rows.

| Worker | Event ID/type | UTC event time | Server detail |
|---|---|---|---|
| W2 | 85 KILL_REQUESTED | 05:26:22.811 | Death not yet confirmed |
| W2 | 86 WORKER_DEAD | 05:26:25.334 | Reason `re-registered` |
| W2 | 88 RECOVERING | 05:26:25.334 | Generation 2, checkpoint 44 |
| W2 | 87 WORKER_RESTARTED | 05:26:25.342 | Reason `re-registered` |
| W2 | 89 RECOVERED | 05:26:26.442 | Generation 3, checkpoint 44, duration 1108 ms |
| W1 | 104 KILL_REQUESTED | 05:28:49.472 | Death not yet confirmed |
| W1 | 107 RECOVERING | 05:28:52.177 | Generation 3, checkpoint 58 |
| W1 | 105 WORKER_DEAD | 05:28:52.178 | Reason `re-registered` |
| W1 | 106 WORKER_RESTARTED | 05:28:52.183 | Reason `re-registered` |
| W1 | 108 RECOVERED | 05:28:53.473 | Generation 4, checkpoint 58, duration 1296 ms |

Event IDs are not strictly chronological: recovery-start records have higher IDs but timestamps preceding restart records. No ordering was invented to make the sequence cleaner.

## Verdicts and limitations

| Requested check | Verdict | Evidence / limitation |
|---|---|---|
| Worker-2 truly killed / DEAD | **Pass at server-history level** | Coordinator `WORKER_DEAD` event 86, distinct from request 85; screenshot 03 shows Dead. No live DEAD/false worker snapshot or independent OS/container exit evidence was captured. Server’s reason is `re-registered`, not a process-exit code. |
| Recovering shown | **Pass, transient** | Capture 03 execution state RECOVERING and rendered worker Recovering labels; history records recovery start. Screenshot latency means the saved image already shows survivor Rejoined/Running labels. Repeat capture also has RECOVERING execution. |
| Partitions reassigned to survivors and back | **Not demonstrated** | No ownership changes in UI, timed JSON or supplementary polling. W2 retained P1/P4 and W1 retained P0/P3, even during recovery. No reassignment event appears in either test’s history. A transition shorter than polling could be missed; do not claim successful reassignment. |
| Survivors remain present | **Pass, with global pause** | Both other workers remain alive; recovered processing resumes. Screenshots show aggregate throughput falling to 0 during recovery, so uninterrupted survivor processing is not established. |
| Restored checkpoint | **Pass** | W2: checkpoint 44, generation 2→3, 1108 ms recovery. W1: checkpoint 58, generation 3→4, 1296 ms. History, execution API and recovery-history UI agree. |
| Worker rejoined | **Pass** | Both have WORKER_RESTARTED plus RECOVERED, subsequent live RUNNING/positive throughput, and Rejoined labels in rendered UI. |
| Failure stays after recovery | **Pass within observation window** | W1 death visible in screenshot `09-history-retained`; W2 death retained through +270 s and the second kill. Server/UI explicitly retain only the latest 200 events, not permanent history. |
| Displayed times are real event times | **Pass to displayed seconds** | Browser timezone `Asia/Calcutta` (UTC+05:30). All ten new event timestamps formatted in that timezone appear in captured UI; `event-time-comparison.json` preserves epoch, UTC and local strings. E.g. W2 death 05:26:25.334Z displays 10:56:25 AM; W1 recovery 05:28:53.473Z displays 10:58:53 AM. Milliseconds are not displayed. |
| Late events appear honestly | **Pass** | At +15 s, rendered list contains 20 LATE records. `08-late-records-api.json`: HTTP 200, 20 LATE records with suffixes `late-1` through `late-20`, closed-window reasons, partitions and offsets. Filtered screenshot makes cards readable. Times are explicitly described by UI as simulated event time, not wall-clock failure time. |
| Repeatability on worker 1 | **Pass for death/recovery; same reassignment gap** | Independent request 104 and coordinator events 105–108; restored checkpoint 58; all workers RUNNING by +6 s, history retained after +45 s. |

### UI issues observed, not fixed

1. **Survivors labeled Rejoined without a new restart record.** During W2’s kill, W1 is Rejoined (`03`/`04`); during W1’s kill, W2 is Rejoined (`09-after-3s`/`09-after-6s`). The only new WORKER_RESTARTED event in each cycle names the killed worker. This label is not reliable evidence that a particular survivor process restarted.
2. **Dead badge can coexist with active processing.** In `09-after-6s`, W1 remains Dead with positive throughput while the API is RUNNING. The panel honestly warns that death/restart marks persist at least three seconds and live numbers may already be newer; this is historical marking, not current liveness.
3. **Global status remains Running while throughput is zero.** Both +3-second screenshots show the top-level Running badge with 0 ev/s while recovery occurs. The execution API, not that badge, establishes RECOVERING.
4. **Default Failed Records viewport clips the first late record.** The requested +15-second screenshot is preserved unchanged. A later screenshot uses the real Late filter to expose readable LATE cards.
5. **No reassignment proof.** Ownership consistently stays on the original worker. This task records the gap rather than fabricating an intermediate state.

## Handoff

Added 19 real screenshots, matching API/UI JSON captures, exact command output, transition observations and this report under the assigned proof directory. Checked both worker kills in visible Playwright Chromium and against coordinator recovery history, current execution and worker APIs; also confirmed all 20 injected late records. Both workers recovered, with the reassignment and survivor-label limitations documented above; no application fix was attempted. No commit or push was made, as instructed. The requested separate report under `swarm/reports/` was not written because repository instructions prohibit modifying anything under `swarm/`; this handoff remains in the authorized proof directory.
