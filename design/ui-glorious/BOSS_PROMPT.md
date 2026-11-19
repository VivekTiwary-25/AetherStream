MISSION: ui-glorious — AetherStream expo UI rebuild (overnight, 20 Nov 2026)

You are the boss Claude Code for Vivek's swarm. Read this whole prompt before acting.

==================================================
STEP 0 (do this first, before anything else)
==================================================
a) Run `swarm status` (and `omp usage` if useful). Report to Vivek in plain language: which PCs are online (utkarsh-pc, vimlesh-pc, upendra-pc, vivek-pc), which agents and accounts are available on each, and how much usage is left. If a PC is down or logged out, say exactly what Vivek must do to fix it. If fewer than 3 worker PCs are up, propose how to rebalance the tracks below.
   (Snapshot from heartbeats at 00:46 IST: all 4 PCs online, no alerts, all workers idle. Codex left: utkarsh 77%, vimlesh 55%, upendra 85%, vivek 76%. Grok on vivek-pc is at 12%: avoid it. vimlesh-pc also has Claude. vachana-claude on vivek-pc has no task kinds assigned; it is a good account for the Ask AI `claude -p` calls so they don't use Vivek's main Opus.)
b) Design assets. This zip (ui-glorious-pack) is in Vivek's Downloads folder on vivek-pc (%USERPROFILE%\Downloads). It contains:
     - BOSS_PROMPT.md (this file)
     - design/ui-glorious/home-mockup.png (highway, truck from behind; also the rear-view truck reference)
     - design/ui-glorious/liveworld-mockup.png (Mysuru map with trucks and glass sidebar; also the 3/4-view truck reference)
     - design/ui-glorious/design-pack.md (GPT's design handoff)
   The home looping VIDEO is NOT in the zip. It is a separate mp4 (720p) in the same Downloads folder. Find it by recent date and type. If unsure which file it is, ask Vivek in one short message.
   Create branch `ui-glorious` from `main`. Copy the design/ui-glorious/ folder into the repo, and copy the video in as design/ui-glorious/home-loop.mp4. If the video is over 50 MB, do one quick ffmpeg compression on vivek-pc first (a short 720p clip takes seconds; this is the only allowed media job on vivek-pc). Commit and push so every machine gets them.
c) Then post a short plan, do the recon (section 1), build the shell, and start the four tracks. Do not wait for further approval unless something only Vivek can fix comes up.

==================================================
0. CONTEXT AND HARD RULES
==================================================
- Project: AetherStream (github.com/VivekTiwary-25/AetherStream), a fault-tolerant distributed stream processor (mini Flink) with a trucks demo. The engine, API, CI/CD and docs are DONE and stable. This mission is a UI rebuild plus a few small, real features.
- The CDD expo is 21 Nov. It is a 20-minute demo that Vivek drives himself. Build ONLY what is on the demo path (section 2). Hide or skip everything else. Never show a fake control: anything visible must really work.
- Branch: ALL work goes on `ui-glorious`. Never commit to or merge into `main`. main auto-deploys to vivek-pc, which is the live demo. Vivek decides when to merge.
- Do not change the engine's recovery mechanism, the simulator's event schema, or anything that breaks the existing correctness tests. All existing tests must still pass on ui-glorious.
- Keep all data genuine. No made-up statistics. Where something is a display mapping (truck geography, see Track A), keep it honest in code comments and in your report.
- No secrets in the repo (public repo). No API keys anywhere.
- Follow the existing swarm rules: protect Vivek's Opus (stop at 90% used), use the swarm commands (queue, tasks, review, merge, reject), and keep the self-healing behaviour. Only stop for a human when only a human can fix it, and then say exactly what to do.
- Machines (default; rebalance based on Step 0):
  - utkarsh-pc (RTX 4050, always on): full stack (engine + Kafka + simulator + API), integration builds, the preview server, and Track C.
  - vimlesh-pc: Track A (Map).
  - upendra-pc: Track D (Pipelines).
  - Track B (Analytics): a second worker on utkarsh-pc, or rebalance if needed.
  - vivek-pc (RTX 2050, low RAM): ONLY the boss and the live demo. No builds, no heavy work. Exceptions: the Step 0 file copy and video compression, and small single-call tests of headless `claude -p` for Track D's auth check.
- Preview: run ui-glorious on utkarsh-pc at localhost:8089 and expose it with a quick tunnel (cloudflared or similar). Never deploy ui-glorious onto vivek-pc's live demo.

==================================================
1. FIRST 45 MINUTES: RECON + SHELL (boss + one agent)
==================================================
Recon (read the code, report in plain language, about 10 lines per item):
  a) How the old "Kill worker" button kills a worker (endpoint, process, docker?).
  b) What is in web/src/app/editor/: is there a node canvas we can reuse?
  c) Can the engine run user-defined pipelines at runtime? (Expected: no. Pipelines will run in the API layer anyway.)
  d) Does the simulator already support a truck breakdown or stopped status? Does it support start, pause, reset or time speed? List which ones exist.
  e) For each old panel (speed windows, deliveries/min, alerts, workers, dead letters, checkpoint timeline): which hook, endpoint or WebSocket message it uses.
  f) The worker-card bug: the rehearsal showed worker cards not reliably showing a killed worker even when the engine was in recovery. Find where worker state comes from and your best guess at the cause.
  g) How the simulator's truck count is configured (default 8; we need 6).
  h) The exact headless flags for Claude Code on this version (`claude --help`): print-mode, JSON output, model choice, disabling tools, config dir.

Shell (shared foundation, built BEFORE the tracks start, owned by the boss):
  - New UI lives in web/src/glorious/ with folders shell/, home/, map/, analytics/, logs/, pipelines/. Each track only edits its own folder. Shared files (shell/, tokens, router) change only through the boss.
  - The new UI is the default. The old UI stays fully working at `?ui=classic` (and the old city at `?city=classic` keeps working inside it). The existing Playwright tests, DEMO.md and docs/expo/CHEATSHEET.md must keep passing or working against `?ui=classic`. Update the test base URL if needed. Do not rewrite the old tests.
  - Design tokens (CSS variables) and these shared components every track uses:
      GlassSurface: neutral frosted white, no blue hue. Start from background rgba(255,255,255,0.55), backdrop-filter blur(20px) saturate(140%), 1px rgba(255,255,255,0.6) inner border, soft wide shadow, radius 20-24px. Match the mockups.
      GlassWindow: draggable and resizable (use a small library such as react-rnd), close button, focus brings it to the front, keyboard accessible, a back arrow for drill-down views. Clicking a row changes the window's content without changing its size or position.
      ClayButton: soft, tactile, clay-like primary button, with a 44px or larger touch target.
    Font: Inter (or the closest match to the mockups). Text must meet WCAG AA contrast on glass. Use a slightly stronger glass behind text-heavy windows.
  - Sidebar: one glass sidebar on the left. Items: Home, Live World, Analytics, Pipelines, Settings. On Home it is a minimal icon rail. On the other screens it shows labels, and the active item is a warm cream pill (see the Live World mockup). Clicking Analytics or Settings transforms the same sidebar: the other items fade out, the sub-options fade in, and a back arrow restores normal navigation. Never add a second sidebar.
  - Status pill, top right, on every screen: status (Running, Degraded, Recovering, Stopped) as text AND a colour dot, plus live events/s. Both come from real engine data.
  - Motion: only the Home → Live World slide-up transition, the sidebar transform, and window open/close. Respect prefers-reduced-motion (no video autoplay, no slides, use fades only).
  - Accessibility kept: keyboard path to everything, visible focus rings, status as text + colour, confirm dialogs where destructive.

Design references are in design/ui-glorious/ (from Step 0). Match the mockups closely, except where this prompt says otherwise. Notes on the mockups:
  - home-mockup.png has a painted left rail and status pill. Ignore those painted parts; the real ones are HTML.
  - liveworld-mockup.png has a painted Mysuru Palace building and a bottom stats strip. Both are REMOVED. Its sidebar shows Workers and Events; the real sidebar has only Home, Live World, Analytics, Pipelines, Settings.
  - design-pack.md is GPT's handoff. Where it differs from this prompt, this prompt wins.

==================================================
2. THE DEMO PATH (build this, in this order of importance)
==================================================
  1. Home: the looping highway video with the glass pill.
  2. Click Live World: the map slides up like an Android app drawer.
  3. Hover a truck: a glass info card with real data.
  4. Analytics → Truck Speeds → click a truck: its speed history graph.
  5. Analytics → Failed Records: late and rejected messages with reasons.
  6. Pipelines → Ask AI → review the generated pipeline → Open Editor (new tab) → Run → see results.
  7. Settings → Logs & Recovery → Vivek types `worker kill 2` in a terminal → the site shows the dead worker, the checkpoint restore, partition reassignment, the survivors taking over, the worker rejoining, and the failure staying in history.

==================================================
3. SCREENS AND TRACKS
==================================================
HOME (boss/shell, small)
  - Full-screen <video> background from home-loop.mp4: muted, autoplay, loop, playsinline, poster = first frame. If the loop has a visible jump, make a short crossfade loop with ffmpeg on utkarsh-pc. Compress to a small web-friendly mp4 (and webm if easy).
  - The video is 720p. Accept it and use object-fit: cover.
  - Reduced motion: show the poster image only.
  - The sidebar rail and the status pill are real HTML on top. Nothing is painted in.

TRACK A: LIVE WORLD MAP (vimlesh-pc)
  - MapLibre GL with an OFFLINE map: a Mysuru-only PMTiles extract (Protomaps basemap, roughly lon 76.58-76.72, lat 12.24-12.38; verify the box covers the city), stored in the repo or in web/public and served locally. No internet needed at runtime.
  - Style it to look like Apple Maps (match liveworld-mockup.png): warm pastel land, soft green parks, blue water, warm yellow main roads, white minor roads, clean labels. No blue UI tint.
  - Do NOT draw the Mysuru Palace building or any decorative castle art. Plain text labels for landmarks are fine.
  - Six predefined routes on real Mysuru roads. Compute them ONCE at build time on a dev machine (for example from OSM data or a public routing service) and commit them as static GeoJSON. No runtime routing.
  - Set the simulator to 6 trucks through config only. Map simulator truck i to route i. Move each truck marker along its route using the simulator's REAL speedKmh and status: it stops when the truck is stopped, and shows breakdown if that status exists. Speed, status, routeId, deliveries, partition and worker all come from real data. Only the geographic path is a display mapping (say so in a code comment). Keep markers from overlapping visually where routes cross (small offset).
  - Truck marker: ONE 3/4-angle truck image with a transparent background, flipped horizontally when the truck heads left. No 3D, no rotation. First try to cut a clean truck from liveworld-mockup.png with automatic background removal on utkarsh-pc. If the result is poor, ask Vivek for a transparent PNG (one short message). A soft coloured ground glow under each truck shows status (green = moving, amber = slow or alert, grey = stopped).
  - Hover (and keyboard focus, and tap) shows a glass card: truck photo (one image for all trucks, e.g. cropped from the mockup's card), Truck id, status chip, speed km/h, route (from → to place names), "Partition N · Worker Wn" from real engine assignment.
  - Floating controls: search (searches only the 6 trucks and about 10 landmarks, with coordinates taken from the OSM data, not invented), recenter, zoom in, zoom out.
  - NO bottom statistics strip.
  - If the simulator already supports breakdown (recon d): right-click a truck (long-press on touch) opens a glass menu with a "Simulate breakdown" toggle that changes REAL simulator state. If it does not exist, skip this entirely.

TRACK B: ANALYTICS (second worker on utkarsh-pc)
  - Sidebar → Analytics shows exactly five options: Truck Speeds, Deliveries, Alerts, Events Processed, Failed Records.
  - Each option opens a GlassWindow to the right of the sidebar. Several windows can be open at once. Clicking an already-open option brings its window forward. The map stays visible underneath.
  - REUSE the old panels' data code (recon e). Only rebuild the look.
  - Polished:
      Truck Speeds: one row per truck (current speed, 1-minute average, reading count). Clicking a row shows that truck's recent speed history as a line chart in the same window, with a back arrow. This window also serves as the keyboard-accessible truck list.
      Failed Records: rejected and late messages, reason, time, and the technical details that exist.
  - Simple (table plus one small trend line): Deliveries (per truck plus trend), Alerts (truck, message, time), Events Processed (real processing rate plus any breakdown that exists).
  - Use one chart library across all windows, quiet styling, readable on glass.
  - Empty states must be honest ("No failed records yet").

TRACK C: LOGS & RECOVERY + TERMINAL COMMANDS (utkarsh-pc, needs the full stack)
  - Sidebar → Settings shows only "Logs & Recovery". Add "Truck breakdown" only if it really exists (recon d). Add start, pause or reset only if they already exist. No dark mode, no map preferences, no performance toggles.
  - Logs & Recovery view (a large glass window or a full panel):
      All 3 workers in real time: name, running/dead/recovering, processing rate, assigned Kafka partitions (P0-P5), useful queue or lag info.
      Chronological engine activity and failure log.
      Completed checkpoint history, and which checkpoint was used for each recovery.
      After a kill: the dead worker is clearly marked, partition reassignment is shown, the survivors take over, and the worker is shown rejoining if it restarts. The failure stays in history after recovery.
      Be honest: a short recovery pause is shown as a pause, not hidden.
  - FIX THE WORKER-CARD BUG FIRST. Reproduce it with a real kill on utkarsh-pc, find the cause, fix it, and prove it with a screenshot sequence (before kill, dead, recovering, recovered).
  - Terminal commands (thin wrappers around EXISTING functionality only):
      `worker kill <n>` → does exactly what the old Kill worker button did.
      `events late` (or a similar short name) → does what the old "Send late events" did, so Vivek can fill Failed Records during the demo.
    These must work on Windows (vivek-pc runs the demo): provide a .cmd or PowerShell version, plus a bash version. Print a clear one-line result.
  - Add the commands to docs/expo/CHEATSHEET.md as a new "new UI" section. Do not delete the classic instructions.

TRACK D: PIPELINES (upendra-pc)
  - Sidebar → Pipelines opens a glass panel next to the sidebar with:
      Ask AI: a large prompt box, a ClayButton "Generate", a calm thinking animation (10-30 s is expected), and the generated pipeline shown as a readable node list or mini graph for review, with buttons "Run" and "Open in editor".
      Open Editor: a clearly visible button with an external-window icon. It opens /pipelines/editor in a new browser tab.
  - Pipeline JSON: nodes and edges. Exactly five node types:
      Filter {field, op, value}
      Pick Field {fields[]}
      Window Average {field, windowSec, groupBy?}
      Threshold Alert {field, op, value, message}
      Output {name}
    Fields are only the real fields available on truck events. Write a JSON schema and validate every pipeline against it (from AI and from the editor).
  - Execution: in the API layer, next to the engine, reading the same stream. Do NOT modify the engine or simulator. Results stream to the UI and appear in a GlassWindow ("Pipeline results"), with honest errors.
  - Manual editor: reuse web/src/app/editor if it has a usable canvas (recon b). Otherwise use React Flow (@xyflow/react). Drag nodes from a palette of the five types, connect them, edit params in a side panel, Validate (show clear errors on the nodes), Run.
  - Ask AI backend: POST endpoint → spawn headless Claude Code (`claude -p`) using the existing logged-in subscription on the machine. No API key.
      Pass the user's text through stdin or an argument array, NEVER through shell string interpolation.
      Give a system prompt with the schema and the available fields. Ask for JSON only. Disable tools and file access. Set a timeout of about 60 s. Allow one request at a time.
      Model and config dir are configurable through env vars (AI_CMD, AI_MODEL, CLAUDE_CONFIG_DIR). Default to a fast model; test latency. Prefer the vachana-claude config dir on vivek-pc so Vivek's main Opus is not used.
      Validate the output. On invalid JSON, retry once, then fall back to the closest saved example and say so honestly in the UI ("Showing a saved example: AI was unavailable").
  - Auth check: the boss runs ONE small `claude -p` test on vivek-pc to confirm headless mode works with the subscription login, and reports which account or config dir works.
  - Make 3 rehearsal prompts and save their validated outputs as fallback examples:
      "Alert me when any truck goes over 60 km/h"
      "Average speed per truck over the last 30 seconds"
      "Show only trucks on partition 2 with their speed"
    Adjust the wording to the real fields.
  - 06:00 IST CHECKPOINT (mandatory report to Vivek):
      Ask AI produces valid, runnable pipelines → keep everything.
      Otherwise → ship the manual editor only.
      Editor not working either → hide the Pipelines sidebar item completely.

==================================================
4. TIMELINE (IST, 20 Nov)
==================================================
  ~01:00        Step 0 report (swarm status + assets found).
  ~01:00-01:45  Recon report + shell. Post the plan.
  01:45         Tracks A-D start in parallel.
  03:30         Progress update: one screenshot per track + preview link.
  06:00         Pipelines checkpoint (above) + progress screenshots.
  07:00         Feature freeze. Merge all tracks into ui-glorious. Run the full test suite (classic tests + engine/correctness tests) + new smoke tests.
  07:00-08:30   Fix bugs only. Do a full run-through of the demo path (section 2) on the preview, with a screenshot of every step (a short screen recording if cheap).
  08:30         Final report: preview link, screenshots of all 7 demo steps, test results, known issues, exact steps to merge and run on vivek-pc, and the terminal command list.
  09:00         STOP.

Stretch (only if everything above is green before 07:00): make the new UI usable on Android phones (sidebar becomes a bottom bar, windows become full-width sheets, the map works with touch). Desktop is always the priority. Do not start this if it risks the demo path.

==================================================
5. NEW SMOKE TESTS (Playwright, new UI)
==================================================
  1. Home loads → click Live World → map is visible with 6 truck markers.
  2. Open Analytics → Truck Speeds → click a row → the chart appears in the same window.
  3. Open Logs & Recovery → it lists 3 workers with partitions.
Keep them fast and stable. They must not need the AI.

==================================================
6. EXPLICITLY OUT OF SCOPE
==================================================
Dark mode, theme switch, map preferences, performance toggle, truck zoom scenes, 3D trucks, truck rotation, bottom stats strip, separate Simulation/Chaos/Results Comparison/Tour/Checkpoint pages, extra Analytics categories, extra animations, palace or castle art. Do not delete backend features just because their UI entry is gone.

==================================================
7. DEFINITION OF DONE
==================================================
  - All 7 demo-path steps work end-to-end on the preview with real data.
  - A terminal `worker kill 2` is clearly visible in Logs & Recovery, recovery details are accurate, and the history persists.
  - The old UI works at ?ui=classic, and all existing tests pass.
  - Nothing visible is fake. Nothing is committed to main.
