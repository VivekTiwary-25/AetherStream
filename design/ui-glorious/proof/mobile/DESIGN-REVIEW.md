# Phone design review, 390x844 (report only, no code changed)
Sources: `proof/mobile/` on `task/t261120-0518-0d13` (combined-phone-*.png, phone-*.png, map-card-fixture-390x844.png), design-pack.md.
CSS refs are the phone `@media (max-width: 768px)` blocks on that branch.

## What looks good
- The bottom bar reads as one clean glass slab. Five equal tabs and the cream active pill match the desktop sidebar language.
- Home video stays full-bleed. The 0.8 glass on the status pill and the bar keeps text readable over sky and road.
- Live World: heading, search, notice and zoom stack sit in a calm column with no collisions in the resting state.
- Pipelines panel: 44 px chips and inputs, 16 px fields (no iOS zoom), no horizontal overflow.

## Top 8 issues (smallest fix first in each)
1. **Window sheets are too see-through.** On Truck Speeds, map labels ("Live World", "Fleet unavailable", Mysuru) show through the title and body, and the text reads muddy (combined-phone-analytics, phone-sheet-after).
   Fix in `shell/shell.css` phone block: `.gl-window__surface { background: var(--gl-glass-strong); backdrop-filter: blur(28px) saturate(120%); }`
2. **Settings sheet does not hide the map controls.** The zoom stack's ghost shows through the sheet's right edge and its glass lets street names bleed in (combined-phone-settings).
   Fix in `shell/shell.css`: `.gl-sidebar__sheet { background: var(--gl-glass-strong); z-index: 41; }`
   Also add: `.glorious-app:has(.gl-sidebar__sheet, .gl-window--active) .gl-live-world__controls { opacity: 0; pointer-events: none; }`
3. **Two tabs look active at once.** Live World and Settings are both cream while the Settings sheet is open. Live World also stays active under the Analytics window.
   The real fix is in `Sidebar.tsx`. Smallest CSS stopgap, in `shell.css`: `.gl-sidebar__nav:has([aria-expanded="true"]) .gl-nav-item--active:not([aria-expanded="true"]) { background: transparent; box-shadow: none; }` (check the attribute name first).
4. **Map controls crowd the bottom bar.** Controls sit at bottom 84 px and the bar is 76 px tall, which leaves an 8 px gap. Under a sheet the "−" button lands on the bar edge (phone-sheet-after).
   Fix in `map/LiveWorld.css`: `.gl-live-world__controls { bottom: calc(var(--gl-bottom-bar-height) + 16px); }`. Optionally hide the locate button at ≤768 px to shorten the stack.
5. **Bottom bar labels are cramped.** At 10 px, "Live World" and "Pipelines" nearly touch their neighbours, and contrast drops over bright video frames.
   Fix in `shell.css`: `.gl-sidebar__surface > .gl-sidebar__nav .gl-nav-item { font-size: 11px; font-weight: 600; letter-spacing: -0.01em; }`
6. **Open search has a tiny close button, a duplicate ×, and a clipped focus ring.** The left "×" is a small glyph, the input also shows a native clear ×, and the dark green ring is cut off at the top-left (combined-phone-search).
   Fix in `LiveWorld.css`: `.gl-live-world__search-toggle { font-size: 22px; }`, `.gl-live-world__search input::-webkit-search-cancel-button { display: none; }`, and `.gl-live-world__search input:focus-visible { outline-offset: -3px; }`
7. **Disabled clay buttons fade into the panel.** Generate, Validate and Run use grey on cream at roughly 2:1 contrast, so they look like smudges (combined-phone-pipelines, combined-phone-editor).
   Fix in `pipelines/pipelines.css`: `.gl-pipelines .gl-clay-button:disabled, .gl-pe .gl-clay-button:disabled { opacity: 1; color: var(--gl-muted); background: rgba(25,27,23,0.05); box-shadow: none; }`
8. **The editor step palette cuts off with no hint that it scrolls.** "Add Window" is chopped at the right edge, and the coloured left borders look like stray "(" characters (combined-phone-editor).
   Fix in `pipelines.css`: `.gl-pe__palette-list { mask-image: linear-gradient(90deg, #000 85%, transparent); scroll-padding-inline: 12px; }`

## Notes
- map-card-fixture-390x844.png still shows the desktop sidebar behind the truck card. It predates the phone shell, so recapture it before judging the card. Its own layout (bottom-anchored, 44 px close button) looks right.
- The editor page shows no bottom bar. Confirm that is intended (full-screen editor). If it is not, add bottom padding equal to `--gl-bottom-bar-height` in `.gl-pe`.
- Sheet heights are fine: windows run from 64 px to bar+8, and the settings sheet hugs its content. Only the glass opacity (#1, #2) needs work.
