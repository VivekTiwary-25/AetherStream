import { lazy, Suspense, useCallback, useEffect } from 'react'
import { DashboardProvider } from '../app/state/store'
import { Home } from './home/Home'
import { createPrefetch } from './prefetch'
import { Sidebar } from './shell/Sidebar'
import { StatusPill } from './shell/StatusPill'
import { WindowManager } from './shell/WindowManager'
import { useScreen } from './shell/router'
import './shell/tokens.css'
import './shell/shell.css'

const prefetchLiveWorld = createPrefetch(() => import('./map/LiveWorld').then((module) => ({ default: module.LiveWorld })))
const LiveWorld = lazy(prefetchLiveWorld)
const EditorPage = lazy(() => import('./pipelines/EditorPage').then((module) => ({ default: module.EditorPage })))
const PipelinesPanel = lazy(() => import('./pipelines/PipelinesPanel').then((module) => ({ default: module.PipelinesPanel })))

function LoadingScreen() {
  return <div className="gl-surface" role="status" style={{ position: 'absolute', top: '50%', left: '50%', transform: 'translate(-50%, -50%)', padding: '16px 24px' }}>Loading…</div>
}

export function GloriousApp() {
  const { screen, navigate, enteringLive } = useScreen()
  const onWindowOpen = useCallback((id: string) => {
    if (id.startsWith('analytics.') || id.startsWith('settings.')) navigate('live')
  }, [navigate])
  useEffect(() => {
    const connection = (navigator as Navigator & { connection?: { saveData?: boolean } }).connection
    if (screen !== 'home' || connection?.saveData) return

    const prefetch = () => {
      // Keep speculative failures out of the UI; lazy still receives the import result.
      void prefetchLiveWorld().catch(() => {})
    }
    if (typeof window.requestIdleCallback === 'function') {
      const id = window.requestIdleCallback(prefetch)
      return () => window.cancelIdleCallback(id)
    }
    const id = window.setTimeout(prefetch, 1000)
    return () => window.clearTimeout(id)
  }, [screen])
  return <div className={`glorious-app${screen === 'home' ? ' glorious-app--home' : ''}`}>
    <DashboardProvider><WindowManager onOpen={onWindowOpen}>
      {screen === 'editor' ? <Suspense fallback={<LoadingScreen />}><EditorPage /></Suspense> : <>
        <Sidebar screen={screen} navigate={navigate} />
        {screen === 'home' && <Home />}
        {screen !== 'home' && <main className={`gl-screen${enteringLive ? ' gl-screen--enter' : ''}`} key={screen}>
          <Suspense fallback={<LoadingScreen />}>
            {screen === 'live' ? <LiveWorld /> : <PipelinesPanel />}
          </Suspense>
        </main>}
      </>}
      <StatusPill />
    </WindowManager></DashboardProvider>
  </div>
}
