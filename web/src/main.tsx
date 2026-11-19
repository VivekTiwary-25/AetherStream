import React, { Suspense, lazy } from 'react'
import ReactDOM from 'react-dom/client'
// Classic UI is loaded only when selected, so the new UI's first load stays small.
const App = lazy(() => import('./App'))
import { GloriousApp } from './glorious/GloriousApp'
import './styles/global.css'

// ?ui=classic selects the old UI. The classic Playwright suite sets localStorage
// aether.ui=classic instead (its specs load '/'); ?ui=glorious always forces the new UI.
function wantsClassic(): boolean {
  const ui = new URLSearchParams(window.location.search).get('ui')
  if (ui === 'classic') return true
  if (ui === 'glorious') return false
  try {
    return window.localStorage.getItem('aether.ui') === 'classic'
  } catch {
    return false
  }
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    {wantsClassic() ? <Suspense fallback={null}><App /></Suspense> : <GloriousApp />}
  </React.StrictMode>,
)
