# AetherStream — visual QA round 2 (T121)

`visual-qa-2.md` from T115 was not on main when T121 started, so T121 ran this second pass itself. It covers the open
items from `visual-qa.md` (T91 / T98 status) and `city-visual-qa.md` (T102 "Left"), plus anything new.

**How it was checked:** the Vite dev server (`VITE_USE_MOCK=1`, so the badge says MOCK DATA) proxying `/api` and `/ws` to
`localhost:18080`. The dev mock could not bind that port because the local live stack already serves it, so the data is
real, read-only data (page loads and the WebSocket only, no POSTs, no chaos actions). Captured with
headless Microsoft Edge (Playwright) at 1440×900, 1024×768 and 390×844 (touch), about 8 s after load, viewport and full page.
Then `npm run lint` (0 errors), `npm test` (25 files, 133 passed, 1 skipped as before) and `npm run build` all passed.

Priority: **P1** = breaks the first impression or hides the product · **P2** = visible polish or clarity problem · **P3** = nit.

## P1

| # | Issue | Status |
|---|---|---|
| P1-1 | **Desktop: the dashboard buries the city.** The right-aligned bento started right under the banner (y≈180) and covered about 2/3 of the first screen, including most of the map and the whole stream-engine column. The city, which is the product, was only visible as a strip on the left. | Fixed in T121: `.panel-grid` now starts at `max(0px, 100vh − 34rem)` (y≈540 at 900 px tall). The first screen shows the map, trucks, packet flow and engine ring, and the top row of panels is still clearly visible as a "more below" hint. Scrolling brings the panels up over the fixed city backdrop. |
| P1-2 | **Phone: the city was not visible at all.** At 390 px the panel list started directly under the banner, so the fixed city backdrop was hidden behind panels on every scroll position. | Fixed in T121: below 1100 px the panel list starts lower (`max(12rem, 100svh − 30rem)`, and `100svh − 21rem` below 700 px). On a phone the first screen shows header, banner, the whole map and the Aether City title and Add-truck dock. The dock and title were moved up (`bottom: 82px / 138px`) so the first panel header can peek in below them without covering the Add truck control. |
| P1-3 | **Empty, stretched panels.** In the row "speed · deliveries · alerts" every cell stretched to the speed list (about 400 px), so Deliveries had about 220 px of empty glass and Alerts about 330 px. | Fixed in T121: Speed windows now spans two rows, with Deliveries and Alerts stacked beside it (`grid-template-areas`), so all three end on the same line with very little empty space. |

## P2

| # | Issue | Status |
|---|---|---|
| P2-1 | **Too many primary buttons.** Eight solid clay-teal buttons below the fold: Send late events, Compare, Take savepoint, Rescale, and all four scenarios. They competed with each other and diluted "primary". | Fixed in T121: one primary action per panel. Compare with reference and Take savepoint stay primary, and Kill worker stays danger. Send late events, Rescale and the four scenarios are now ghost clay buttons. The active scenario gets cyan text and a cyan border on top of its existing glow. |
| P2-2 | Scenario descriptions were cut off with ellipses ("A truck breaks down during its rou…", "Reset the simulator to its normal s…"). | Fixed in T121: descriptions wrap. |
| P2-3 | Savepoint ids in Operations wrapped into 2–3 ragged lines and pushed the Restore buttons around. | Fixed in T121: the label is clamped to 2 lines at 0.85 rem, and the full id and checkpoint are in its `title`. The text node is unchanged, so tests and screen readers see the same string. |
| P2-4 | Timeline checkpoint labels ("#118") were 10 px. | Fixed in T121: 11 px. |
| P2-5 | Status is repeated: the header pill, the banner pill, the city's top-right pill and the green connection dot plus the banner's "Live" chip. | Left: the header pill and banner pill come from the same hook (X3), and unit tests plus the e2e rely on both. The city pill is lane 5 (`web/src/city`). Removing one of them is a design decision for the owner. |
| P2-6 | While the socket is down and no execution is known, the header and banner say "Stopped", which can read as "the pipeline was stopped". | Left: needs an "Unknown/offline" execution state in `useExecutionStatus` and the StatusPill set, and a decision on its colour. The red "Live connection lost" chip and the dimmed city already show the real cause. |
| P2-7 | X3 (Workers panel during recovery) and the X4 dropped-WS-frame problem from round 1. | Left: they need `web/src/api/ws.ts` to expose every message (outside this task's files). |
| P2-8 | On a phone the map is about 250 px wide with empty space under it, and the engine column is a sliver (round-1 mobile-01). | Left: city scene layout (`web/src/city`, lane 5). |
| P2-9 | Engine column text is still under 11 px at full-city zoom, and there is no mini-map or reset chip when zoomed (T102 "Left"). | Left: city scene (lane 5). |

## P3
- Dead letters still stretches to the height of Workers (about 100 px of empty glass at 3 workers). Left: harmless, and it keeps the bento edges aligned.
- The timeline keeps about 40 px of headroom above the markers. That space is reserved for the "✕ worker lost" labels, so it was kept.
