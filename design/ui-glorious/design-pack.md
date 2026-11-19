AetherStream — Final Design & Implementation Handoff

Repository: "VivekTiwary-25/AetherStream"
Goal: Transform the existing working project into a premium, interactive, glassmorphic logistics dashboard.

Important: Preserve the actual AetherStream engine. This is a UI redesign plus selected new functionality, not a rewrite of the backend.

---

1. Overall Design Language 🔒

- [ ] Premium, clean, neutral glassmorphism.
- [ ] Frosted translucent panels, gentle shadows, rounded corners.
- [ ] Text and charts must remain highly readable.
- [ ] Avoid excessive blue tint or childish-looking elements.
- [ ] Maintain a consistent visual language throughout.
- [ ] Use subtle, satisfying animations.

2. Homepage 🔒

- [ ] Use our approved cinematic highway design.
- [ ] White/cream Indian delivery truck viewed from behind.
- [ ] Road leading toward Mysuru, lush greenery and natural light.
- [ ] Minimal left navigation rail.
- [ ] Neutral frosted status pill in the top-right.
- [ ] Opening Live World slides the map upward, like an Android app drawer.

3. Live World Map 🔒

- [ ] Use real Mysuru map geography and roads.
- [ ] Apple Maps-inspired visual treatment.
- [ ] Six trucks in the approved demo design.
- [ ] Trucks appear as detailed miniature 3D Indian trucks, not map-pin icons.
- [ ] Trucks follow predefined routes with believable movement.
- [ ] Hovering shows a glass information card with a realistic Indian truck image and relevant details.
- [ ] Keep floating search, GPS, zoom and map controls.
- [ ] Remove the bottom floating statistics strip entirely.
- [ ] No decorative castle element or unrelated landmarks.
- [ ] Connect truck positions to actual simulator data.

Implementation note: The current repository uses a fictional 2 km × 2 km grid and defaults to eight trucks. Claude must reconcile that with the six-truck Mysuru design rather than pretending the existing coordinates are real Mysuru roads.

4. Left Sidebar 🔒

- [ ] Single glassmorphic sidebar, anchored on the left.
- [ ] Main destinations: Home, Live World, Analytics, Pipelines, Settings.
- [ ] Clicking a destination such as Analytics transforms and expands the existing sidebar.
- [ ] Other navigation options disappear during expansion.
- [ ] Back button restores normal navigation.
- [ ] Do not create a second sidebar alongside it.

5. Analytics 🔒

Clicking Analytics reveals these five options directly:

- [ ] Truck Speeds
- [ ] Deliveries
- [ ] Alerts
- [ ] Events Processed
- [ ] Failed Records

Floating window behaviour

- [ ] Clicking an option opens a separate glass window to the right of the sidebar.
- [ ] Windows are draggable, resizable and independently closable.
- [ ] Multiple windows may remain open simultaneously.
- [ ] Clicking an already-open option brings its window forward.
- [ ] Map remains visible underneath.
- [ ] Clicking a data row changes the contents of that same window, without changing its size or position.
- [ ] Back arrow returns to the overview.

Data presentation

- [ ] Truck Speeds: one row per truck, showing current speed, one-minute average and available reading counts.
- [ ] Selecting a truck shows its recent speed history as a graph.
- [ ] Deliveries: per-truck counts and time-based trends.
- [ ] Alerts: affected truck, alert message and timestamp.
- [ ] Events Processed: actual event-processing rate and available breakdowns.
- [ ] Failed Records: rejected and late messages, reasons and available technical details.
- [ ] Choose suitable graphs or tables for each dataset.
- [ ] Use real backend values. No fabricated dashboard statistics.

6. Pipelines 🔒

Entry point: Pipelines in the left sidebar.

Option A — Ask AI

- [ ] Large natural-language prompt box.
- [ ] User describes a calculation, condition or automation.
- [ ] Claude generates a pipeline using available data and supported operations.
- [ ] Show generated pipeline for review before execution.
- [ ] Allow opening the generated pipeline in the manual editor.

Option B — Manual Editor

- [ ] Clearly visible Open Editor button with external-window icon.
- [ ] Opens a separate browser tab.
- [ ] Full-size visual drag-and-drop workflow canvas.
- [ ] Simple, understandable nodes and connections.
- [ ] Validate and execute pipelines.

Delegated to Claude

- [ ] Decide precise node types and editor architecture.
- [ ] Design validation, execution feedback and custom-results handling.
- [ ] Explore Claude Opus 5.5 integration; verify access and authentication.
- [ ] Keep AI-generated workflows compatible with the actual engine.
- [ ] Treat external AI actions, calls and other integrations as new functionality requiring implementation, not existing capabilities.

7. Settings 🔒

Settings expands within the existing left sidebar.

General

- [ ] Light / Dark / System theme.

Map Preferences

- [ ] Truck labels and map animation preferences.

Performance

- [ ] Reduced animations and graphical effects.

Simulation Controls

- [ ] Start, pause, stop and reset simulation.
- [ ] Change simulation time speed.
- [ ] Simulate breakdown of one or multiple selected trucks.
- [ ] Restore selected trucks.

Live World shortcut

- [ ] Right-click a miniature truck to open a glass context menu.
- [ ] Include a satisfying Simulate Breakdown toggle.
- [ ] The toggle must change real simulator state, not only its appearance.
- [ ] Provide an equivalent touch interaction on mobile.

Important: Truck breakdown simulation and worker-process failure are separate concepts.

8. Settings → Logs & Recovery 🔒

This is our engine resilience demonstration.

- [ ] Show all three processing workers in real time.
- [ ] Show each worker's actual assigned Kafka partitions.
- [ ] Show running/dead/recovering state and processing rate.
- [ ] Show useful queue or workload information.
- [ ] Show chronological engine activity and failure logs.
- [ ] Show completed checkpoint history.
- [ ] Show which checkpoint was used during recovery.

Expo demonstration

- [ ] User kills a real worker from the terminal.
- [ ] Implement a simple shortcut command, ideally similar to "worker kill 2".
- [ ] Website visibly identifies the failed worker.
- [ ] Show checkpoint restoration and partition reassignment.
- [ ] Show surviving workers taking over.
- [ ] If the worker restarts automatically, show it rejoining.
- [ ] Preserve the failure history even after recovery.
- [ ] Do not falsely claim uninterrupted processing: a short recovery pause is acceptable.

Known issue to fix: Existing rehearsal reported that worker cards did not reliably reflect a killed worker, even when the engine entered recovery.

9. Optional Truck Zoom Experience

Claude decides feasibility.

- [ ] Ideal: clicking a truck zooms into a detailed animated street scene at its location.
- [ ] Use a few predefined, reusable animated areas rather than a fully rendered 3D city.
- [ ] If implementation is too expensive or unreliable, skip this feature.
- [ ] Keep the normal map and hover cards working regardless.

10. Animations & Responsive Design

Claude handles implementation.

- [ ] Home-to-map slide-up transition.
- [ ] Smooth sidebar transformations.
- [ ] Floating-window opening, dragging and resizing.
- [ ] Natural truck movement and turning.
- [ ] Subtle hover and status animations.
- [ ] Responsive layouts for mobile and tablet.
- [ ] Touch equivalents and reduced-motion accessibility.

11. Explicitly Removed Features ❌

- [ ] No bottom floating statistics strip.
- [ ] No separate Simulation page.
- [ ] No separate Chaos page.
- [ ] No Results Comparison UI.
- [ ] No Guided Demo / Tour.
- [ ] No standalone Checkpoint History page.
- [ ] No unnecessary Analytics subcategories such as “Fleet Activity.”
- [ ] No worker diagnostics cluttering ordinary Analytics.
- [ ] No elaborate separate Truck Detail View unless the optional zoom experience is feasible.

Keep existing backend functionality where useful; removing a UI entry does not mean deleting engine capabilities.

---

12. Implementation Priorities

Priority 1 — Essential

- Homepage and Live World.
- Accurate map and miniature trucks.
- Transforming sidebar.
- Analytics and floating windows.
- Settings and simulation controls.
- Real worker-failure/recovery Logs.

Priority 2 — Major feature

- AI-assisted Pipelines.
- Manual workflow editor.
- Pipeline execution and feedback.

Priority 3 — Polish / Optional

- Mobile responsiveness.
- Advanced animations.
- Detailed truck zoom scenes.

13. Final Acceptance Criteria

- [ ] All primary screens follow the approved designs.
- [ ] Every visible control has a real purpose.
- [ ] All analytics use actual calculated or streamed data.
- [ ] Map and worker information reflect backend state.
- [ ] Terminal-triggered worker failure is genuinely visible in Logs.
- [ ] Recovery information is accurate and persists in history.
- [ ] Existing backend functionality and tests remain intact.
- [ ] No unnecessary features are added without approval.

Design principle: Make the interface immediately understandable to a nontechnical visitor while still allowing us to demonstrate the distributed-stream-processing engine underneath it.