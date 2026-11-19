import { useCallback, useEffect, useState } from 'react'

export type Screen = 'home' | 'live' | 'pipelines' | 'editor'
const paths: Record<Screen, string> = { home: '/', live: '/live', pipelines: '/pipelines', editor: '/pipelines/editor' }
const current = (): Screen => window.location.pathname.replace(/\/$/, '') === '/pipelines/editor' ? 'editor' : window.location.pathname.startsWith('/pipelines') ? 'pipelines' : window.location.pathname.startsWith('/live') ? 'live' : 'home'

export function useScreen() {
  const [screen, setScreen] = useState(current)
  const [enteringLive, setEnteringLive] = useState(false)
  useEffect(() => {
    const pop = () => { setScreen(current()); setEnteringLive(false) }
    window.addEventListener('popstate', pop)
    return () => window.removeEventListener('popstate', pop)
  }, [])
  const navigate = useCallback((next: Screen) => {
    if (next === screen) return
    setEnteringLive(screen === 'home' && next === 'live')
    const target = new URL(window.location.href)
    target.pathname = paths[next]
    window.history.pushState(null, '', target)
    setScreen(next)
  }, [screen])
  return { screen, navigate, enteringLive }
}
