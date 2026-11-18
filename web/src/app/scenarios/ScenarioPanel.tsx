import { useEffect, useState } from 'react'
import { ClayButton } from '../ui/ClayButton'
import { GlassPanel } from '../ui/GlassPanel'
import { applyScenario, builtInScenarios, getScenarios, getSimulatorState, type Scenario } from './scenariosApi'

const trucks = Array.from({ length: 8 }, (_, index) => `truck-${String(index + 1).padStart(3, '0')}`)

export function ScenarioPanel() {
  const [scenarios, setScenarios] = useState<Scenario[]>(builtInScenarios)
  const [activeScenario, setActiveScenario] = useState<string | null>(null)
  const [truckId, setTruckId] = useState('truck-005')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    let mounted = true
    void getScenarios().then((available) => {
      if (mounted) setScenarios(available)
    }).catch((reason: unknown) => {
      if (mounted) setError(reason instanceof Error ? reason.message : 'Simulator is not reachable. Check that it is running and try again.')
    })
    void getSimulatorState().then((state) => {
      if (mounted && state.scenario) setActiveScenario(state.scenario)
    }).catch((reason: unknown) => {
      if (mounted) setError(reason instanceof Error ? reason.message : 'Simulator is not reachable. Check that it is running and try again.')
    })
    return () => { mounted = false }
  }, [])

  async function chooseScenario(name: string) {
    if (pending) return
    setPending(true)
    setError('')
    try {
      await applyScenario({ name, ...(name === 'breakdown' ? { truckId } : {}) })
      setActiveScenario(name)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'The simulator could not complete that request. Please try again.')
    } finally {
      setPending(false)
    }
  }

  const active = scenarios.find((scenario) => scenario.name === activeScenario)

  return (
    <GlassPanel title="City scenarios" status={active ? <span className="scenario-panel__active">Active: {active.label}</span> : null}>
      <div className="scenario-panel__controls" aria-busy={pending}>
        {scenarios.map((scenario) => (
          <div className="scenario-panel__option" key={scenario.name}>
            {scenario.name === 'breakdown' && (
              <label className="scenario-panel__truck-picker">
                Truck
                <select aria-label="Breakdown truck" value={truckId} disabled={pending} onChange={(event) => setTruckId(event.target.value)}>
                  {trucks.map((id) => <option key={id} value={id}>{id}</option>)}
                </select>
              </label>
            )}
            <ClayButton
              type="button"
              className={activeScenario === scenario.name ? 'scenario-panel__button scenario-panel__button--active' : 'scenario-panel__button'}
              disabled={pending}
              aria-pressed={activeScenario === scenario.name}
              onClick={() => void chooseScenario(scenario.name)}
            >
              {scenario.label}
            </ClayButton>
            <p>{scenario.description}</p>
          </div>
        ))}
      </div>
      {error && <p className="scenario-panel__error" role="alert">{error}</p>}
      <style>{`
        .scenario-panel__controls { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--space-3); }
        .scenario-panel__option { min-width: 0; }
        .scenario-panel__button { width: 100%; }
        .scenario-panel__button--active { box-shadow: 0 0 0 1px var(--color-cyan), 0 0 18px rgb(34 211 238 / 42%); }
        .scenario-panel__option p { margin: var(--space-2) 0 0; color: var(--color-muted, #aebdca); font-size: 0.85rem; line-height: 1.35; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
        .scenario-panel__active { color: var(--color-cyan); font-size: 0.85rem; text-shadow: 0 0 10px rgb(34 211 238 / 45%); }
        .scenario-panel__truck-picker { display: flex; align-items: center; gap: var(--space-2); margin-bottom: var(--space-2); font-size: 0.8rem; }
        .scenario-panel__truck-picker select { min-width: 0; padding: 0.3rem; color: inherit; background: rgb(15 23 42 / 75%); border: 1px solid rgb(148 163 184 / 35%); border-radius: var(--radius-sm); }
        .scenario-panel__error { margin: var(--space-3) 0 0; color: var(--color-red, #f87171); }
        @media (max-width: 480px) { .scenario-panel__controls { grid-template-columns: 1fr; } }
        @media (prefers-reduced-motion: reduce) { .scenario-panel__button--active { box-shadow: 0 0 0 1px var(--color-cyan); text-shadow: none; } }
      `}</style>
    </GlassPanel>
  )
}
