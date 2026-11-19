# Offline asset check

Static review of the Glorious UI references in `web/src/glorious/**`, `web/index.html`, and `web/public/glorious/map/style.json`.

| Asset / runtime request | Local path / source | Result |
|---|---|---|
| UI entry | `/src/main.tsx` (web source bundle) | OK — same-origin |
| Inter font weights 400/500/600/700 | `@fontsource/inter/*.css` and package font files bundled at build | OK — local package assets; build output required |
| Home poster/background | `/glorious/home-poster.jpg` | OK — `web/public/glorious/home-poster.jpg` |
| Home loop video | `/glorious/home-loop.mp4` | OK — `web/public/glorious/home-loop.mp4` |
| Map style | `/glorious/map/style.json` | OK — `web/public/glorious/map/style.json` |
| Map vector tiles | `pmtiles:///glorious/map/mysuru.pmtiles` via bundled `pmtiles` protocol | OK — `web/public/glorious/map/mysuru.pmtiles` |
| Map glyphs | `/glorious/map/fonts/{fontstack}/{range}.pbf` | OK — local Noto Sans Regular PBF ranges under `web/public/glorious/map/fonts/` |
| Map sprite | None declared in style | OK — not requested |
| MapLibre worker | `maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url` via Vite worker URL import | OK — dependency bundled as local worker asset |
| Routes and landmarks | `/glorious/map/routes.geojson`; `/glorious/map/landmarks.json` | OK — both under `web/public/glorious/map/` |
| Truck images | `/glorious/truck.png`; `/glorious/truck-photo.jpg` | OK — both under `web/public/glorious/` |
| Font/style imports and CSS URLs | local `@fontsource` imports; `/glorious/home-poster.jpg` | OK — no external CSS import or URL found |
| Live socket | `ws(s)://<current-host>/ws` | OK — same-origin host, protocol follows page scheme |
| Simulator and recovery API | `/api/simulator/state`, `/api/simulator/scenario`, `/api/recovery/history` | OK — same-origin paths |
| Dashboard API | `/api/results/summary` and shared dashboard REST requests | OK — same-origin paths |
| Pipeline / AI API | `/api/glorious/pipelines/{fields,validate,runs,...}`; `/api/glorious/ai/generate` | OK — same-origin paths |
| Attribution destinations | `https://protomaps.com`, OpenStreetMap copyright, ESA WorldCover | EXTERNAL links only in map attribution text; not map/data fetch URLs |

## Verdict
No external runtime asset fetch URL was found. Map data, glyphs, media, fonts, and worker are local/bundled; API and WebSocket traffic is same-origin. Attribution contains external hyperlinks only. Offline operation still requires the same-origin application server/API and deployment of the built UI assets.