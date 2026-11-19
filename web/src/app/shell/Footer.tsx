import { useEffect, useState } from 'react'
import { MockBadge } from './MockBadge'

export function Footer() {
  const [version, setVersion] = useState<string | null>(null)
  const [expoBuild, setExpoBuild] = useState<string | null>(null)

  useEffect(() => {
    let active = true
    fetch('/api/version')
      .then((response) => {
        if (!response.ok) throw new Error('Version request failed')
        return response.json() as Promise<{ commit: string; builtAt: string }>
      })
      .then(({ commit }) => {
        if (active) setVersion(commit.slice(0, 7))
      })
      .catch(() => {
        if (active) setVersion('unknown')
      })

    fetch('/expo-build.txt', { cache: 'no-store' })
      .then((response) => {
        if (!response.ok) throw new Error('Expo build request failed')
        return response.text()
      })
      .then((text) => {
        const counter = text.trim()
        if (active && /^\d+$/.test(counter)) setExpoBuild(counter)
      })
      .catch(() => {
        // Older/offline deployments may not have a demo counter.
      })

    return () => {
      active = false
    }
  }, [])

  return (
    <footer className="app-footer" data-tour="devops">
      <span>Deployed version: {version ?? '…'}</span>
      {expoBuild !== null && <span>Expo build #{expoBuild}</span>}
      <MockBadge />
    </footer>
  )
}
