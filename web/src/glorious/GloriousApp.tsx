import { lazy, Suspense } from 'react'
import { DashboardProvider } from '../app/state/store'
import { Home } from './home/Home'
import { GlassSurface } from './shell/GlassSurface'
import { PipelinesPanel } from './pipelines/PipelinesPanel'
import { Sidebar } from './shell/Sidebar'
import { StatusPill } from './shell/StatusPill'
import { WindowManager } from './shell/WindowManager'
import { useScreen } from './shell/router'
import './shell/tokens.css'
import './shell/shell.css'

const LiveWorld = lazy(() => import('./map/LiveWorld').then((module) => ({ default: module.LiveWorld })))
const EditorPage = lazy(() => import('./pipelines/EditorPage').then((module) => ({ default: module.EditorPage })))

const loadingScreen = <div style={{ position: 'absolute', inset: 0, display: 'grid', placeItems: 'center' }}>
  <GlassSurface className="gl-placeholder"><span role="status">Loading…</span></GlassSurface>
</div>

export function GloriousApp() {
  const { screen, navigate, enteringLive } = useScreen()
  return <div className={`glorious-app${screen === 'home' ? ' glorious-app--home' : ''}`}>
    <DashboardProvider><WindowManager>
      {screen === 'editor' ? <Suspense fallback={loadingScreen}><EditorPage /></Suspense> : <>
        {screen === 'home' && <Home />}
        {screen !== 'home' && <main className={`gl-screen${enteringLive ? ' gl-screen--enter' : ''}`} key={screen}>
          <Suspense fallback={loadingScreen}>
            {screen === 'live' ? <LiveWorld /> : <PipelinesPanel />}
          </Suspense>
        </main>}
        <Sidebar screen={screen} navigate={navigate} />
      </>}
      <StatusPill />
    </WindowManager></DashboardProvider>
  </div>
}
