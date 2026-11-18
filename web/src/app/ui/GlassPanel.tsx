import type { ReactNode } from 'react'

interface GlassPanelProps {
  title: string
  children: ReactNode
  status?: ReactNode
}

export function GlassPanel({ title, children, status }: GlassPanelProps) {
  const headingId = `panel-heading-${title.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`
  return (
    <section className="glass-panel" aria-labelledby={headingId}>
      <header className="glass-panel__header">
        <h2 id={headingId}>{title}</h2>
        {status}
      </header>
      <div className="glass-panel__content">{children}</div>
    </section>
  )
}
