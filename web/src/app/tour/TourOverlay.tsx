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

function findTargetRect(selector: string | undefined): Rect | null {
  if (!selector || typeof document === 'undefined') return null
  try {
    const element = document.querySelector(selector)
    if (!element) return null
    const box = element.getBoundingClientRect()
    if (!box.width && !box.height) return null
    const pad = 8
    return { top: box.top - pad, left: box.left - pad, width: box.width + pad * 2, height: box.height + pad * 2 }
  } catch {
    return null // invalid selector or detached node: just skip the spotlight
  }
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
          <ClayButton type="button" onClick={() => (last ? close() : go(index + 1))}>{last ? 'Finish' : 'Next'}</ClayButton>
        </div>
      </section>
    </>
  )
}
