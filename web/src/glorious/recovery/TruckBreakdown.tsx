import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react'
import { getSimulatorState } from '../../api/client'
import type { SimulatorTruck } from '../../api/types'
import { useAetherMessages } from '../../api/ws'
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
  const [confirming, setConfirming] = useState(false)
  const trigger = useRef<HTMLButtonElement>(null)
  const dialog = useRef<HTMLDivElement>(null)
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
  function submit(event: FormEvent) {
    event.preventDefault()
    if (!truckId || pending) return
    setConfirming(true)
  }
  function closeConfirmation() {
    setConfirming(false)
    requestAnimationFrame(() => trigger.current?.focus())
  }
  function trapFocus(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === 'Escape') { event.preventDefault(); closeConfirmation() }
    if (event.key !== 'Tab') return
    const items = dialog.current?.querySelectorAll<HTMLElement>('button:not(:disabled)')
    if (!items?.length) return
    const first = items[0]!
    const last = items[items.length - 1]!
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
  }
  useEffect(() => {
    if (confirming) requestAnimationFrame(() => dialog.current?.querySelector('button')?.focus())
  }, [confirming])
  async function confirmBreakdown() {
    setConfirming(false)
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
      requestAnimationFrame(() => trigger.current?.focus())
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
    <button ref={trigger} className="gl-clay-button" type="submit" disabled={!truckId || pending}>{pending ? 'Requesting breakdown…' : 'Simulate breakdown'}</button>
    {confirming && <div className="recovery-confirm-backdrop">
      <div ref={dialog} role="alertdialog" aria-modal="true" aria-labelledby="breakdown-confirm-title" className="recovery-confirm" onKeyDown={trapFocus}>
        <p id="breakdown-confirm-title">Simulate a breakdown of Truck {truckId}? It stops for about 15 simulated minutes and resumes by itself.</p>
        <div><button type="button" onClick={() => void confirmBreakdown()}>Confirm</button><button type="button" onClick={closeConfirmation}>Cancel</button></div>
      </div>
    </div>}
    {result && <p role="status">{result}</p>}
    {submitError && <p role="alert">Breakdown failed: {submitError}</p>}
  </form>
}
