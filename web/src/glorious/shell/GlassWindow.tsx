import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { Rnd } from 'react-rnd'
import { GlassSurface } from './GlassSurface'
import { Icon } from './Icon'
import type { WindowGeometry } from './windows'

export interface GlassWindowProps {
  title: string
  onBack?: () => void
  onClose: () => void
  onFocus: () => void
  initial: WindowGeometry
  strong?: boolean
  active?: boolean
  closing?: boolean
  zIndex?: number
  children: ReactNode
}

export function GlassWindow({ title, onBack, onClose, onFocus, initial, strong = true, active, closing, zIndex, children }: GlassWindowProps) {
  // Geometry belongs to the frame, never its changing content.
  const [geometry, setGeometry] = useState(initial)
  const titleId = useId()
  const titleBar = useRef<HTMLDivElement>(null)
  useEffect(() => { if (active) titleBar.current?.focus({ preventScroll: true }) }, [active])
  return <Rnd
    className={`gl-window${closing ? ' gl-window--closing' : ''}`}
    size={{ width: geometry.w, height: geometry.h }} position={{ x: geometry.x, y: geometry.y }}
    minWidth={Math.min(280, initial.w)} minHeight={Math.min(180, initial.h)} bounds="parent" style={{ zIndex }}
    dragHandleClassName="gl-window__titlebar" cancel="button" onMouseDown={onFocus} onTouchStart={onFocus}
    onDragStop={(_, data) => setGeometry((previous) => ({ ...previous, x: data.x, y: data.y }))}
    onResizeStop={(_, __, element, ___, position) => setGeometry({ x: position.x, y: position.y, w: element.offsetWidth, h: element.offsetHeight })}
  >
    <section role="dialog" aria-labelledby={titleId} aria-modal="false" className="gl-window__frame" onFocusCapture={onFocus}>
      <GlassSurface strong={strong} className="gl-window__surface">
        <div ref={titleBar} tabIndex={0} className="gl-window__titlebar" aria-label={`${title}. Arrow keys move; Shift plus arrow keys resize.`}
          onKeyDown={(event) => {
            if (event.target !== event.currentTarget || !event.key.startsWith('Arrow')) return
            event.preventDefault()
            const dx = event.key === 'ArrowRight' ? 16 : event.key === 'ArrowLeft' ? -16 : 0
            const dy = event.key === 'ArrowDown' ? 16 : event.key === 'ArrowUp' ? -16 : 0
            setGeometry((previous) => event.shiftKey
              ? { ...previous, w: Math.max(Math.min(280, initial.w), Math.min(window.innerWidth - previous.x, previous.w + dx)), h: Math.max(Math.min(180, initial.h), Math.min(window.innerHeight - previous.y, previous.h + dy)) }
              : { ...previous, x: Math.max(0, Math.min(window.innerWidth - previous.w, previous.x + dx)), y: Math.max(0, Math.min(window.innerHeight - previous.h, previous.y + dy)) })
          }}>
          {onBack && <button type="button" className="gl-icon-button" aria-label="Back" onClick={onBack}><Icon name="back" /></button>}
          <h2 id={titleId}>{title}</h2>
          <button type="button" className="gl-icon-button" aria-label={`Close ${title}`} onClick={onClose}><Icon name="close" /></button>
        </div>
        <div className="gl-window__content">{children}</div>
      </GlassSurface>
    </section>
  </Rnd>
}
