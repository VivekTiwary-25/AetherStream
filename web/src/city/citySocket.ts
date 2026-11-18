import type { CityEnvelope } from './cityTypes'

export function subscribeCity(onMessage: (message: CityEnvelope<unknown>) => void): () => void {
  let socket: WebSocket | undefined
  let timer: ReturnType<typeof setTimeout> | undefined
  let delay = 1000
  let closed = false

  const connect = () => {
    if (closed) return
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    socket = new WebSocket(`${protocol}//${window.location.host}/ws`)
    socket.onopen = () => { delay = 1000 }
    socket.onmessage = (event) => {
      try {
        const message = JSON.parse(event.data) as CityEnvelope<unknown>
        if (message && typeof message.type === 'string') onMessage(message)
      } catch {
        // Ignore malformed frames; the next valid stream update restores current state.
      }
    }
    socket.onclose = () => {
      if (closed) return
      timer = setTimeout(connect, delay)
      delay = Math.min(delay * 2, 10_000)
    }
    socket.onerror = () => socket?.close()
  }

  connect()
  return () => {
    closed = true
    if (timer) clearTimeout(timer)
    socket?.close()
  }
}
