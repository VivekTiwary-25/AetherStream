# Figma plan

This is a build checklist for a future prototype. No Figma file or tested design is implied by this plan.

## Frames to create

1. **Operations — desktop, 1440 px:** bento layout with execution banner, city/logistics scene, summary, flow, workers, results, checkpoints, and chaos panel.
2. **Pipeline editor — desktop, 1440 px:** node palette, graph canvas, selected-node configuration, validation summary, Validate/Save/Run actions.
3. **Recovery focus — desktop:** worker failure event, RECOVERING state, selected checkpoint, and recovered state as a separate later frame.
4. **Compare results — desktop:** comparison summary, matched/mismatch counts, scope note, mismatch table, loading and empty states.
5. **Administration — desktop:** membership, ownership, and access controls separated from runtime actions; mark unsupported features as prototype assumptions.
6. **Operations — narrow, 390 px:** responsive order for state, essential metrics, accessible truck list, workers, results, and actions.
7. **Pipeline editor — narrow:** graph overview, selected node configuration, and validation access without relying on hover.
8. **Accessibility states:** keyboard focus, high-contrast boundary, reduced motion, loading, disconnected, empty, and API error.

Use Auto Layout and named constraints where possible. Mark every invented control or result as a prototype assumption until confirmed by an API contract or implementation.

## Components to define

- Navigation, breadcrumb, page title, and deployed-commit footer.
- State banner and state chip variants: running, backpressured, checkpointing, recovering, paused/stopped, failed.
- Connection/freshness indicator variants: live, reconnecting, unavailable, unknown.
- Metric card variants with title, value, unit, time window, and timestamp.
- Worker row, checkpoint row, event row, result table row, alert, dead-letter row.
- Pipeline operator node variants for Source, Filter, Map, Key-by, Window, Rule, and Sink; selected, error, and disabled states.
- Port, connection, and validation/error marker with text alternative.
- Form field, inline help, compiler message, validation summary, and plan preview.
- Button variants: primary clay, secondary, destructive, icon, disabled, pending.
- City scene with truck, depot, stop, route, and status variants; pair with a non-map list.
- Chaos confirmation dialog and comparison summary/mismatch table.

Use semantic colour/text tokens from [design-system.md](design-system.md). Ensure button and field components include focus and disabled/pending variants. Do not communicate status by colour alone.

## Prototype flows

### Author and run

Start from a sample draft → connect/configure operators → introduce a validation error → select error to locate graph field → correct → validate successfully → save version → confirm run → show returned execution state. Use API-aligned words; do not simulate a real API call as completed evidence.

### Monitor and recover

Show live snapshot → reveal backpressure on a worker while execution remains running → open chaos confirmation → show worker failure event → transition to “Recovering from checkpoint #N” → show RECOVERED and resumed execution only as a distinct frame. Include reconnecting and stale-data alternatives.

### Compare and inspect delivery

Open comparison action → loading → match summary or mismatch detail → open deployed footer identity. Make the comparison checkpoint scope visible and show commit/build time only when data is available.

## Prototype interaction rules

- Animate only state changes; provide a reduced-motion flow with no decorative movement.
- Keep essential text and tables readable without interacting with the map.
- Support keyboard focus order in the prototype; avoid hover-only labels and controls.
- Use realistic empty, failed, and reconnecting states rather than placeholder green success.
- Avoid implying admin operations, autosave, pause/resume, or other API actions unless their contracts exist.

## Checklist

- [ ] Create a named Figma page for foundations, components, and flows.
- [ ] Add colour, type, spacing, radius, and elevation styles from the proposed tokens.
- [ ] Build reusable components with state and interaction variants.
- [ ] Create all desktop and narrow frames listed above.
- [ ] Link author/run, monitor/recover, and compare/delivery prototype flows.
- [ ] Add reduced-motion, focus, disconnected, empty, loading, and error variants.
- [ ] Check text and UI contrast against composed backgrounds, not token swatches alone.
- [ ] Review with representative pipeline author, operator, administrator, and evaluator roles.
- [ ] Record actual findings and revisions separately; do not label assumptions as research.
- [ ] Link the completed Figma file and prototype only after they exist and are accessible.
