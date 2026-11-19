import { lazy, Suspense } from 'react'
import { DashboardProvider } from '../app/state/store'
import { Home } from './home/Home'
import { Sidebar } from './shell/Sidebar'
import { StatusPill } from './shell/StatusPill'
import { WindowManager } from './shell/WindowManager'
import { useScreen } from './shell/router'
import './shell/tokens.css'
import './shell/shell.css'

const LiveWorld = lazy(() => import('./map/LiveWorld').then((module) => ({ default: module.LiveWorld })))
const EditorPage = lazy(() => import('./pipelines/EditorPage').then((module) => ({ default: module.EditorPage })))
const PipelinesPanel = lazy(() => import('./pipelines/PipelinesPanel').then((module) => ({ default: module.PipelinesPanel })))

function LoadingScreen() {
  return <div className="gl-surface" role="status" style={{ position: 'absolute', top: '50%', left: '50%', transform: 'translate(-50%, -50%)', padding: '16px 24px' }}>Loading…</div>
}

export function GloriousApp() {
  const { screen, navigate, enteringLive } = useScreen()
  return <div className={`glorious-app${screen === 'home' ? ' glorious-app--home' : ''}`}>
    <DashboardProvider><WindowManager>
      {screen === 'editor' ? <Suspense fallback={<LoadingScreen />}><EditorPage /></Suspense> : <>
        {screen === 'home' && <Home />}
        {screen !== 'home' && <main className={`gl-screen${enteringLive ? ' gl-screen--enter' : ''}`} key={screen}>
          <Suspense fallback={<LoadingScreen />}>
            {screen === 'live' ? <LiveWorld /> : <PipelinesPanel />}
          </Suspense>
        </main>}
        <Sidebar screen={screen} navigate={navigate} />
      </>}
      <StatusPill />
    </WindowManager></DashboardProvider>
  </div>
}
