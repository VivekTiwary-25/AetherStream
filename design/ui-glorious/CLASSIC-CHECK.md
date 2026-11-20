# Classic UI regression check

Production build (`npm ci`, `npm run build`) served with `npx vite preview`; Playwright Chromium, backend absent. Each route was screenshot-captured and rendered without uncaught errors or console errors.

| Route / setup | Result |
| --- | --- |
| `/?ui=classic` | Classic shell (`.app-shell`) rendered. |
| `/?ui=classic&city=classic` | Classic shell rendered; city canvas mounted. |
| `/` with `localStorage.aether.ui=classic` preset | Classic shell rendered. |
| `/?ui=glorious` with `localStorage.aether.ui=classic` preset | Glorious UI (`.glorious-app`) rendered; query override won. |

Classic tests: `NODE_OPTIONS=--no-experimental-webstorage npm test -- --maxWorkers=2 src/App.test.tsx src/app src/city` — 26 files passed, 154 tests passed, 3 skipped. Production build passed; Vite reported its existing large-chunk warning. No source fix required.
