import { useCallback, useEffect, useLayoutEffect, useState } from 'react'
import { ClayButton } from '../ui/ClayButton'
import { readItem, writeItem } from './storage'
import { TOUR_STORAGE_KEY, tourSteps, type TourStep } from './tourSteps'
import './tour.css'

interface TourOverlayProps {
  /** When false nothing is rendered. Defaults to true. */
  open?: boolean
  onClose?: () => void
  steps?: TourStep[]
}

interface Rect { top: number; left: number; width: number; height: number }

function initialStep(count: number): number {
  const stored = Number.parseInt(readItem('localStorage', TOUR_STORAGE_KEY) ?? '', 10)
  return Number.isInteger(stored) && stored >= 0 && stored < count ? stored : 0
}

/** Splits "a, b[x="1,2"]" on top-level commas so alternatives can be tried in priority order. */
function selectorAlternatives(selector: string): string[] {
  const parts: string[] = []
  let depth = 0
  let quote: string | null = null
  let start = 0
  for (let i = 0; i < selector.length; i += 1) {
    const char = selector[i]
    if (quote) { if (char === quote) quote = null }
    else if (char === '"' || char === "'") quote = char
    else if (char === '[' || char === '(') depth += 1
    else if (char === ']' || char === ')') depth -= 1
    else if (char === ',' && depth === 0) { parts.push(selector.slice(start, i)); start = i + 1 }
  }
  parts.push(selector.slice(start))
  return parts.map((part) => part.trim()).filter(Boolean)
}

function findTarget(selector: string | undefined): Element | null {
  if (!selector || typeof document === 'undefined') return null
  for (const alternative of selectorAlternatives(selector)) {
    try {
      const element = document.querySelector(alternative)
      if (element) return element
    } catch {
      // invalid selector: try the next alternative
    }
  }
  return null
}

function findTargetRect(selector: string | undefined): Rect | null {
  const element = findTarget(selector)
  if (!element) return null
  try {
    const box = element.getBoundingClientRect()
    if (!box.width && !box.height) return null
    const pad = 8
    return { top: box.top - pad, left: box.left - pad, width: box.width + pad * 2, height: box.height + pad * 2 }
  } catch {
    return null // detached node: just skip the spotlight
  }
}

function revealTarget(selector: string | undefined) {
  const element = findTarget(selector)
  if (!element || typeof (element as HTMLElement).scrollIntoView !== 'function') return
  const box = element.getBoundingClientRect()
  // Large targets (the full-screen city) are already "visible"; only scroll for elements off screen.
  if (box.bottom > 0 && box.top < window.innerHeight) return
  try { (element as HTMLElement).scrollIntoView({ block: 'center', inline: 'nearest' }) } catch { /* ignore */ }
}

export function TourOverlay({ open = true, onClose, steps = tourSteps }: TourOverlayProps) {
  const [index, setIndex] = useState(() => initialStep(steps.length))
  const [rect, setRect] = useState<Rect | null>(null)
  const step: TourStep | undefined = steps[Math.min(index, steps.length - 1)]
  const last = index >= steps.length - 1

  const go = useCallback((next: number) => {
    const clamped = Math.max(0, Math.min(steps.length - 1, next))
    setIndex(clamped)
    writeItem('localStorage', TOUR_STORAGE_KEY, String(clamped))
  }, [steps.length])

  const close = useCallback(() => onClose?.(), [onClose])
  const finish = useCallback(() => {
    // A finished tour starts again from step 1 next time; closing early resumes where it stopped.
    writeItem('localStorage', TOUR_STORAGE_KEY, '0')
    setIndex(0)
    onClose?.()
  }, [onClose])

  useEffect(() => {
    if (!open) return
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'ArrowRight') { event.preventDefault(); go(index + 1) }
      else if (event.key === 'ArrowLeft') { event.preventDefault(); go(index - 1) }
      else if (event.key === 'Escape') { event.preventDefault(); close() }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, index, go, close])

  const target = step?.target
  useLayoutEffect(() => {
    if (!open) return
    const update = () => setRect(findTargetRect(target))
    revealTarget(target)
    update()
    window.addEventListener('resize', update)
    window.addEventListener('scroll', update, true)
    return () => {
      window.removeEventListener('resize', update)
      window.removeEventListener('scroll', update, true)
    }
  }, [open, target])

  if (!open || !step) return null

  const titleId = `tour-step-title-${step.id}`
  return (
    <>
      {rect && (
        <div
          className="tour-spotlight"
          data-testid="tour-spotlight"
          aria-hidden="true"
          style={{ top: rect.top, left: rect.left, width: rect.width, height: rect.height }}
        />
      )}
      <section className="tour-card" role="dialog" aria-modal="false" aria-labelledby={titleId}>
        <p className="tour-card__count">Step {index + 1} of {steps.length}</p>
        <h2 id={titleId}>{step.title}</h2>
        <p className="tour-card__body">{step.body}</p>
        <div className="tour-card__actions">
          <ClayButton variant="ghost" type="button" onClick={close}>Close</ClayButton>
          <ClayButton variant="ghost" type="button" onClick={() => go(index - 1)} disabled={index === 0}>Back</ClayButton>
          <ClayButton type="button" onClick={() => (last ? finish() : go(index + 1))}>{last ? 'Finish' : 'Next'}</ClayButton>
        </div>
      </section>
    </>
  )
}
