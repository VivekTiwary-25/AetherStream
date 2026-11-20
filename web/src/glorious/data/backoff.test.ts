import { afterEach, describe, expect, it, vi } from 'vitest'
import { createBackoff } from './backoff'

afterEach(() => vi.restoreAllMocks())

describe('outage backoff', () => {
  it('bounds failing retries between five and fifteen seconds without repeated diagnostics', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const retry = createBackoff('Fleet')
    const error = new Error('offline')
    expect(Array.from({ length: 7 }, () => retry.failure(error))).toEqual([5000, 10000, 15000, 15000, 15000, 15000, 15000])
    expect(warn).toHaveBeenCalledTimes(1)
    expect(warn).toHaveBeenCalledWith('Fleet unavailable; retrying with backoff', error)
  })

  it('resets the delay and diagnostic after success without coupling independent endpoints', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const fleet = createBackoff('Fleet')
    const history = createBackoff('History')
    fleet.failure('offline')
    fleet.failure('offline')
    history.failure('offline')
    fleet.success()
    expect(fleet.failure('new outage')).toBe(5000)
    expect(history.failure('same outage')).toBe(10000)
    expect(warn).toHaveBeenCalledTimes(3)
  })
})
