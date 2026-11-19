# Cold-start timings

| Date (UTC) | Docker engine | Compose down | Compose up | Services healthy | API health | DEMO READY | Total |
|---|---:|---:|---:|---:|---:|---:|---:|
| 2026-11-19 (#120) | — | — | — | — | — | — | 54s |
| 2026-11-19 (#107) | — | — | — | — | — | — | 56s |
| 2026-11-19 (#104) | — | — | — | — | — | — | 52s |
| 2026-11-19 (#99) | — | — | — | — | — | — | 62s |
| 2026-11-19 (#48) | — | — | — | — | — | — | 52s |
| **Median** | — | — | — | — | — | — | **54s** |
| vivek-pc laptop | — | — | — | — | — | — | — |

GitHub's public jobs API exposes only the aggregate “Measure cold start” step duration, not the timer's individual phase results. Totals above are elapsed step times from `started_at` to `completed_at`; phase values are unavailable from that API.
