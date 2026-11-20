# Glorious performance check

Profile: Chromium, production `vite preview`, 1365×768 viewport, CPU throttling 4×, local assets. Cold first navigation; no backend requirement.

| Metric | Before | After |
|---|---:|---:|
| Home first paint | 1,072 ms | 84 ms* |
| Home interactive (DOMContentLoaded; Home navigation control present) | 1,187 ms | 173 ms* |
| JavaScript downloaded before Home interaction | 97,235 bytes | 97,250 bytes |
| Live World first map canvas after click | 262 ms† | 465 ms† |
| Live World heap after 2 minutes | 15.5 MB | 15.4 MB |

* Home paint and DOMContentLoaded are raw browser timings, not comparable across these separate Chromium launches; the before run included first-launch overhead. Neither run reported a `first-contentful-paint` entry. The after run fetched the poster in 33 ms (255,584 encoded bytes); the Home loop video was not requested while Home was displayed.

† The app does not expose MapLibre's internal `idle` event to page instrumentation. These values are first `.maplibregl-canvas` creation, not map idle. Before used a warm cache; after used a cold cache, so the values are not comparable and do not establish a map-load improvement.

Changes: preload the Home poster at high fetch priority; set the Home video to `preload="metadata"` so the full loop is not eagerly fetched while Home is displayed. The page still auto-plays the video, so playback behavior is unchanged. No map behavior, visuals, or routes changed.


