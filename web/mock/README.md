# AetherStream mock server

> **DEV ONLY.** Synthetic data for UI work before the real backend exists. Do not run this in docker compose, nginx, or any deployed build. Production `npm run build` must leave `VITE_USE_MOCK` unset. When the UI is pointed at this server, start it with `VITE_USE_MOCK=1` so the **MOCK DATA** badge is visible.

Speaks contract 04 (REST + WebSocket) and contract 01 truck shapes. Every HTTP response sets `X-Mock: 1`. JSON bodies match the contracts and do not add mock-only fields.

## Run

```bash
cd web/mock
npm install
npm start
```

Listens on `127.0.0.1:18080`. Override with `PORT`.

Point the Vite dev server at this origin (proxy `/api` and `/ws`, or call `http://127.0.0.1:18080` directly) and set `VITE_USE_MOCK=1`.

Self-test (also what CI runs):

```bash
cd web/mock
npm install
node smoke.mjs
```

`smoke.mjs` starts the server on a random port, calls every REST endpoint, and checks the WebSocket sends `snapshot`, then `trucks` and `workers`, within 3 seconds.
