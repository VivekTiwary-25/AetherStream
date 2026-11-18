# T10: The game-like truck city (PixiJS 8) with glowing event stream

Lane 5 (web/city). Build the centrepiece of the UI: a polished, game-like top-down city in the browser where trucks drive
between a depot and delivery stops, and every event they emit is visible as a glowing particle flowing into the engine.
Think "premium browser delivery game", dark and atmospheric, smooth 60 fps.

Files (create/edit only these):
- web/src/city/** (replace CityPlaceholder.tsx usage by exporting a `CityView` component; keep CityPlaceholder.tsx file or delete it)
- Do NOT create or edit web/src/api/** (another task is writing the shared API client at the same time). Put the few types
  you need (mirroring contract 04 field names exactly) in web/src/city/cityTypes.ts and a small self-contained reconnecting
  WebSocket subscription in web/src/city/citySocket.ts (reconnect with backoff 1 s -> 10 s). A later task may switch it to the
  shared client.
- web/package.json + web/package-lock.json: only to add `pixi.js` ^8 (and `@pixi/react` only if you really need it - plain
  pixi in a useEffect is preferred)
- web/src/app/shell/AppShell.tsx: only the one line that renders the city (swap CityPlaceholder for CityView)
- web/src/city/__tests__/** (Vitest tests for the pure logic)

What to build:
1. Data: subscribe to the WebSocket `/ws` (contract 04): `snapshot` (simulator state with stops + depot + trucks), `trucks`
   every 250 ms, `flow` every 1 s, `workers`, `execution`. Interpolate truck positions smoothly between updates (lerp + heading
   slerp) so motion is fluid at 60 fps even though updates are 4/s. World is 2000x2000 m; fit it to the viewport with padding.
2. Visuals (PixiJS 8, WebGL):
   - Dark navy ground, a subtle street grid every 200 m with softly glowing street lines, blocks as slightly raised dark tiles
     with faint rim light (2.5D feel via a gentle drop shadow / top highlight), a few decorative elements (parks, tiny
     building tops) generated deterministically - restrained, never cluttered.
   - Depot: a distinct hub with a soft pulsing ring. Stops: small pads; a stop flashes briefly when a delivery happens.
   - Trucks: small rounded truck sprites drawn with Graphics (cab + box), rotated to heading, colour by status (DRIVING
     cyan-ish, UNLOADING amber, STOPPED red with a slow pulse ring), a soft headlight glow, a label `07` on hover.
   - The engine: an "Engine core" structure drawn at one edge of the map (e.g. right side) with N partition lanes (6) lit by
     activity. Each emitted event = a small glowing particle that travels on a smooth bezier curve from its truck to the
     engine lane of its partition (approximate partition = hash(truckId) % 6 is fine visually), particle count scaled from
     `flow.eventsPerSec` (cap ~300 live particles, pooled; no allocations per frame). Rejected events (flow.rejectedPerSec)
     = small red particles; late = violet.
   - When execution state is RECOVERING, the engine core dims and shows a scanning effect; trucks keep driving.
   - Worker health ring segments on the engine core: green healthy, amber backpressured, red dead (from `workers`).
3. Interaction: drag-and-drop a new truck from a small glass "Add truck" chip (bottom-left) onto the map ->
   `POST /api/simulator/trucks {x, y}` in world metres (use fetch; show a quick drop ripple). Hover truck = tooltip
   (truckId, speed km/h, status). Mouse-wheel zoom + drag pan, clamped; double-click resets view.
4. Performance and polish: one Pixi Application, resize-aware, devicePixelRatio aware (cap 2), pause rendering when the tab is
   hidden, `prefers-reduced-motion` reduces particles and pulses. Destroy everything on unmount.
5. Keep pure logic (world->screen transform, interpolation, partition mapping, particle pool) in plain TS modules with
   Vitest tests in web/src/city/__tests__/.

Use `npm run mock` in web/mock (dev mock server, contract-faithful) or the real API for local dev if you can run Node.
The UI must work with the real backend unchanged.

Acceptance check: `ci-web` workflow green on your branch (lint + tests + build). In your result describe the visual
design briefly and list anything you could not check visually.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql. If a contract looks wrong, follow it anyway and say so in your result.
- Do not change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only create/edit the files listed under "Files". If you truly need another file, say so in your result instead of editing it.
- Your PC may not have Java, Node or Docker. The acceptance check runs in GitHub Actions on your task branch (CI runs on push to task/**). Write code carefully so it compiles first time; keep it simple and readable.
- Do not commit secrets, build outputs, node_modules or .gradle/ folders.
- In your result, list what you built, how the acceptance check proves it, and anything left undone.
