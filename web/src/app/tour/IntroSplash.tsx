import { useCallback, useEffect, useState } from 'react'
import { prefersReducedMotion, readItem, writeItem } from './storage'
import { INTRO_STORAGE_KEY } from './tourSteps'
import './tour.css'

interface IntroSplashProps {
  durationMs?: number
  onDone?: () => void
}

function shouldShow(): boolean {
  if (prefersReducedMotion()) return false
  return readItem('sessionStorage', INTRO_STORAGE_KEY) !== '1'
}

/** A short, skippable title card shown once per browser session. Skipped entirely with reduced motion. */
export function IntroSplash({ durationMs = 2000, onDone }: IntroSplashProps) {
  const [visible, setVisible] = useState(shouldShow)

  const finish = useCallback(() => {
    setVisible(false)
    onDone?.()
  }, [onDone])

  useEffect(() => {
    writeItem('sessionStorage', INTRO_STORAGE_KEY, '1')
    if (!visible) return
    const timer = window.setTimeout(finish, durationMs)
    const onKey = () => finish()
    window.addEventListener('keydown', onKey)
    return () => {
      window.clearTimeout(timer)
      window.removeEventListener('keydown', onKey)
    }
  }, [visible, durationMs, finish])

  if (!visible) return null
  return (
    <div className="intro-splash" role="dialog" aria-label="AetherStream introduction" onClick={finish}>
      <div>
        <h1>AetherStream</h1>
        <p>a fault-tolerant stream engine</p>
        <button type="button" className="clay-button clay-button--ghost" onClick={(event) => { event.stopPropagation(); finish() }}>Skip</button>
      </div>
    </div>
  )
}
