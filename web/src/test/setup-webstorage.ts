// Node 25+ ships its own experimental Web Storage global. Without
// --localstorage-file it is undefined or throws, and it shadows jsdom's
// window.localStorage/sessionStorage, so any test that touches storage fails.
// vite.config.ts already turns the Node flag off where supported; this is the
// fallback: if the global storage is missing or broken, put jsdom's real
// Storage objects back on the global.
type JsdomHolder = { jsdom?: { window: Window } }

function works(get: () => Storage | undefined): boolean {
  try {
    const s = get()
    return !!s && typeof s.setItem === 'function' && typeof s.clear === 'function'
  } catch {
    return false
  }
}

const dom = (globalThis as unknown as JsdomHolder).jsdom
if (dom) {
  for (const name of ['localStorage', 'sessionStorage'] as const) {
    if (!works(() => globalThis[name])) {
      const real = dom.window[name]
      Object.defineProperty(globalThis, name, { configurable: true, enumerable: true, get: () => real })
    }
  }
}
