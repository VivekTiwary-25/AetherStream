# New UI expo demo — 20 minutes

Vivek drives; keep the browser on the new UI at `http://localhost:8088`. Use only the live stream and controls below. The geographic truck paths are display mappings; telemetry and assignments are live.

## Before the slot

- From the repository root, start the host AI bridge in PowerShell. The bridge uses the Vachana Claude profile when present; set it explicitly for this launch:
  ```powershell
  $env:CLAUDE_CONFIG_DIR = "$env:USERPROFILE\.swarm\profiles\vachana-claude\claude"
  powershell -ExecutionPolicy Bypass -File scripts/ai-bridge/start.ps1
  ```
  Leave that terminal running. If that profile directory is absent, remove the assignment and use the launcher's configured login.
- Open a second PowerShell or cmd terminal in the repository root and add the expo scripts to this session's PATH:
  ```powershell
  $env:PATH = "$PWD\scripts\expo;$env:PATH"
  ```
  In cmd instead: `set PATH=%CD%\scripts\expo;%PATH%`. Bash: `export PATH="$PWD/scripts/expo:$PATH"`.
- Check the app and API are already running at `http://localhost:8088`; the worker commands use that URL by default. Check the AI bridge health at `http://127.0.0.1:18090/health`.
- In the expo terminal, run `worker status`. Expect the current worker IDs, states and partitions; do not kill a worker during setup.
- Know the demo commands (run only at the indicated step): `worker kill 2` and `events late 10`. `worker kill` sends the existing API action; it is not a Docker command. Never repeat it during recovery. Late-event classification depends on the engine watermark.
- If the new UI or API is unavailable, use `http://localhost:8088/?ui=classic`; the classic controls remain available.

## Seven-step run of show

### 1. Home — highway (about 1 minute)
- **Click:** Open `http://localhost:8088/`. Point out the left navigation and the top-right status pill; click **Live World** to continue.
- **Audience sees:** The highway video (or its poster with reduced-motion preference), live engine status and event rate.
- **Say:** “This is the live fleet view entrance; the status and rate come from the running stream.”
- **If it fails:** Reload once. If the new UI still fails, switch to `?ui=classic` and continue with the classic demo.

### 2. Live World — map (about 2 minutes)
- **Click:** In the left navigation, click **Live World**.
- **Audience sees:** The Mysuru map and the live truck markers; the map moves up into view.
- **Say:** “The map shows the six configured trucks against real Mysuru roads; their telemetry is live.”
- **If it fails:** Wait for the map and live telemetry to load. If unavailable, switch to `?ui=classic` rather than claiming frozen positions are live.

### 3. Live World — truck details (about 2 minutes)
- **Click:** Hover a truck marker (or focus/select it with the keyboard).
- **Audience sees:** Its details card: truck ID, status, speed, route ID, display path and current partition/worker assignment. The card identifies the path as a display mapping.
- **Say:** “The route is a geographic display mapping; speed, status and worker assignment come from the system.”
- **If it fails:** Try another marker or focus it with Tab. If telemetry is stale, call that out; do not present a frozen speed as current.

### 4. Analytics — truck speeds (about 3 minutes)
- **Click:** Click **Analytics**, then **Truck Speeds**, then click a truck row.
- **Audience sees:** Current truck speed, completed-window average and reading count; selecting a row opens that truck’s speed history in the same window. Use **Back** to return to the list.
- **Say:** “This combines the current simulator reading with completed speed windows and the recent history.”
- **If it fails:** Wait for live samples and completed windows. If the list is empty, say the stream has not produced the required data; use `?ui=classic` only if needed to continue.

### 5. Analytics — failed records (about 3 minutes)
- **Click:** In the expo terminal, run `events late 10` once. In the app, open **Analytics** → **Failed Records**; choose **LATE** to narrow the list, or leave **All** selected. Click a record to inspect its details.
- **Audience sees:** Late or rejected records when the engine classifies them, with event time, partition, offset and reason. Empty state is **No failed records yet.**
- **Say:** “These are records the live engine classified as late or rejected, with the reason and source position.”
- **If it fails:** `events late` reports how many events were sent, not that they were classified late. If no late records appear, explain that the watermark determines classification; do not invent records or reasons.

### 6. Pipelines — Ask AI, review and run (about 5 minutes)
- **Click:** Click **Pipelines**. In **Ask AI**, enter `Alert me when any truck goes over 60 km/h`, click **Generate**, and review the generated steps. Click **Open in editor** to open the new tab; review the handed-off pipeline there, then click **Run**.
- **Audience sees:** A pipeline preview (AI-generated or explicitly marked as a saved example), then the editor and a **Pipeline results** window with results or an honest run error.
- **Say:** “The pipeline is validated before use; this run reads the live stream and reports its results here.”
- **If it fails:** If AI is unavailable, use the displayed saved example only when marked **Showing a saved example: AI was unavailable**; review it before running. If the editor or run fails, show the error and continue—do not claim a successful result.

### 7. Settings — logs and recovery (about 4 minutes)
- **Click:** Return to the app tab. Click **Settings** → **Logs & Recovery**. In the expo terminal, run `worker kill 2` once.
- **Audience sees:** Worker 2’s dead state, the recovery pause, checkpoint restore, partition ownership changes, surviving workers and (if it restarts) the rejoined state. The failure remains in history.
- **Say:** “The engine recovers from a completed checkpoint and records the worker failure; the visible pause is part of recovery.”
- **If it fails:** Do not repeat the kill. Wait for the live recovery feed; if the command reports an error, use the classic UI’s **Kill worker** flow once, then watch **Logs & Recovery** if available. If recovery is still in progress, describe only the state visible on screen.

Keep the remaining time for questions. Never describe unobserved recovery phases or pipeline results as completed.
