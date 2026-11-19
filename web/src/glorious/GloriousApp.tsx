import { DashboardProvider } from '../app/state/store'
import { Home } from './home/Home'
import { LiveWorld } from './map/LiveWorld'
import { EditorPage } from './pipelines/EditorPage'
import { PipelinesPanel } from './pipelines/PipelinesPanel'
import { Sidebar } from './shell/Sidebar'
import { StatusPill } from './shell/StatusPill'
import { WindowManager } from './shell/WindowManager'
import { useScreen } from './shell/router'
import './shell/tokens.css'
import './shell/shell.css'

export function GloriousApp() {
  const { screen, navigate, enteringLive } = useScreen()
  return <div className={`glorious-app${screen === 'home' ? ' glorious-app--home' : ''}`}>
    <DashboardProvider><WindowManager>
      {screen === 'editor' ? <EditorPage /> : <>
        {screen === 'home' && <Home />}
        {screen !== 'home' && <main className={`gl-screen${enteringLive ? ' gl-screen--enter' : ''}`} key={screen}>
          {screen === 'live' ? <LiveWorld /> : <PipelinesPanel />}
        </main>}
        <Sidebar screen={screen} navigate={navigate} />
      </>}
      <StatusPill />
    </WindowManager></DashboardProvider>
  </div>
}
