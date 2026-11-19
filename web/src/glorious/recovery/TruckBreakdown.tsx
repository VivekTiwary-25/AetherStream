import { useEffect, useRef, useState, type FormEvent } from 'react'
import { getSimulatorState } from '../../api/client'
import type { SimulatorTruck } from '../../api/types'
import { useAetherMessages } from '../../api/ws'
import { ClayButton } from '../shell/ClayButton'
import { simulateBreakdown } from './useRecoveryFeed'
import './recovery.css'

export function TruckBreakdown() {
  const [trucks, setTrucks] = useState<SimulatorTruck[]>([])
  const [selected, setSelected] = useState('')
  const [loaded, setLoaded] = useState(false)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [pending, setPending] = useState(false)
  const [result, setResult] = useState<string | null>(null)
  const [submitError, setSubmitError] = useState<string | null>(null)
  const revision = useRef(0)
  const submitting = useRef(false)
  const status = useAetherMessages((message) => {
    const data = message.data as { trucks?: SimulatorTruck[]; simulator?: { trucks?: SimulatorTruck[] } } | null
    const fleet = message.type === 'snapshot' ? data?.simulator?.trucks : message.type === 'trucks' ? data?.trucks : undefined
    if (Array.isArray(fleet)) {
      revision.current++
      setTrucks(fleet)
      setLoaded(true)
      setLoadError(null)
    }
  })
  useEffect(() => {
    let active = true
    const version = revision.current
    getSimulatorState().then((simulator) => {
      if (active && revision.current === version) {
        setTrucks(simulator.trucks)
        setLoaded(true)
      }
    }).catch((error: Error) => {
      if (active && revision.current === version) setLoadError(error.message)
    })
    return () => { active = false }
  }, [])
  const truckId = trucks.some((truck) => truck.truckId === selected) ? selected : trucks[0]?.truckId ?? ''
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!truckId || submitting.current) return
    submitting.current = true
    setPending(true)
    setResult(null)
    setSubmitError(null)
    try {
      await simulateBreakdown(truckId)
      setResult(`Breakdown accepted for ${truckId}. Watch its live status on the map.`)
    } catch (error) {
      setSubmitError(error instanceof Error ? error.message : 'Breakdown request failed')
    } finally {
      submitting.current = false
      setPending(false)
    }
  }
  return <form className="recovery-panel recovery-breakdown" onSubmit={submit}>
    <p>Stop a real simulator truck. It resumes automatically after ~15 sim-minutes.</p>
    <p className="recovery-muted">This changes the simulator, not worker health or engine recovery.</p>
    {status !== 'connected' && <p role="status">{status === 'connecting' ? 'Connecting' : 'Disconnected'} · truck statuses may be stale.</p>}
    {loadError && <p role="alert">Truck list unavailable: {loadError}</p>}
    {!trucks.length && <p>{loaded ? 'No trucks reported by the simulator.' : loadError ? 'Truck list could not be loaded.' : 'Loading trucks…'}</p>}
    <label htmlFor="recovery-breakdown-truck">Truck</label>
    <select id="recovery-breakdown-truck" value={truckId} onChange={(event) => setSelected(event.target.value)} disabled={!trucks.length || pending}>
      {!trucks.length && <option value="">No trucks available</option>}
      {[...trucks].sort((a, b) => a.truckId.localeCompare(b.truckId)).map((truck) => <option value={truck.truckId} key={truck.truckId}>{truck.truckId} · {truck.status}</option>)}
    </select>
    <ClayButton type="submit" disabled={!truckId || pending}>{pending ? 'Requesting breakdown…' : 'Simulate breakdown'}</ClayButton>
    {result && <p role="status">{result}</p>}
    {submitError && <p role="alert">Breakdown failed: {submitError}</p>}
  </form>
}
