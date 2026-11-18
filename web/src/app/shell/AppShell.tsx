import { useEffect, useState, type ReactNode } from 'react'
import { CityView } from '../../city/CityView'
import { ChaosPanel, ComparePanel } from '../chaos'
import PipelineEditor from '../editor/PipelineEditor'
import { CheckpointTimeline } from '../timeline'
import { AlertsPanel, DeadLettersPanel, DeliveriesPanel, ExecutionBanner, SpeedWindowsPanel, WorkersPanel } from '../panels/LivePanels'
import { useDashboard, DashboardProvider } from '../state/store'
import { ClayButton } from '../ui/ClayButton'
import { StatusPill, type StatusState } from '../ui/StatusPill'
import { Footer } from './Footer'

type PanelKey = 'speed' | 'deliveries' | 'alerts' | 'workers' | 'dead-letters' | 'chaos' | 'compare' | 'timeline'

function readCollapsed(): Partial<Record<PanelKey, boolean>> {
  try {
    const stored = localStorage.getItem('aetherstream.collapsed-panels')
    if (!stored) return {}
    const value = JSON.parse(stored) as unknown
    return value !== null && typeof value === 'object' ? value as Partial<Record<PanelKey, boolean>> : {}
  } catch {
    return {}
  }
}

function DashboardShell() {
  const { execution, workers, socketStatus } = useDashboard()
  const [collapsed, setCollapsed] = useState(readCollapsed)
  const [editorOpen, setEditorOpen] = useState(false)
  const backpressured = workers.some((worker) => worker?.alive && worker?.backpressured)
  const recoveringWorker = workers.some((worker) => worker?.alive && worker?.state === 'RESTORING')
  const status: StatusState | 'stopped' = execution?.state === 'FAILED'
    ? 'failed'
    : execution?.state === 'RECOVERING' || recoveringWorker
      ? 'recovering'
      : execution?.state === 'RUNNING' && backpressured
        ? 'backpressured'
        : execution?.state === 'RUNNING'
          ? 'running'
          : 'stopped'

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setEditorOpen(false)
      else if (event.key.toLowerCase() === 'e' && !event.repeat && !event.altKey && !event.ctrlKey && !event.metaKey) {
        const target = event.target
        if (target instanceof HTMLElement && (target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName))) return
        setEditorOpen(true)
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [])

  const toggle = (key: PanelKey) => {
    setCollapsed((current) => {
      const next = { ...current, [key]: !current[key] }
      try { localStorage.setItem('aetherstream.collapsed-panels', JSON.stringify(next)) } catch { /* Storage can be unavailable. */ }
      return next
    })
  }
  const panel = (key: PanelKey, title: string, children: ReactNode) => (
    <div className={`panel-slot panel-slot--${key}`} key={key}>
      {collapsed[key]
        ? <button type="button" className="panel-chip" onClick={() => toggle(key)} aria-label={`Expand ${title}`}>{title}</button>
        : <>{children}<button type="button" className="panel-collapse" onClick={() => toggle(key)} aria-label={`Collapse ${title}`} title={`Collapse ${title}`}>−</button></>}
    </div>
  )

  return (
    <main className="app-shell">
      <div className="app-city"><CityView /></div>
      <header className="app-topbar">
        <div className="app-heading">
          <div><p className="app-eyebrow">STREAM PROCESSING CONTROL</p><h1>AetherStream</h1></div>
          {status === 'stopped' ? <span className="status-pill status-pill--stopped"><span className="status-pill__dot" aria-hidden="true" />Stopped</span> : <StatusPill state={status} />}
          <span className={`connection-dot connection-dot--${socketStatus}`} role="status" aria-label={`WebSocket ${socketStatus}`} title={`WebSocket ${socketStatus}`} />
        </div>
        <ClayButton variant="primary" onClick={() => setEditorOpen(true)}>Pipeline</ClayButton>
      </header>
      <ExecutionBanner />
      <section className="panel-grid" aria-label="Dashboard panels">
        {panel('speed', 'Speed windows', <SpeedWindowsPanel />)}
        {panel('deliveries', 'Deliveries / min', <DeliveriesPanel />)}
        {panel('alerts', 'Alerts', <AlertsPanel />)}
        {panel('workers', 'Workers', <WorkersPanel />)}
        {panel('dead-letters', 'Dead letters', <DeadLettersPanel />)}
        {panel('chaos', 'Chaos controls', <ChaosPanel />)}
        {panel('compare', 'Reference comparison', <ComparePanel />)}
        {panel('timeline', 'Checkpoint & recovery timeline', <CheckpointTimeline />)}
      </section>
      <Footer />
      <PipelineEditor open={editorOpen} onClose={() => setEditorOpen(false)} onStarted={() => setEditorOpen(false)} />
    </main>
  )
}

export function AppShell() {
  return <DashboardProvider><DashboardShell /></DashboardProvider>
}
