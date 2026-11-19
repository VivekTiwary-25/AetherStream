import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, renderHook } from '@testing-library/react'
import { useAetherSocket } from '../ws'

class MockWebSocket {
  static instances: MockWebSocket[] = []
  onopen: (() => void) | null = null
  onmessage: ((event: MessageEvent<string>) => void) | null = null
  onerror: (() => void) | null = null
  onclose: (() => void) | null = null
  closed = false

  constructor(readonly url: string) {
    MockWebSocket.instances.push(this)
  }

  close() {
    this.closed = true
    this.onclose?.()
  }
}

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.unstubAllGlobals()
  MockWebSocket.instances = []
})

describe('useAetherSocket', () => {
  it('connects, delivers valid messages, ignores malformed frames and closes on errors', () => {
    vi.stubGlobal('WebSocket', MockWebSocket)
    const { result, unmount } = renderHook(() => useAetherSocket())
    const socket = MockWebSocket.instances[0]

    expect(socket.url).toBe(`ws://${window.location.host}/ws`)
    act(() => socket.onopen?.())
    expect(result.current.status).toBe('connected')
    act(() => socket.onmessage?.({ data: '{"type":"snapshot","ts":1,"data":{}}' } as MessageEvent<string>))
    expect(result.current.lastMessage).toEqual({ type: 'snapshot', ts: 1, data: {} })
    act(() => socket.onmessage?.({ data: '{bad json' } as MessageEvent<string>))
    expect(result.current.lastMessage).toEqual({ type: 'snapshot', ts: 1, data: {} })
    act(() => socket.onerror?.())
    expect(socket.closed).toBe(true)
    expect(result.current.status).toBe('disconnected')

    unmount()
  })

  it('reconnects with exponential backoff and cancels pending reconnect on unmount', () => {
    vi.useFakeTimers()
    vi.stubGlobal('WebSocket', MockWebSocket)
    const { unmount } = renderHook(() => useAetherSocket())
    const first = MockWebSocket.instances[0]

    act(() => first.onopen?.())
    act(() => first.onclose?.())
    expect(MockWebSocket.instances).toHaveLength(1)
    act(() => vi.advanceTimersByTime(999))
    expect(MockWebSocket.instances).toHaveLength(1)
    act(() => vi.advanceTimersByTime(1))
    expect(MockWebSocket.instances).toHaveLength(2)

    act(() => MockWebSocket.instances[1].onclose?.())
    act(() => vi.advanceTimersByTime(1999))
    expect(MockWebSocket.instances).toHaveLength(2)
    act(() => vi.advanceTimersByTime(1))
    expect(MockWebSocket.instances).toHaveLength(3)

    act(() => MockWebSocket.instances[2].onclose?.())
    unmount()
    act(() => vi.advanceTimersByTime(10_000))
    expect(MockWebSocket.instances).toHaveLength(3)
    expect(vi.getTimerCount()).toBe(0)
  })
})
