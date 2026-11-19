import { useEffect, useState, type ReactNode } from 'react'
import { CityView } from '../../city/CityView'
import { ChaosPanel, ComparePanel } from '../chaos'
import PipelineEditor from '../editor/PipelineEditor'
import { OpsPanel } from '../ops'
import { ScenarioPanel } from '../scenarios'
import { CheckpointTimeline } from '../timeline'
import { IntroSplash, TourOverlay } from '../tour'
import { AlertsPanel, DeadLettersPanel, DeliveriesPanel, ExecutionBanner, ExecutionStatusPill, SpeedWindowsPanel, WorkersPanel } from '../panels/LivePanels'
import { useExecutionStatus } from '../state/executionStatus'
import { useDashboard, DashboardProvider } from '../state/store'
import { ClayButton } from '../ui/ClayButton'
import { Footer } from './Footer'

type PanelKey = 'speed' | 'deliveries' | 'alerts' | 'workers' | 'dead-letters' | 'chaos' | 'compare' | 'timeline' | 'ops' | 'scenarios'

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
  const { execution, socketStatus } = useDashboard()
  // Same derived state as the execution banner, so the header badge never contradicts it.
  const { status } = useExecutionStatus()
  const [collapsed, setCollapsed] = useState(readCollapsed)
  const [editorOpen, setEditorOpen] = useState(false)
  const [tourOpen, setTourOpen] = useState(false)

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
  const panel = (key: PanelKey, title: string, children: ReactNode, tour?: string) => (
    <div className={`panel-slot panel-slot--${key}`} key={key} data-tour={tour}>
      {collapsed[key]
        ? <button type="button" className="panel-chip" onClick={() => toggle(key)} aria-label={`Expand ${title}`}>{title}</button>
        : <>{children}<button type="button" className="panel-collapse" onClick={() => toggle(key)} aria-label={`Collapse ${title}`} title={`Collapse ${title}`}>−</button></>}
    </div>
  )

  return (
    <main className={`app-shell${socketStatus === 'disconnected' ? ' app-shell--offline' : ''}`}>
      <div className="app-city" data-tour="city"><CityView /></div>
      <header className="app-topbar">
        <div className="app-heading">
          <div><p className="app-eyebrow">STREAM PROCESSING CONTROL</p><h1>AetherStream</h1></div>
          <ExecutionStatusPill status={status} />
          <span className={`connection-dot connection-dot--${socketStatus}`} role="status" aria-label={`WebSocket ${socketStatus}`} title={`WebSocket ${socketStatus}`} />
          <div className="app-actions">
            <ClayButton variant="ghost" onClick={() => { setEditorOpen(false); setTourOpen(true) }} aria-label="Start tour" data-tour="tour">Tour</ClayButton>
            <ClayButton variant="primary" onClick={() => { setTourOpen(false); setEditorOpen(true) }} aria-label="Open pipeline editor" title="Open pipeline editor (E) - build, validate or load an example" data-tour="editor">Pipeline</ClayButton>
          </div>
        </div>
      </header>
      <ExecutionBanner />
      <section className="panel-grid" aria-label="Dashboard panels" data-tour="panels">
        {panel('speed', 'Speed windows', <SpeedWindowsPanel />)}
        {panel('deliveries', 'Deliveries / min', <DeliveriesPanel />)}
        {panel('alerts', 'Alerts', <AlertsPanel />)}
        {panel('workers', 'Workers', <WorkersPanel />)}
        {panel('dead-letters', 'Dead letters', <DeadLettersPanel />)}
        {panel('chaos', 'Chaos controls', <ChaosPanel />, 'chaos')}
        {panel('compare', 'Reference comparison', <ComparePanel />, 'compare')}
        {panel('timeline', 'Checkpoint & recovery timeline', <CheckpointTimeline />, 'kill')}
        {panel('ops', 'Operations', <OpsPanel key={`${execution?.id ?? 'none'}:${execution?.state ?? ''}`} />, 'ops')}
        {panel('scenarios', 'City scenarios', <ScenarioPanel />, 'scenarios')}
      </section>
      <Footer />
      <PipelineEditor open={editorOpen} onClose={() => setEditorOpen(false)} onStarted={() => setEditorOpen(false)} />
      <TourOverlay open={tourOpen} onClose={() => setTourOpen(false)} />
      <IntroSplash />
    </main>
  )
}

export function AppShell() {
  return <DashboardProvider><DashboardShell /></DashboardProvider>
}
