# Offline Mysuru map

The checked-in runtime assets are in `web/public/glorious/map/`. They are real Protomaps/OSM data, not a rendered mockup. No internet connection, access token, online style, remote font, sprite, or routing request is needed at runtime. The geographic routes are a display mapping, not simulator GPS; simulator speed/status/assignment are supplied separately by the UI.

## Basemap source and extent

- Latest available daily build listed at <https://maps.protomaps.com/builds/> when retrieved on 2026-11-20: **20261118**, tileset **4.15.2** (compatible with style major versions 4 and 5). The following day's URL returned 404; the build index is authoritative.
- Source: <https://build.protomaps.com/20261118.pmtiles>.
- CLI: [go-pmtiles v1.31.2](https://github.com/protomaps/go-pmtiles/releases/tag/v1.31.2), temporary Windows x86_64 release binary, not committed.
- Requested bbox, west/south/east/north: **76.55,12.23,76.75,12.42**. This deliberately widens the initial city box to include the Ring Road, the northwestern Infosys campus (OSM way 231782270), Chamundi Hill and approach roads. PMTiles extracts retain intersecting whole tiles rather than clipping features at the exact boundary.
- Zooms **0–15**, 520 tiles; extraction transferred about 3.5 MB and produced **3,375,729 bytes** (3.220 MiB), well below 40 MB.
- SHA-256: `3155841e4b0f04722bcddbd6022ba9b334ced77060aeb5e0b330721b54ff8185`.

Exact PowerShell commands, from the repository root (requires curl, tar, and Node 20+ on PATH):

```powershell
$tools = Join-Path $env:TEMP 'mysuru-map-tools'
New-Item -ItemType Directory -Force $tools | Out-Null
curl.exe -fL --retry 2 'https://github.com/protomaps/go-pmtiles/releases/download/v1.31.2/go-pmtiles_1.31.2_Windows_x86_64.zip' -o "$tools/pmtiles.zip"
Expand-Archive -Force "$tools/pmtiles.zip" $tools
& "$tools/pmtiles.exe" extract 'https://build.protomaps.com/20261118.pmtiles' 'web/public/glorious/map/mysuru.pmtiles' --bbox=76.55,12.23,76.75,12.42 --maxzoom=15
curl.exe -fL 'https://registry.npmjs.org/protomaps-themes-base/-/protomaps-themes-base-4.5.0.tgz' -o "$tools/theme.tgz"
tar -xzf "$tools/theme.tgz" -C $tools
node design/ui-glorious/map-build/build-style.mjs "$tools/package/dist/esm/index.js"
node design/ui-glorious/map-build/build-data.mjs
Remove-Item -Recurse -Force $tools
```

For a newer extract, first consult the daily-build index and explicitly substitute its available URL. Older daily builds are eventually removed upstream, so the committed archive and its hash are the durable copy. Extraction requires HTTP range support; serving `mysuru.pmtiles` should also support byte ranges (Vite/static file serving does).

## Style and offline fonts

`build-style.mjs` starts with **protomaps-themes-base@4.5.0 `light`**, then customizes its palette: warm cream earth, soft green parks/woods, blue water, warm yellow major roads, white minor roads and dark grey text. It preserves the upstream road ordering, bridge/tunnel handling, zoom-dependent widths and multilingual label expressions. Every layout and formatted-label font reference is normalized to `Noto Sans Regular`. All 256 upstream BMP glyph ranges are physically local (6,240,473 bytes of PBF data), including Kannada, Latin, Devanagari and punctuation ranges. The pinned upstream asset commit is `028c18f713baecad011301ff7a69acc39bcc2ae7`, with font tree `99b7e57c7f136e62153faefe18049b2f89bb9434`. The downloader checks every downloaded range against the upstream tree's byte size.

All icon properties are removed: POIs are plain text, including Mysore Palace. There is no artwork and no sprite dependency. To regenerate only the JSON without redownloading glyphs:

```powershell
node design/ui-glorious/map-build/build-style.mjs "$tools/package/dist/esm/index.js" --style-only
```

The source URL in the style is exactly `pmtiles:///glorious/map/mysuru.pmtiles`. The only HTTPS strings in its runtime JSON are attribution hyperlinks; these are not asset requests.

## Real-road routes and landmark provenance

`source-data.json` records the complete Nominatim lookup response, exact query URL and fetch time, all six OSRM request URLs, complete OSRM responses, original landmark coordinates, snapped road waypoint coordinates, reported distances and geometries. OSM place IDs were selected from bounded Nominatim searches around Mysuru; the generator then looks up those exact IDs instead of depending on future search ranking. `landmarks.json` is a bare array of ten `{name,lon,lat,source:"osm:type/id"}` objects; shortened English display names are naming-only mappings of those real objects.

`build-data.mjs` without flags deterministically replays the committed provider responses without network access. To intentionally refresh from the public providers:

```powershell
node design/ui-glorious/map-build/build-data.mjs --refresh
```

This uses <https://nominatim.openstreetmap.org/lookup> once for all ten places and <https://router.project-osrm.org/route/v1/driving/> once per route, with full-resolution GeoJSON. OSRM requests are sequential and spaced by 1.1 seconds. Each request contains **A;B;A**, `continue_straight=false`, `overview=full`, `geometries=geojson`: the return leg is routed separately, not a naive reversal through one-way streets. The generator rejects a result unless the returned first and last coordinates are exactly equal; it never inserts a fabricated closing segment. Area-centroid landmark locations are snapped by OSRM to roads; the raw response preserves that distinction.

| routeIndex | From | To (then return to From) | OSRM loop metres | Vertices |
| --- | --- | --- | ---: | ---: |
| 0 | Infosys campus | Manasagangotri Campus | 19,779.2 | 419 |
| 1 | Manasagangotri Campus | Mysore Palace | 11,815.3 | 281 |
| 2 | Mysore Palace | St. Philomena's Church | 5,524.5 | 191 |
| 3 | Devaraja Market | JSS Hospital | 4,268.2 | 181 |
| 4 | Kukkarahalli Lake Park | Karanji Lake | 13,229.8 | 306 |
| 5 | JSS Hospital | Chamundeshwari Temple, Chamundi Hill | 21,494.3 | 744 |

Ten landmarks and exact coordinate provenance are in the generated JSON: Mysore Palace (`osm:way/656323590`), Infosys campus (`osm:way/231782270`), Kukkarahalli Lake Park (`osm:relation/9994370`), Karanji Lake (`osm:relation/2310424`), St. Philomena's Church (`osm:way/28275837`), Mysuru Zoo (`osm:way/28950421`), Manasagangotri Campus (`osm:way/51684020`), Chamundeshwari Temple on Chamundi Hill (`osm:relation/5422792`), JSS Hospital (`osm:way/150869510`), Devaraja Market (`osm:node/4764454021`).

## Runtime URLs

- `/glorious/map/style.json`
- `/glorious/map/mysuru.pmtiles` (registered protocol URL: `pmtiles:///glorious/map/mysuru.pmtiles`)
- `/glorious/map/fonts/{fontstack}/{range}.pbf` (`fontstack` is `Noto Sans Regular`, URL-encoded by MapLibre)
- `/glorious/map/routes.geojson`
- `/glorious/map/landmarks.json`

## Licenses and attribution

- Map and route/landmark data: **© OpenStreetMap contributors**, [ODbL 1.0](https://opendatacommons.org/licenses/odbl/1-0/); <https://www.openstreetmap.org/copyright>. Protomaps PMTiles is an ODbL Produced Work. Retain the style's visible attribution. OSRM is the routing engine, using OSM road data; this is not live traffic or access-permission advice.
- Protomaps style code: **BSD-3-Clause**, copyright 2019–2023 Protomaps LLC, Kelso Cartography. Upstream visual design by Geraldine Sarmiento for Protomaps LLC: **CC0**. Full upstream notice is served at `/glorious/map/LICENSES.txt`; upstream source: <https://github.com/protomaps/basemaps/tree/cd18b22f8ba4e9d18aac1afe7246ebe3c395cb1f>.
- Protomaps glyph assets: **SIL Open Font License 1.1**, included at `/glorious/map/fonts/OFL.txt`; <https://github.com/protomaps/basemaps-assets/tree/028c18f713baecad011301ff7a69acc39bcc2ae7/fonts>.
- Landcover: ESA WorldCover / Daylight landcover, **CC-BY 4.0**; <https://esa-worldcover.org/en/data-access>. Natural Earth low-zoom data is public domain. OSM-derived earth/water polygons are from <https://osmdata.openstreetmap.de/> (ODbL).

## Live telemetry and motion

`LiveWorld.tsx` registers the local PMTiles protocol and maps the first six real simulator trucks, sorted by `truckId`, to `routeIndex` 0–5. `useLiveFleet.ts` shares the existing WebSocket (`snapshot`/`trucks`) and polls `/api/simulator/state` every three seconds because truck frames omit `running` and `timeScale`. Stale telemetry (over 4.5 seconds without either source), a paused simulator, and STOPPED/IDLE/UNLOADING statuses freeze motion; no fleet records or worker assignments are invented.

Distance increment in metres is `speedKmh / 3.6 × realElapsedSeconds × simulator.timeScale`. Compose currently configures a real 10× simulated clock, but the UI reads the live value instead of assuming it; 36 km/h therefore covers 100 display metres per wall-clock second at that scale. Background-tab suspension resets the integration clock rather than extrapolating an unobserved speed/status. Geographic paths, short truck labels and `Wn` worker abbreviations are display mappings; speed, status, routeId, Kafka murmur2 partition and live partition owner remain real.

Breakdown sends `POST /api/simulator/scenario {"name":"breakdown","truckId":"…"}` and waits for actual STOPPED telemetry. It never optimistically changes status and exposes no restore toggle: the simulator resumes automatically. Recreate the simulator service after deployment to apply `SIM_INITIAL_TRUCKS: "6"`; this does not prohibit later deliberate Surge/add-truck scenarios. No truck-count assertion of eight was found in `tests/ui/*.spec.ts` or existing web tests (the eight-step tour is unrelated).

## Verification / deployment limits

- `npm run lint` passed with seven existing Fast Refresh warnings in `app/state/store.tsx`; `npm test` passed **176 tests**, with three existing skips. Nine new route-motion tests cover physical speed integration, clock scale, status stops, loop wrapping, duplicate road points and horizontal flip. A final map-only ESLint check also passed after the MapLibre worker fix.
- Static asset verification passed: six closed routes inside the extraction bbox, ten OSM source IDs, all 256 glyph files, local-only style source/glyph URLs and the exact archive size.
- Browser smoke mounted the actual `LiveWorld` and `DashboardProvider` in a temporary isolated entry because the shared shell imports a missing `web/src/glorious/logs/menu` module. With all hosts except localhost/127.0.0.1 blocked, the map rendered local roads, parks, lakes and text labels; PMTiles range requests returned 206 and local glyphs returned 200. Landmark selection, zoom in/out and recenter visibly changed the actual map; reduced-motion controls were also exercised. No external HTTP asset requests were observed.
- MapLibre 6's default relative worker URL failed under Vite's dependency prebundle. The permanent fix imports `maplibre-gl-worker.mjs?url` and sets MapLibre's emitted local worker URL explicitly. The isolated **production** Vite bundle built successfully and passed `npm run check:no-mock -- <isolated-output>`; this is not a claim that the full-shell build passed.
- After repairing interrupted local dependency extraction, `npm run build` is blocked **only** by the shared shell's missing `../logs/menu` import and its resulting implicit-any callback. Default `npm run check:no-mock` cannot pass without that production `dist` directory. The shell/Logs owner must provide its assigned menu; no out-of-scope shell or old-UI files were changed.
- Docker's daemon and the simulator were unavailable. The real simulator endpoint returned HTTP 500 through the preview proxy, so the UI honestly showed “Fleet unavailable” with no fabricated markers. Six live markers, hover cards, real worker reassignment and breakdown/resumption still require the real stack plus the separately supplied truck images. Temporary smoke entry files were removed after verification.
