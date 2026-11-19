import type { ComponentProps } from 'react'
import type * as WindowManagerModule from './WindowManager'
import type { WindowsApi } from './windows'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { GloriousApp } from '../GloriousApp'
import { useWindows } from './windows'

// jsdom has no WebGL renderer. Exercise the real shell, menus and windows.
vi.mock('../map/LiveWorld', () => ({ LiveWorld: () => null }))
vi.mock('../../api/ws', () => ({ useAetherMessages: () => 'disconnected' }))

let windowsApi: WindowsApi
function CaptureWindows() {
  windowsApi = useWindows()
  return null
}

vi.mock('./WindowManager', async (importOriginal) => {
  const { WindowManager } = await importOriginal<typeof WindowManagerModule>()
  return {
    WindowManager: (props: ComponentProps<typeof WindowManager>) => <WindowManager {...props}>
      <CaptureWindows />{props.children}
    </WindowManager>,
  }
})

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new Error('Service unavailable'))))
  vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: false, addEventListener() {}, removeEventListener() {} })))
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  window.history.replaceState(null, '', '/')
})

describe('map-backed navigation', () => {
  it.each([
    ['/', 'Analytics', 'Truck Speeds'],
    ['/pipelines', 'Analytics', 'Truck Speeds'],
    ['/', 'Settings', 'Logs & Recovery'],
    ['/pipelines', 'Settings', 'Logs & Recovery'],
  ])('opens %s → %s → %s over Live World', async (path, section, option) => {
    window.history.replaceState(null, '', `${path}?city=mysuru`)
    const push = vi.spyOn(window.history, 'pushState')
    render(<GloriousApp />)

    fireEvent.click(screen.getByRole('button', { name: section }))
    expect(window.location.pathname).toBe('/live')
    expect(window.location.search).toBe('?city=mysuru')
    fireEvent.click(await screen.findByRole('button', { name: option }))

    expect(screen.getByRole('dialog', { name: option })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Back to main navigation' })).toBeTruthy()
    expect(push).toHaveBeenCalledTimes(1)
  })

  it('keeps the existing window and sidebar sub-options when switching from Pipelines', async () => {
    render(<GloriousApp />)
    fireEvent.click(screen.getByRole('button', { name: 'Analytics' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Truck Speeds' }))
    const speeds = screen.getByRole('dialog', { name: 'Truck Speeds' })

    vi.useFakeTimers()
    fireEvent.click(screen.getByRole('button', { name: 'Back to main navigation' }))
    act(() => vi.runOnlyPendingTimers())
    vi.useRealTimers()
    fireEvent.click(screen.getByRole('button', { name: 'Pipelines' }))
    expect(window.location.pathname).toBe('/pipelines')
    fireEvent.click(screen.getByRole('button', { name: 'Settings' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Logs & Recovery' }))

    expect(window.location.pathname).toBe('/live')
    expect(screen.getByRole('dialog', { name: 'Truck Speeds' })).toBe(speeds)
    expect(screen.getByRole('dialog', { name: 'Logs & Recovery' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Truck breakdown' })).toBeTruthy()
  })

  it.each([
    ['/', 'analytics.custom'],
    ['/pipelines', 'settings.custom'],
  ])('routes programmatic windows from %s to the map (%s)', (path, id) => {
    window.history.replaceState(null, '', path)
    render(<GloriousApp />)
    act(() => windowsApi.open(id, { title: 'Track window', render: () => null }))
    expect(window.location.pathname).toBe('/live')
    expect(screen.getByRole('dialog', { name: 'Track window' })).toBeTruthy()
  })

  it('keeps unrelated windows on their current screen', () => {
    window.history.replaceState(null, '', '/pipelines')
    render(<GloriousApp />)
    act(() => windowsApi.open('pipelines.results', { title: 'Pipeline results', render: () => null }))
    expect(window.location.pathname).toBe('/pipelines')
    expect(screen.getByRole('dialog', { name: 'Pipeline results' })).toBeTruthy()
  })
})

describe('phone navigation and sheets', () => {
  beforeEach(() => {
    vi.stubGlobal('matchMedia', vi.fn((query: string) => ({
      matches: query === '(max-width: 768px)', addEventListener() {}, removeEventListener() {},
    })))
  })

  it('keeps main destinations available and returns keyboard focus from sub-options', () => {
    render(<GloriousApp />)
    const main = within(screen.getByRole('navigation', { name: 'Main navigation' }))
    fireEvent.click(main.getByRole('button', { name: 'Analytics' }))
    expect(main.getByRole('button', { name: 'Home' })).toBeTruthy()
    expect(main.getByRole('button', { name: 'Pipelines' })).toBeTruthy()
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Back to main navigation' }))
    fireEvent.keyDown(document.activeElement!, { key: 'Escape' })
    expect(screen.queryByRole('navigation', { name: 'Analytics options' })).toBeNull()
    expect(document.activeElement).toBe(main.getByRole('button', { name: 'Analytics' }))
    fireEvent.click(main.getByRole('button', { name: 'Settings' }))
    fireEvent.click(screen.getByRole('button', { name: 'Back to main navigation' }))
    expect(document.activeElement).toBe(main.getByRole('button', { name: 'Settings' }))
  })

  it('dismisses the option sheet when opening a window', () => {
    render(<GloriousApp />)
    fireEvent.click(screen.getByRole('button', { name: 'Analytics' }))
    fireEvent.click(screen.getByRole('button', { name: 'Truck Speeds' }))
    expect(screen.queryByRole('navigation', { name: 'Analytics options' })).toBeNull()
    expect(screen.getByRole('dialog', { name: 'Truck Speeds' }).contains(document.activeElement)).toBe(true)
  })

  it('exposes only the newest sheet, preserves hidden state, and supports back and Escape', () => {
    render(<GloriousApp />)
    act(() => windowsApi.open('first', { title: 'First', render: () => <input aria-label="Draft" defaultValue="kept" /> }))
    const first = screen.getByRole('dialog', { name: 'First' })
    fireEvent.change(screen.getByRole('textbox', { name: 'Draft' }), { target: { value: 'edited' } })
    act(() => windowsApi.open('second', {
      title: 'Details', onBack: () => windowsApi.update('second', { title: 'List', onBack: undefined }),
      render: () => <p>Second sheet</p>,
    }))
    expect(screen.queryByRole('dialog', { name: 'First' })).toBeNull()
    expect(first.hasAttribute('inert')).toBe(true)
    fireEvent.click(screen.getByRole('button', { name: /^Back$/ }))
    expect(screen.getByRole('dialog', { name: 'List' })).toBeTruthy()
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(screen.getByRole('dialog', { name: 'First' })).toBe(first)
    expect((screen.getByRole('textbox', { name: 'Draft' }) as HTMLInputElement).value).toBe('edited')
    fireEvent.click(screen.getByRole('button', { name: 'Close First' }))
    expect(screen.queryByRole('dialog')).toBeNull()
  })
})
