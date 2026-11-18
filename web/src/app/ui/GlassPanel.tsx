import type { ReactNode } from 'react'

interface GlassPanelProps {
  title: string
  children: ReactNode
  status?: ReactNode
}

export function GlassPanel({ title, children, status }: GlassPanelProps) {
  return (
    <section className="glass-panel" aria-label={title}>
      <header className="glass-panel__header">
        <h2>{title}</h2>
        {status}
      </header>
      <div className="glass-panel__content">{children}</div>
    </section>
  )
}
