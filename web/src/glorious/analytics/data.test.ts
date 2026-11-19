import { describe, expect, it } from 'vitest'
import type { DeadLetter, WindowResult } from '../../api/types'
import { groupTruckWindows, latestCompletedWindow, mapFailedRecords } from './data'

const windowRow = (key: string, start: number, end: number, sinkId = 'speedSink'): WindowResult => ({
  sinkId, key, windowStart: start, windowEnd: end, values: { avgSpeedKmh: end / 1000 }, eventCount: 4,
})

describe('analytics data shaping', () => {
  it('groups speed windows by truck and orders each history by completion time', () => {
    const result = groupTruckWindows([windowRow('truck-b', 60_000, 120_000), windowRow('truck-a', 0, 60_000), windowRow('truck-b', 0, 60_000)])
    expect(result.map(({ truckId }) => truckId)).toEqual(['truck-a', 'truck-b'])
    expect(result[1]?.history.map(({ windowEnd }) => windowEnd)).toEqual([60_000, 120_000])
    expect(result[1]?.latest.windowEnd).toBe(120_000)
  })

  it('returns the latest completed window for one truck only', () => {
    const result = latestCompletedWindow([windowRow('truck-a', 0, 60_000), windowRow('truck-a', 60_000, 120_000), windowRow('truck-b', 0, 60_000)], 'truck-a')
    expect(result?.values.avgSpeedKmh).toBe(120)
    expect(latestCompletedWindow([], 'truck-a')).toBeUndefined()
  })

  it('maps failed-record fields and sorts newest simulation event first', () => {
    const failed: DeadLetter[] = [
      { eventId: 'late-1', kind: 'LATE', reason: 'watermark', partition: 2, offset: 8, eventTime: 10 },
      { eventId: 'reject-1', kind: 'REJECTED', reason: 'invalid', partition: 1, offset: 9, eventTime: 20 },
    ]
    expect(mapFailedRecords(failed)).toEqual([
      { eventId: 'reject-1', kind: 'REJECTED', reason: 'invalid', partition: 1, offset: 9, eventTime: 20 },
      { eventId: 'late-1', kind: 'LATE', reason: 'watermark', partition: 2, offset: 8, eventTime: 10 },
    ])
  })
})
