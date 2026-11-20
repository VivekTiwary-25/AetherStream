# Glorious UI guide

`GloriousApp.tsx` assembles the app. Feature folders own their screens and data;
the integration owner maintains `shell/`, `home/`, `GloriousApp.tsx`, and routing.

## Folder map

- `shell/`: shared windows, sidebar, status pill, router, icons, surfaces, tokens, and motion.
- `home/`: landing screen and reduced-motion video/poster behavior.
- `map/`: Live World map, route/landmark data, fleet display, and breakdown action.
- `analytics/`: speed, delivery, alert, throughput, and failed-record views and charts.
- `recovery/`: worker/recovery history and truck-breakdown controls.
- `pipelines/`: pipeline prompt/review, visual editor, validation, run results, and mode switch.

## Shell API

`useWindows()` in `shell/windows.ts` exposes `open(id, options)`, `close(id)`,
`isOpen(id)`, and `update(id, partialOptions)`. `WindowManager` is mounted by
`GloriousApp`; IDs should be stable and feature-prefixed. Reopening an ID brings
its existing window forward. `update` changes title, back callback, or content
without resetting geometry. Do not open or update windows during render.

`shell/sections.ts` defines `SidebarItem` as `{ id, label, icon?, onSelect }`.
`analytics/menu.ts` contributes Truck Speeds, Deliveries, Alerts, Events
Processed, and Failed Records. `recovery/menu.ts` contributes Logs & Recovery
and Truck breakdown. The sidebar's main sections are Home, Live World,
Analytics, Pipelines, and Settings; Analytics and Settings open their own
submenus. The window IDs use `analytics.<id>` and `settings.<id>`.

`useEngineStatus()` in `shell/status.ts` reads dashboard state and reports a
label and events-per-second rate; a disconnected socket is unavailable, not
zero. The `DashboardProvider` in `app/state/store.tsx` consumes `/ws` frames
and hydrates from REST. `shell/router.ts` handles `/`, `/live`, `/pipelines`,
and `/pipelines/editor` with browser history. `GloriousApp.tsx` lazy-loads
LiveWorld, PipelinesPanel, and EditorPage; the editor route has no sidebar.
Opening an Analytics or Settings window navigates to Live World without
reloading or closing existing windows. `?ui=classic` selects the old app.

## Feature data and assets

- Map: `map/useLiveFleet.ts` consumes `snapshot` and `trucks` frames from
  `api/ws.ts` (`api/types.ts` simulator types) and polls `/api/simulator/state`.
  `map/LiveWorld.tsx` posts `/api/simulator/scenario` for breakdowns. It loads
  `/glorious/map/style.json`, `routes.geojson`, and `landmarks.json`; the style
  references local `mysuru.pmtiles` and `fonts/` glyph files. The MapLibre
  worker is bundled with `maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url`.
- Analytics: `analytics/AnalyticsPanel.tsx` reads shared dashboard state and
  `/ws` `snapshot`, `trucks`, `flow`, and result frames via `api/types.ts`.
  Dashboard hydration in `app/state/store.tsx` uses `/api/results/windows`,
  `/api/results/alerts`, `/api/results/dead-letters`, `/api/results/summary`,
  and `/api/workers`; `analytics/data.ts` groups windows and dead letters.
- Recovery: `recovery/useRecoveryFeed.ts` reads `/ws` `recovery`, `snapshot`,
  and `execution` frames, polls `/api/recovery/history`, and uses
  `getCurrentExecution()` and `getCheckpoints()` from `api/client.ts`.
  `recovery/TruckBreakdown.tsx` uses simulator state and the scenario endpoint.
- Pipelines: `pipelines/api.ts` calls
  `/api/glorious/pipelines/fields`, `/api/glorious/pipelines/validate`,
  `/api/glorious/pipelines/runs`, and `/api/glorious/pipelines/runs/:id/results`
  and `/stop`, plus `/api/glorious/ai/generate`. `useRunPipeline.tsx` opens
  results windows; `ResultsView.tsx` retrieves pages by sequence cursor.
- Home: `home/Home.tsx` uses `/glorious/home-loop.mp4` and
  `/glorious/home-poster.jpg`; reduced motion displays the poster instead.

Live fleet polling cannot overwrite newer socket telemetry or reconnect control
state. Analytics replaces snapshot truck lists, expires current-speed readings
after 4.5 seconds, and deduplicates persisted windows and failed records.
Recovery keeps confirmed deaths visible until a newer heartbeat arrives; missing
partition data is unavailable, not an empty assignment.
The pipeline editor preserves drafts in tab-local session storage across refresh
and Back. Edits invalidate in-flight validation; results polling errors display
Disconnected until a successful update, and reopening results selects the new run.

Fleet, recovery history/execution/checkpoint, and pipeline-results requests never
overlap within a poller. Failed polls wait 5, 10, then at most 15 seconds, emit one
warning per endpoint outage, and reset after a successful response; normal cadences
remain 3, 5, and 1 seconds respectively. Checkpoint events do not bypass outage
backoff. The shared WebSocket retains its existing reconnect backoff (10-second
cap); browser-generated network errors remain visible.

Vite serves files in `web/public/` from the site root. Keep required source
assets out of paths matched by the root `.gitignore` `logs/` pattern: it is
unanchored and silently ignores matching directories anywhere in the tree.

## Modes, responsive behavior, and checks

Set `PIPELINES_MODE` in `pipelines/mode.ts` to `'full'`, `'editor'`, or
`'hidden'` (default `'full'`). Full mode offers AI generation and the editor;
editor mode offers only the manual editor path; hidden mode removes Pipelines
from the sidebar and routes hidden pipeline paths home.

At `max-width: 768px`, navigation becomes a safe-area-aware bottom bar with
Analytics/Settings option sheets. Windows become non-draggable, full-width
sheets; only the active sheet is interactive. Map search collapses to a touch
toggle, truck cards anchor above the bar, analytics rows/charts stack, and
recovery tables scroll within their panels. The editor intentionally has no
bottom bar; its palette scrolls horizontally above the canvas and inspector.
Desktop styles and window geometry remain unchanged above this breakpoint.

From `web/`, run `npm run dev` to develop, `npm run lint` for ESLint,
`npm test` for Vitest, and `npm run build` for TypeScript plus Vite production
build. `npm run preview` serves the built app.
