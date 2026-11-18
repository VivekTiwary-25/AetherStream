# Design system

Proposed tokens for the AetherStream UI. These values define a starting point, not an implemented stylesheet. Verify contrast against actual composited glass backgrounds before release.

## Colour tokens

| Token | Hex | Role |
|---|---|---|
| `canvas` | `#0B1020` | Main dark navy page background. |
| `surface` | `#111827` | Charcoal panel base. |
| `surface-raised` | `#1B2638` | Raised panel and menu surface. |
| `surface-glass` | `#182337` | Glass panel tint, composited over canvas. |
| `text-primary` | `#F4F7FF` | Main text on dark surfaces. |
| `text-secondary` | `#C3CCDA` | Supporting text; verify contrast on each use. |
| `text-muted` | `#A5B1C4` | Secondary labels; do not use where contrast fails. |
| `accent-cyan` | `#67D9E8` | Restrained interactive/focus accent; pair with dark text when used as a fill. |
| `accent-violet` | `#A99BFF` | Secondary data accent. |
| `accent-clay` | `#D97958` | Primary clay button fill; use dark foreground. |
| `border-subtle` | `#3A475D` | Panel borders and dividers. |
| `state-running` | `#57D6A1` | Running state indicator. |
| `state-backpressured` | `#F2B84B` | Backpressure indicator. |
| `state-checkpointing` | `#67D9E8` | Checkpointing indicator. |
| `state-recovering` | `#A99BFF` | Recovery indicator. |
| `state-paused` | `#A5B1C4` | Paused/stopped indicator. |
| `state-failed` | `#F17676` | Failed state indicator. |

State colours are redundant cues, never the only state label. For text, use a contrast-tested foreground/background pair; do not use the vivid state hue as small text without verification. Normal text must meet WCAG AA 4.5:1; large text and meaningful graphical boundaries 3:1. For example, use `text-primary` on `canvas` or `surface` and verify all translucent composites.

## Type scale

Use a legible system sans-serif stack; use tabular numerals for changing metrics. Keep line length moderate for explanatory content.

| Token | Size / line height | Use |
|---|---|---|
| `type-display` | 32 / 40 px | Page title, one per view. |
| `type-title` | 24 / 32 px | Major panel or editor title. |
| `type-heading` | 18 / 26 px | Section heading. |
| `type-body` | 16 / 24 px | Standard copy and controls. |
| `type-compact` | 14 / 20 px | Table and supporting UI; retain contrast. |
| `type-caption` | 12 / 16 px | Metadata only, not the sole source of essential information. |
| `type-mono` | 13 / 20 px | Commit IDs, code, event IDs, and values needing alignment. |

## Spacing, radius, elevation

| Token | Value | Use |
|---|---:|---|
| `space-1` | 4 px | Small icon/text gap. |
| `space-2` | 8 px | Compact control spacing. |
| `space-3` | 12 px | Dense table cell spacing. |
| `space-4` | 16 px | Standard component padding. |
| `space-6` | 24 px | Panel and bento gaps. |
| `space-8` | 32 px | Major section separation. |
| `radius-sm` | 8 px | Inputs and small chips. |
| `radius-md` | 14 px | Cards and panels. |
| `radius-lg` | 20 px | Main feature panels. |
| `radius-pill` | 999 px | Compact tags and status chips. |

Use restrained elevation: a 1 px `border-subtle`, subtle dark shadow below panels, and a small luminous accent only for focus or active selection. Glass recipe: `surface-glass` tint with moderate transparency and backdrop blur only as progressive enhancement; include an opaque fallback and confirm text contrast over varied content. Avoid stacking multiple blurred layers.

## Clay primary button recipe

Use a warm `accent-clay` fill, dark readable label, rounded `radius-md` shape, and a small lower-edge shadow that suggests a tactile clay surface. On hover, adjust brightness slightly; on press, reduce shadow and shift by at most 1 px. Provide a visible focus ring and disabled state. Keep destructive actions separate; clay styling does not mean “safe.” Verify foreground contrast.

## Runtime states

| State | Token | Required visible wording / detail |
|---|---|---|
| Running | `state-running` | “Running”; show execution identity and freshness. |
| Backpressured | `state-backpressured` | “Backpressured” on affected worker/flow; can coexist with execution “Running.” |
| Checkpointing | `state-checkpointing` | “Checkpointing”; show checkpoint identifier when available. |
| Recovering | `state-recovering` | “Recovering from checkpoint #N” when known; otherwise “Recovering.” |
| Paused/stopped | `state-paused` | Explicitly distinguish “Paused” only if supported; otherwise show “Stopped.” |
| Failed | `state-failed` | “Failed” plus actionable available error context. |

## Component inventory

- Navigation and deployment footer with commit and optional build time.
- Pipeline canvas, operator palette, typed node, ports, connection, minimap if useful.
- Node configuration form, validation summary, inline compiler error, plan preview.
- Primary, secondary, destructive, icon, and disabled buttons.
- Execution state banner, freshness/connection indicator, worker table and state chip.
- City/logistics visualization with accessible truck list alternative.
- Metric card, flow/partition table, result window table, alerts and dead-letter table.
- Checkpoint timeline/table, recovery event feed, comparison summary and mismatch table.
- Chaos controls with confirmation, pending, success, and failure feedback.
- Empty, loading, unavailable, and error states that are distinct from zero results.
