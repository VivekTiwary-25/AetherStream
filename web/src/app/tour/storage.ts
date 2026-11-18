type StorageKind = 'localStorage' | 'sessionStorage'

function storage(kind: StorageKind): Storage | null {
  try {
    return typeof window === 'undefined' ? null : window[kind]
  } catch {
    return null
  }
}

export function readItem(kind: StorageKind, key: string): string | null {
  try {
    return storage(kind)?.getItem(key) ?? null
  } catch {
    return null
  }
}

export function writeItem(kind: StorageKind, key: string, value: string): void {
  try {
    storage(kind)?.setItem(key, value)
  } catch {
    // Storage may be full or blocked (private mode); the tour still works without it.
  }
}

export function prefersReducedMotion(): boolean {
  try {
    return typeof window !== 'undefined' && typeof window.matchMedia === 'function'
      && window.matchMedia('(prefers-reduced-motion: reduce)').matches
  } catch {
    return false
  }
}
