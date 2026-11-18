import { CityPlaceholder } from '../../city/CityPlaceholder'
import { StatusPill } from '../ui/StatusPill'
import { Footer } from './Footer'

const panelSlots = ['windows', 'deliveries', 'alerts', 'deadLetters', 'workers', 'chaos', 'compare']

export function AppShell() {
  return (
    <main className="app-shell">
      <CityPlaceholder />
      <header className="app-heading">
        <div>
          <p className="app-eyebrow">STREAM PROCESSING CONTROL</p>
          <h1>AetherStream</h1>
        </div>
        <StatusPill state="healthy" />
      </header>
      <section className="panel-grid" aria-label="Dashboard panels">
        {panelSlots.map((slot) => (
          <div className={`panel-slot panel-slot--${slot}`} key={slot} aria-label={`${slot} panel`} />
        ))}
      </section>
      <Footer />
    </main>
  )
}
