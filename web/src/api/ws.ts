import { useEffect, useRef, useState } from 'react'
import type { WebSocketMessage } from './types'

export type SocketStatus = 'connecting' | 'connected' | 'disconnected'
export type MessageListener = (message: WebSocketMessage) => void
export type StatusListener = (status: SocketStatus) => void

interface Subscriber {
  onStatus?: StatusListener
  onMessage?: MessageListener
}

// One shared socket for the whole page. Every frame is delivered synchronously, in arrival order, to every
// subscriber: frames that arrive within one React batch are never collapsed into a single "last message".
let socket: WebSocket | null = null
let retryTimer: ReturnType<typeof setTimeout> | null = null
let retryDelay = 1000
let currentStatus: SocketStatus = 'connecting'
const subscribers = new Set<Subscriber>()

function setStatus(status: SocketStatus) {
  currentStatus = status
  ;[...subscribers].forEach((subscriber) => subscriber.onStatus?.(status))
}

function deliver(message: WebSocketMessage) {
  ;[...subscribers].forEach((subscriber) => {
    if (!subscriber.onMessage) return
    try {
      subscriber.onMessage(message)
    } catch (error) {
      // One faulty consumer must not stop later consumers (or later frames) from being processed.
      console.error('WebSocket message listener failed', error)
    }
  })
}

function connect() {
  if (!subscribers.size || socket) return
  const scheme = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  const current = new WebSocket(`${scheme}//${window.location.host}/ws`)
  socket = current
  current.onopen = () => {
    retryDelay = 1000
    setStatus('connected')
  }
  current.onmessage = (event: MessageEvent<string>) => {
    let message: WebSocketMessage
    try {
      message = JSON.parse(event.data) as WebSocketMessage
    } catch {
      return // Ignore malformed frames; the next server event remains usable.
    }
    if (message === null || typeof message !== 'object') return
    deliver(message)
  }
  current.onerror = () => current.close()
  current.onclose = () => {
    if (socket === current) socket = null
    setStatus('disconnected')
    if (subscribers.size && retryTimer === null) {
      retryTimer = setTimeout(() => {
        retryTimer = null
        connect()
      }, retryDelay)
      retryDelay = Math.min(retryDelay * 2, 10_000)
    }
  }
}

function subscribe(subscriber: Subscriber): () => void {
  subscribers.add(subscriber)
  connect()
  return () => {
    subscribers.delete(subscriber)
    if (subscribers.size) return
    if (retryTimer !== null) clearTimeout(retryTimer)
    retryTimer = null
    retryDelay = 1000
    const closing = socket
    socket = null
    if (closing) {
      closing.onclose = null
      closing.close()
    }
    currentStatus = 'connecting'
  }
}

/** Calls `listener` for every WebSocket frame, in arrival order. Returns an unsubscribe function. */
export function subscribeMessages(listener: MessageListener): () => void {
  return subscribe({ onMessage: listener })
}

/** Calls `listener` on every connection status change. Returns an unsubscribe function. */
export function subscribeStatus(listener: StatusListener): () => void {
  return subscribe({ onStatus: listener })
}

export function getSocketStatus(): SocketStatus {
  return currentStatus
}

/**
 * Subscribes a component to every WebSocket frame (processed one by one, in order) and returns the connection status.
 * `onMessage` may change between renders; the latest one is always used.
 */
export function useAetherMessages(onMessage: MessageListener): SocketStatus {
  const [status, setStatusState] = useState<SocketStatus>(currentStatus)
  const handler = useRef(onMessage)
  useEffect(() => {
    handler.current = onMessage
  })
  useEffect(() => {
    const unsubscribe = subscribe({
      onStatus: setStatusState,
      onMessage: (message) => handler.current(message),
    })
    setStatusState(currentStatus)
    return unsubscribe
  }, [])
  return status
}

/**
 * Legacy hook: status plus the most recent frame only. Frames arriving in one React batch collapse to the last one,
 * so consumers that must see every frame (state machines, counters) should use `useAetherMessages` instead.
 */
export function useAetherSocket() {
  const [lastMessage, setLastMessage] = useState<WebSocketMessage | null>(null)
  const status = useAetherMessages(setLastMessage)
  return { status, lastMessage }
}
