# ADR 0007: React, Vite, PixiJS city UI

**Status:** Accepted

## Context

The operator-facing browser surface combines a live city visualisation with operational data and controls. The stack contract pins React, TypeScript, Vite, and PixiJS for `web/`, and architecture documentation assigns the city view and operator panels to that UI (`docs/contracts/00-stack-and-layout.md`, `docs/architecture.md`).

## Decision

Build the UI as a React + Vite application, render the city with PixiJS, and present dashboard content in reusable glass panels. The implementation includes the Pixi city surface and panel component (`web/src/city/CityView.tsx`, `web/src/city/scene/CityScene.ts`, `web/src/app/ui/GlassPanel.tsx`, `web/src/app/panels/LivePanels.tsx`).

## Consequences

React owns application composition while PixiJS handles the interactive city rendering; shared panels keep monitoring sections visually consistent. The browser app carries both a UI framework and a rendering engine, and their state boundary must remain explicit.

## Alternatives considered

- DOM-only city rendering: simpler stack, less suited to rendering many animated trucks and city elements.
- A non-React canvas application: avoids framework layering but does not use the project's chosen component and state patterns.
