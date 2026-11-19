import { useEffect, useState } from 'react'
import { MockBadge } from './MockBadge'

export function Footer() {
  const [version, setVersion] = useState<string | null>(null)

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

    return () => {
      active = false
    }
  }, [])

  return (
    <footer className="app-footer" data-tour="devops">
      <span>Deployed version: {version ?? '…'}</span>
      <MockBadge />
    </footer>
  )
}
