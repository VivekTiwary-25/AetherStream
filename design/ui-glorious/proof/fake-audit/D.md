# D — Audit: app shell, sidebar, settings entry points, home

## Visible values: item -> source -> status

| Item | Source | Verdict |
|---|---|---|
| Brand “AetherStream” and hidden home heading | Static product branding in `Sidebar.tsx` / `Home.tsx`; not presented as telemetry | OK |
| Sidebar destinations Home, Live World, Analytics, Pipelines, Settings | `Sidebar.tsx` destinations; `router.ts` maps screens to URLs; Analytics and Settings open their respective real submenus; Pipelines visibility follows `PIPELINES_MODE` | OK |
| Analytics menu: Truck Speeds, Deliveries, Alerts, Events Processed, Failed Records | `analytics/menu.ts` maps each `AnalyticsId` to a rendered `AnalyticsPanel`; data comes from dashboard REST hydration and WebSocket frames | OK |
| Settings menu: Logs & Recovery, Truck Breakdown | `recovery/menu.ts` opens `LogsRecovery` and `TruckBreakdown`; these consume worker/recovery/checkpoint and simulator API/WS data | OK |
| Analytics/Settings navigation to Live World | `GloriousApp.tsx` `onWindowOpen` routes prefixed analytics/settings windows to `live`; `navigation.test.tsx` covers sidebar and programmatic window paths | OK |
| Glass window title, Back, Close, move/resize controls | Title and optional Back callback are passed by each real window caller; Close invokes `WindowManager.close`; drag, resize, and keyboard geometry update local frame state | OK |
| Loading… fallback | `GloriousApp.tsx` renders it only while lazy-loaded feature modules resolve; not a claimed data value | OK |
| Engine connection badge | `StatusPill.tsx` reads `useEngineStatus()`; `shell/status.ts` derives it from DashboardProvider socket/execution/worker/recovery state | OK |
| Events-per-second rate | `flow.eventsPerSec` or REST `summary.eventsPerSec`, accepted only if finite and nonnegative; unavailable states render an em dash, never a fabricated zero | OK; regression added in `shell/navigation.test.tsx` |
| Home image/video and alt description | Real `/glorious/home-poster.jpg` and `/glorious/home-loop.mp4` public assets; video is muted/looping and reduced-motion preference selects poster | OK |
| Recovery partition grid P0–P5 | `LogsRecovery.tsx` constant `[0..5]`, documented there as deployed topic `--partitions 6`; live owners and any additional IDs come from worker/history data | QUESTION: fixed deployed partition count can drift if topic configuration changes |
| Version, SHA, and numeric shell/sidebar badges | None displayed in the audited components | OK |

## Verification and limits

- Added a navigation regression asserting a disconnected engine reports an unavailable event rate and never renders `0 ev/s`.
- No fake or dead shell/sidebar/home UI found; no product changes needed beyond the regression test.
- Settings partition count remains a deployment-coupled constant and is the only source-drift question found.

- Requested `NODE_OPTIONS=--no-experimental-webstorage npx vitest run src/glorious` could not start: npx's Vitest/Vite installation failed loading optional `@rolldown/binding-wasm32-wasi`. No tests executed; `web/` has no local `node_modules` installation.
