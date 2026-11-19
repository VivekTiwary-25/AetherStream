import type { ReactNode } from 'react'

export interface GlassSurfaceProps {
  strong?: boolean
  className?: string
  children: ReactNode
}

export function GlassSurface({ strong = false, className = '', children }: GlassSurfaceProps) {
  return <div className={`gl-surface${strong ? ' gl-surface--strong' : ''} ${className}`}>{children}</div>
}
