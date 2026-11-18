import { useEffect, useState } from 'react'
import type { WebSocketMessage } from './types'

type SocketStatus = 'connecting' | 'connected' | 'disconnected'
type Listener = (status: SocketStatus, message?: WebSocketMessage) => void

let socket: WebSocket | null = null
let retryTimer: ReturnType<typeof setTimeout> | null = null
let retryDelay = 1000
const listeners = new Set<Listener>()

function connect() {
  if (!listeners.size || socket) return
  const scheme = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  const current = new WebSocket(`${scheme}//${window.location.host}/ws`)
  socket = current
  current.onopen = () => {
    retryDelay = 1000
    listeners.forEach((listener) => listener('connected'))
  }
  current.onmessage = (event: MessageEvent<string>) => {
    try {
      const message = JSON.parse(event.data) as WebSocketMessage
      listeners.forEach((listener) => listener('connected', message))
    } catch {
      // Ignore malformed frames; the next server event remains usable.
    }
  }
  current.onerror = () => current.close()
  current.onclose = () => {
    if (socket === current) socket = null
    listeners.forEach((listener) => listener('disconnected'))
    if (listeners.size && retryTimer === null) {
      retryTimer = setTimeout(() => {
        retryTimer = null
        connect()
      }, retryDelay)
      retryDelay = Math.min(retryDelay * 2, 10_000)
    }
  }
}

export function useAetherSocket() {
  const [status, setStatus] = useState<SocketStatus>('connecting')
  const [lastMessage, setLastMessage] = useState<WebSocketMessage | null>(null)
  useEffect(() => {
    const listener: Listener = (nextStatus, message) => {
      setStatus(nextStatus)
      if (message) setLastMessage(message)
    }
    listeners.add(listener)
    connect()
    return () => {
      listeners.delete(listener)
      if (!listeners.size) {
        if (retryTimer !== null) clearTimeout(retryTimer)
        retryTimer = null
        socket?.close()
        socket = null
      }
    }
  }, [])
  return { status, lastMessage }
}
