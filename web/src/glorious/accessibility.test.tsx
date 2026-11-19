import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { axe } from 'vitest-axe'
import { DashboardContext, initialDashboardState } from '../app/state/store'
import { AnalyticsPanel } from './analytics/AnalyticsPanel'
import { PipelinesPanel } from './pipelines/PipelinesPanel'
import { LogsRecovery } from './recovery/LogsRecovery'
import { Sidebar } from './shell/Sidebar'
import { WindowManager } from './shell/WindowManager'
import { useWindows } from './shell/windows'


afterEach(() => { cleanup(); vi.unstubAllGlobals() })

async function expectAccessible(container: HTMLElement) {
  const results = await axe(container)
  expect(results.violations).toEqual([])
}

function TruckSpeedsOpener() {
  const windows = useWindows()
  return <button type="button" onClick={() => windows.open('analytics.speeds', {
    title: 'Truck Speeds', render: () => <AnalyticsPanel id="truck-speeds" />,
  })}>Open Truck Speeds</button>
}

describe('Glorious accessibility', () => {
  it('checks the sidebar', async () => {
    const { container } = render(<div className="glorious-app"><WindowManager><Sidebar screen="live" navigate={() => undefined} /></WindowManager></div>)
    await expectAccessible(container)
  })

  it('checks a Truck Speeds glass window', async () => {
    const { container } = render(<div className="glorious-app"><DashboardContext.Provider value={initialDashboardState}>
      <WindowManager><TruckSpeedsOpener /></WindowManager>
    </DashboardContext.Provider></div>)
    fireEvent.click(screen.getByRole('button', { name: 'Open Truck Speeds' }))
    await expectAccessible(container)
  })

  it('checks Logs & Recovery', async () => {
    const { container } = render(<div className="glorious-app"><DashboardContext.Provider value={initialDashboardState}>
      <WindowManager><LogsRecovery /></WindowManager>
    </DashboardContext.Provider></div>)
    await expectAccessible(container)
  })

  it('checks the Pipelines panel', async () => {
    const { container } = render(<div className="glorious-app"><WindowManager><PipelinesPanel /></WindowManager></div>)
    await expectAccessible(container)
  })
})
