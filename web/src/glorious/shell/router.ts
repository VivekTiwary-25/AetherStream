import { useCallback, useEffect, useState } from 'react'
import { PIPELINES_MODE } from '../pipelines/mode'

export type Screen = 'home' | 'live' | 'pipelines' | 'editor'
const paths: Record<Screen, string> = { home: '/', live: '/live', pipelines: '/pipelines', editor: '/pipelines/editor' }
const current = (): Screen => {
  const screen = window.location.pathname.replace(/\/$/, '') === '/pipelines/editor' ? 'editor' : window.location.pathname.startsWith('/pipelines') ? 'pipelines' : window.location.pathname.startsWith('/live') ? 'live' : 'home'
  return PIPELINES_MODE === 'hidden' && (screen === 'pipelines' || screen === 'editor') ? 'home' : screen
}

export function useScreen() {
  const [screen, setScreen] = useState(current)
  const [enteringLive, setEnteringLive] = useState(false)
  useEffect(() => {
    const pop = () => {
      if (PIPELINES_MODE === 'hidden' && window.location.pathname.startsWith('/pipelines')) {
        const target = new URL(window.location.href)
        target.pathname = paths.home
        window.history.replaceState(null, '', target)
      }
      setScreen(current())
      setEnteringLive(false)
    }
    pop()
    window.addEventListener('popstate', pop)
    return () => window.removeEventListener('popstate', pop)
  }, [])
  const navigate = useCallback((next: Screen) => {
    if (PIPELINES_MODE === 'hidden' && (next === 'pipelines' || next === 'editor')) next = 'home'
    if (next === screen) return
    setEnteringLive(screen === 'home' && next === 'live')
    const target = new URL(window.location.href)
    target.pathname = paths[next]
    window.history.pushState(null, '', target)
    setScreen(next)
  }, [screen])
  return { screen, navigate, enteringLive }
}
