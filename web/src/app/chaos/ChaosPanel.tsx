import { useMemo, useState } from 'react'
import './chaos.css'
import { useDashboard } from '../state/store'
import { ClayButton } from '../ui/ClayButton'
import { GlassPanel } from '../ui/GlassPanel'
import { StatusPill } from '../ui/StatusPill'
import { getWorkers, killWorker, sendDuplicates, sendLateEvents, sendMalformed, setSlowSink, type Worker } from './chaosApi'
export function ChaosPanel() {
  const { workers } = useDashboard()
  const backpressured = workers.some((worker) => worker?.alive && worker?.backpressured)
  const [pending, setPending] = useState(false)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [delayMs, setDelayMs] = useState<0 | 50 | 200>(0)
  const [workerList, setWorkerList] = useState<Worker[]>([])
  const [workerChoice, setWorkerChoice] = useState('busiest')
  const [confirmKill, setConfirmKill] = useState(false)

  const busiestWorker = useMemo(() => [...workerList].sort((a, b) => b.partitions.length - a.partitions.length)[0], [workerList])
  const workerLabel = workerChoice === 'busiest' ? busiestWorker?.workerId ?? 'busiest' : workerChoice

  async function perform<T>(action: () => Promise<T>, success: (result: T) => string) {
    setPending(true)
    setError('')
    setMessage('')
    try {
      setMessage(success(await action()))
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Something went wrong. Please try again.')
    } finally {
      setPending(false)
    }
  }

  async function chooseDelay(nextDelay: 0 | 50 | 200) {
    if (pending || nextDelay === delayMs) return
    setPending(true)
    setError('')
    setMessage('')
    try {
      await setSlowSink(nextDelay)
      setDelayMs(nextDelay)
      setMessage(nextDelay === 0 ? 'Output speed restored.' : `Output slowed to ${nextDelay} ms per record.`)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Could not change output speed. Please try again.')
    } finally {
      setPending(false)
    }
  }

  async function openKillConfirmation() {
    setConfirmKill(true)
    setError('')
    setMessage('')
    setPending(true)
    try {
      setWorkerList(await getWorkers())
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Could not load workers. Please try again.')
    } finally {
      setPending(false)
    }
  }

  async function confirmWorkerKill() {
    await perform(() => killWorker(workerChoice === 'busiest' ? null : workerChoice), (result) => {
      setConfirmKill(false)
      return `Worker ${result.workerId} killed.`
    })
  }

  return (
    <GlassPanel title="Chaos controls" status={<StatusPill state={backpressured ? 'backpressured' : 'healthy'} />}>
      <div className="chaos-panel__actions">
        <ClayButton disabled={pending} onClick={() => void perform(sendLateEvents, () => '20 late events sent')}>
          Send late events
        </ClayButton>
        <div className="chaos-panel__secondary">
          <ClayButton variant="ghost" disabled={pending} onClick={() => void perform(sendDuplicates, () => '20 duplicate events sent')}>
            Send duplicates (20)
          </ClayButton>
          <ClayButton variant="ghost" disabled={pending} onClick={() => void perform(sendMalformed, () => '5 malformed events sent')}>
            Send malformed (5)
          </ClayButton>
        </div>
        <label className={`chaos-panel__delay${delayMs ? ' chaos-panel__delay--active' : ''}`}>
          <span>Slow down output</span>
          <select aria-label="Slow down output" disabled={pending} value={delayMs} onChange={(event) => void chooseDelay(Number(event.target.value) as 0 | 50 | 200)}>
            <option value={0}>Off</option>
            <option value={50}>50 ms per record</option>
            <option value={200}>200 ms per record</option>
          </select>
        </label>
        {delayMs > 0 && <p className="chaos-panel__hint">Output slowed: queues fill up (bounded at 1,000) and the engine pushes back.</p>}
        {!confirmKill ? (
          <ClayButton variant="danger" disabled={pending} onClick={() => void openKillConfirmation()}>
            Kill worker
          </ClayButton>
        ) : (
          <div className="chaos-panel__confirm" aria-label="Confirm worker kill">
            <p>Kill worker {workerLabel}? It will crash without warning.</p>
            <label>
              Worker
              <select aria-label="Worker to kill" value={workerChoice} disabled={pending} onChange={(event) => setWorkerChoice(event.target.value)}>
                <option value="busiest">Busiest worker ({busiestWorker?.workerId ?? 'loading'})</option>
                {workerList.map((worker) => <option key={worker.workerId} value={worker.workerId}>{worker.workerId}</option>)}
              </select>
            </label>
            <div className="chaos-panel__secondary">
              <ClayButton variant="danger" disabled={pending} onClick={() => void confirmWorkerKill()}>Confirm kill</ClayButton>
              <ClayButton variant="ghost" disabled={pending} onClick={() => setConfirmKill(false)}>Cancel</ClayButton>
            </div>
          </div>
        )}
        {message && <p className="chaos-panel__notice" role="status">{message}</p>}
        {error && <p className="chaos-panel__error" role="alert">{error}</p>}
      </div>
    </GlassPanel>
  )
}
