import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { GlassWindow } from './GlassWindow'
import { WindowsContext, type WindowOptions, type WindowsApi } from './windows'

interface Entry { id: string; options: WindowOptions; closing: boolean; opener: HTMLElement | null }

export function WindowManager({ children, onOpen }: { children: ReactNode; onOpen?: (id: string) => void }) {
  const [entries, setEntries] = useState<Entry[]>([])
  const timers = useRef(new Map<string, ReturnType<typeof setTimeout>>())
  useEffect(() => {
    const pending = timers.current
    return () => { pending.forEach(clearTimeout); pending.clear() }
  }, [])
  const focus = useCallback((id: string) => setEntries((previous) => {
    const index = previous.findIndex((entry) => entry.id === id)
    return index < 0 || index === previous.length - 1 ? previous : [...previous.slice(0, index), ...previous.slice(index + 1), previous[index]]
  }), [])
  const close = useCallback((id: string) => {
    setEntries((previous) => previous.map((entry) => entry.id === id ? { ...entry, closing: true } : entry))
    if (timers.current.has(id)) return
    timers.current.set(id, setTimeout(() => {
      setEntries((previous) => previous.filter((entry) => entry.id !== id))
      timers.current.delete(id)
    }, 150))
  }, [])
  const open = useCallback<WindowsApi['open']>((id, options) => {
    onOpen?.(id)
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const pending = timers.current.get(id)
    if (pending) { clearTimeout(pending); timers.current.delete(id) }
    setEntries((previous) => {
      const existing = previous.find((entry) => entry.id === id)
      if (existing) return [...previous.filter((entry) => entry.id !== id), { ...existing, closing: false }]
      const sidebar = Number.parseFloat(getComputedStyle(document.querySelector('.glorious-app')!).getPropertyValue('--gl-sidebar-width')) || 208
      const w = Math.min(560, window.innerWidth - 32)
      const h = Math.min(420, window.innerHeight - 120)
      const offset = (previous.length % 6) * 24
      const initial = options.initial ?? { x: Math.min(sidebar + 36 + offset, window.innerWidth - w - 16), y: 100 + offset, w, h }
      // Clamp requested geometry too: windows must be reachable on small screens.
      const fitted = { w: Math.min(initial.w, window.innerWidth - 16), h: Math.min(initial.h, window.innerHeight - 16), x: 0, y: 0 }
      fitted.x = Math.max(0, Math.min(initial.x, window.innerWidth - fitted.w))
      fitted.y = Math.max(0, Math.min(initial.y, window.innerHeight - fitted.h))
      return [...previous, { id, options: { ...options, initial: fitted }, closing: false, opener }]
    })
  }, [onOpen])
  const update = useCallback<WindowsApi['update']>((id, options) => setEntries((previous) => previous.map((entry) => entry.id === id ? { ...entry, options: { ...entry.options, ...options } } : entry)), [])
  const active = entries.filter((entry) => !entry.closing).at(-1)
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || event.defaultPrevented || !active) return
      event.preventDefault()
      close(active.id)
      active.opener?.focus({ preventScroll: true })
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [active, close])
  const value = useMemo<WindowsApi>(() => ({ open, close, update, isOpen: (id) => entries.some((entry) => entry.id === id && !entry.closing) }), [open, close, update, entries])
  return <WindowsContext.Provider value={value}>
    {children}
    <div className="gl-window-layer">
      {entries.map(({ id, options, closing, opener }, index) => <GlassWindow key={id} title={options.title} onBack={options.onBack} strong={options.strong}
        initial={options.initial!} active={active?.id === id} closing={closing} zIndex={index + 1}
        onFocus={() => focus(id)} onClose={() => { close(id); opener?.focus({ preventScroll: true }) }}>
        {options.render()}
      </GlassWindow>)}
    </div>
  </WindowsContext.Provider>
}
