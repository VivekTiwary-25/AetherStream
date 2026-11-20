/** Per-poll outage state; one diagnostic per outage, reset only by a successful request. */
export function createBackoff(label: string) {
  let delay = 0
  return {
    failure(error: unknown) {
      if (delay === 0) console.warn(`${label} unavailable; retrying with backoff`, error)
      delay = delay === 0 ? 5_000 : Math.min(delay * 2, 15_000)
      return delay
    },
    success() { delay = 0 },
  }
}
