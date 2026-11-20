import type { ComponentProps } from 'react'
import type * as WindowManagerModule from './WindowManager'
import type { WindowsApi } from './windows'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
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

    fireEvent.click(screen.getByRole('button', { name: 'Back to main navigation' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Pipelines' }))
    expect(window.location.pathname).toBe('/pipelines')
    fireEvent.click(screen.getByRole('button', { name: 'Settings' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Logs & Recovery' }))

    expect(window.location.pathname).toBe('/live')
    expect(screen.getByRole('dialog', { name: 'Truck Speeds' })).toBe(speeds)
    expect(screen.getByRole('dialog', { name: 'Logs & Recovery' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Truck Breakdown' })).toBeTruthy()
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
