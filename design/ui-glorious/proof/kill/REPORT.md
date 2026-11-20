# Worker kill test: blocked before execution

Target: `http://localhost:8089` on this PC only. The required healthy API and RUNNING execution never became available during the 20-minute readiness window. Stopped under the task's explicit precondition rule; no worker kills or late-event injection were attempted.

## Timeline

All times below are UTC terminal-observation times, **not recovery-history event times**. Evidence: [precondition.json](precondition.json).

| Time (UTC) | What the UI showed | What the API check said |
| --- | --- | --- |
| 2026-11-19 23:40:46 | App not opened; readiness prerequisite pending. | Poll 1: both `/api/health` and `/api/executions/current` failed to connect; curl exit 7. |
| During the readiness wait | Successfully launched visible Playwright 1.65.1 Chromium, 1440×900, new context without storageState; left at `about:blank`. No application UI observed. | Requests repeated approximately every 15 seconds, each bounded to 3 seconds. No successful health/current response observed. |
| 2026-11-20 00:00:32 | No application UI observed. | Poll 79, elapsed 1185 seconds: both endpoints still failed to connect; exact final errors saved in JSON. |
| Approximately 2026-11-20 00:00:46 | No recovery or failed-records screen captured. | Readiness command hit its 1200-second deadline; observed wall time 1200.07 seconds. |
| After the deadline | Closed only the blank browser created for this test. | No kill, status, late-event, recovery-history, or workers requests issued. |

Some captured curl errors were truncated to `c`; full first/final connection-refusal errors are retained in JSON. The command deadline ended the polling loop before its own timeout marker could print.

## Verdict per requested item

| Requested check | Verdict |
| --- | --- |
| Healthy preview and RUNNING execution | **BLOCKED:** connection failures throughout the allowed readiness window. |
| Logs & Recovery before/after screenshots | **NOT TESTED:** app prerequisite unavailable; no screenshots 01–07. |
| Exact worker status/kill output | **NOT RUN:** neither command executed. No kill-success output claimed. |
| Worker-2 DEAD/killed from server evidence | **NOT TESTED:** no kill and no server worker evidence. |
| Recovering state | **NOT TESTED.** |
| Partitions reassigned to survivors and restored | **NOT TESTED.** |
| Restored checkpoint identity | **UNKNOWN:** no recovery-history JSON available. |
| Worker-2 rejoined | **NOT TESTED.** |
| Failure retained in history after recovery | **NOT TESTED.** |
| UI timestamps match real history event timestamps | **NOT TESTED:** no history response or recovery UI observed. Timeline above is polling time only. |
| Late events shown in Analytics → Failed Records after ~15 seconds | **NOT TESTED:** injection not run; no screenshot 08. |
| Worker-1 repeatability | **NOT TESTED:** second kill not run; no screenshots 09. |

## Environment observation and remaining work

C: had 974,848 bytes free when checked. Installing an isolated Python Playwright runtime failed with `OSError: [Errno 28] No space left on device`; an existing cached Node Playwright runtime then launched Chromium successfully. Disk pressure is observed, but its connection to stack unavailability is **not established**.

No application code, swarm services, branches, or commits were changed. No requests targeted port 8088 or another PC. The only artifact under `swarm/` is the required short task report; no implementation there was opened.

Remaining: once the preview stack is healthy and its current execution RUNNING, execute the full worker-2 timeline, late-event scenario, and worker-1 repeat. There is no recovery correctness verdict from this blocked run.
