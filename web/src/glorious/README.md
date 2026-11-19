# Glorious shell API

Each track edits only its own folder: `map/`, `analytics/`, `recovery/`, or `pipelines/`.
The integration owner maintains `shell/`, `home/`, `GloriousApp.tsx`, and routing.

## Windows

```tsx
import { useWindows } from '../shell/windows'
const windows = useWindows()
windows.open('analytics.speeds', {
  title: 'Truck Speeds', strong: true,
  render: () => <Speeds />,
  initial: { x: 260, y: 110, w: 560, h: 420 }, // optional
})
windows.close('analytics.speeds')
windows.isOpen('analytics.speeds')
```

`WindowManager` is already mounted. IDs must be stable and track-prefixed.
`open` on an existing ID brings it forward without replacing its content.
`update(id, { title, onBack, render })` changes header/content without resetting geometry.
For drill-down, keep selection in the rendered component and use `update` for the
header/back callback; pass `onBack: undefined` when returning to the list.
Do not call `open`/`update` during render. Frames support drag, resize, Escape,
focus-to-front, arrow-key movement and Shift+arrow-key resizing on the title bar.
Escape closes only the frontmost window. Closing restores the opener's focus.
`GlassWindow`, `GlassSurface` and `ClayButton` are named exports in `shell/`.
Use `strong` surfaces for dense text. Tokens are `--gl-*` in `shell/tokens.css`.

## Sidebar

Analytics owns `analytics/menu.ts` (`analyticsMenu`); Logs & Recovery owns `recovery/menu.ts`
(`settingsMenu`). Both export `SidebarItem[]` from `shell/sections.ts`:
`{ id, label, icon?: ReactNode, onSelect: (windows: WindowsApi) => void }`.
Use window IDs `analytics.<item.id>` or `settings.<item.id>` for active styling.
No central registry edits or second sidebar needed.

## Status and routing

`useEngineStatus()` from `shell/status` returns `{ label, eventsPerSec }`.
It consumes the existing `useDashboard()` under `DashboardProvider`: `/ws`
snapshot/execution/workers/flow frames and REST `/api/results/summary` hydration.
Disconnected transport means `Disconnected` and an unavailable rate, not zero.
StatusPill is mounted on every screen, including the standalone editor.

Routes: `/` Home, `/live` Live World, `/pipelines` panel, `/pipelines/editor`
standalone new-tab editor (no sidebar). Tracks replace the named placeholder
exports without editing the router. Vite dev/preview and production nginx
already support SPA fallback. `?ui=classic` selects the untouched old `<App />`.
Home video and fonts are local. Reduced motion shows the poster and fades only.

## Classic Playwright limitation

The existing tests call `page.goto('/')`. URL resolution drops a baseURL query,
so `baseURL: 'http://localhost:8088/?ui=classic'` cannot select classic mode.
Playwright config has no context init-script option. Preserving those tests
requires a shared fixture/setup imported by the specs, outside this task's scope.
