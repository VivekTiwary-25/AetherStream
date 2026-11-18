import { CityView } from '../../city/CityView'
import { AlertsPanel, DeadLettersPanel, DeliveriesPanel, ExecutionBanner, SpeedWindowsPanel, WorkersPanel } from '../panels/LivePanels'
import { DashboardProvider } from '../state/store'
import { StatusPill } from '../ui/StatusPill'
import { Footer } from './Footer'

export function AppShell() {
  return (
    <DashboardProvider>
      <main className="app-shell">
        <CityView />
        <header className="app-heading">
          <div>
            <p className="app-eyebrow">STREAM PROCESSING CONTROL</p>
            <h1>AetherStream</h1>
          </div>
          <StatusPill state="healthy" />
        </header>
        <ExecutionBanner />
        <section className="panel-grid" aria-label="Dashboard panels">
          <div className="panel-slot panel-slot--windows"><SpeedWindowsPanel /></div>
          <div className="panel-slot panel-slot--deliveries"><DeliveriesPanel /></div>
          <div className="panel-slot panel-slot--alerts"><AlertsPanel /></div>
          <div className="panel-slot panel-slot--deadLetters"><DeadLettersPanel /></div>
          <div className="panel-slot panel-slot--workers"><WorkersPanel /></div>
        </section>
        <Footer />
      </main>
    </DashboardProvider>
  )
}
