import type { DeadLetter, WindowResult } from '../../api/types'

export interface TruckWindow {
  truckId: string
  latest: WindowResult
  history: WindowResult[]
}

export function groupTruckWindows(windows: WindowResult[]): TruckWindow[] {
  const grouped = new Map<string, WindowResult[]>()
  for (const window of windows) {
    if (window?.sinkId !== 'speedSink' || !window.key || !Number.isFinite(window.windowStart) || !Number.isFinite(window.windowEnd)) continue
    const rows = grouped.get(window.key) ?? []
    rows.push(window)
    grouped.set(window.key, rows)
  }
  return [...grouped].map(([truckId, history]) => {
    history.sort((a, b) => a.windowEnd - b.windowEnd)
    return { truckId, latest: history[history.length - 1]!, history }
  }).sort((a, b) => a.truckId.localeCompare(b.truckId))
}

export function latestCompletedWindow(windows: WindowResult[], truckId: string): WindowResult | undefined {
  return groupTruckWindows(windows).find((truck) => truck.truckId === truckId)?.latest
}

export interface FailedRecordRow {
  eventId: string
  kind: DeadLetter['kind']
  reason: string
  partition: number
  offset: number
  eventTime: number
}

export function mapFailedRecords(records: DeadLetter[]): FailedRecordRow[] {
  return records.filter((record) => record != null).map(({ eventId, kind, reason, partition, offset, eventTime }) => ({ eventId, kind, reason, partition, offset, eventTime }))
    .sort((a, b) => b.eventTime - a.eventTime)
}

export function windowLengthLabel(window: WindowResult): string {
  const durationMs = window.windowEnd - window.windowStart
  if (!Number.isFinite(durationMs) || durationMs <= 0) return 'completed-window avg'
  if (durationMs % 60_000 === 0) return `${durationMs / 60_000}-min avg`
  if (durationMs % 1_000 === 0) return `${durationMs / 1_000}-sec avg`
  return `${durationMs} ms avg`
}
