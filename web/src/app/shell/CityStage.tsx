import { useEffect, useState } from 'react'
import { CityView } from '../../city/CityView'
import { hasSoftwareWebGL, storedMode, storeMode, type CityMode } from './cityMode'

/*
 * The live city redraws its whole Pixi scene every frame. With a GPU that is cheap; with a software WebGL renderer
 * (GitHub runners, VMs, laptops with acceleration off) it pegs the main thread: the dashboard drops to ~3 fps, clicks
 * never settle and screenshots time out. In that case the shell shows a static backdrop instead and lets the presenter
 * opt back in. Override with `?city=live|static` or localStorage CITY_MODE_KEY (see cityMode.ts).
 */
const WATCHDOG_WARMUP_MS = 4000
const WATCHDOG_SAMPLE_MS = 3000
const WATCHDOG_MIN_FPS = 12

type Reason = 'software' | 'slow' | 'chosen'

function initialState(): { mode: CityMode; reason: Reason; pinned: boolean } {
  const chosen = storedMode()
  if (chosen) return { mode: chosen, reason: 'chosen', pinned: true }
  return hasSoftwareWebGL() ? { mode: 'static', reason: 'software', pinned: false } : { mode: 'live', reason: 'chosen', pinned: false }
}

export function CityStage() {
  const [state, setState] = useState(initialState)

  // Frame-rate watchdog for slow renderers the name check cannot recognise. Skipped once the presenter chose a mode.
  useEffect(() => {
    if (state.mode !== 'live' || state.pinned || typeof window.requestAnimationFrame !== 'function') return
    let frames = 0
    let frame = 0
    let sampling = false
    const count = () => {
      frames += 1
      frame = window.requestAnimationFrame(count)
    }
    const start = window.setTimeout(() => {
      if (document.hidden) return
      sampling = true
      frame = window.requestAnimationFrame(count)
    }, WATCHDOG_WARMUP_MS)
    const check = window.setTimeout(() => {
      window.cancelAnimationFrame(frame)
      if (sampling && !document.hidden && frames / (WATCHDOG_SAMPLE_MS / 1000) < WATCHDOG_MIN_FPS) {
        setState({ mode: 'static', reason: 'slow', pinned: false })
      }
    }, WATCHDOG_WARMUP_MS + WATCHDOG_SAMPLE_MS)
    return () => {
      window.clearTimeout(start)
      window.clearTimeout(check)
      window.cancelAnimationFrame(frame)
    }
  }, [state.mode, state.pinned])

  if (state.mode === 'live') return <CityView />
  const why = state.reason === 'software' ? 'no GPU acceleration detected' : state.reason === 'slow' ? 'this browser rendered it too slowly' : 'turned off'
  return (
    <section className="city-static" aria-label="Live delivery city">
      <div className="city-static__notice" role="status">
        <span>Live city paused: {why}.</span>
        <button type="button" className="clay-button clay-button--ghost" onClick={() => { storeMode('live'); setState({ mode: 'live', reason: 'chosen', pinned: true }) }}>
          Start live city
        </button>
      </div>
    </section>
  )
}
