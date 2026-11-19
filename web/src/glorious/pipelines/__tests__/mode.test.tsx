import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, renderHook, screen } from '@testing-library/react'
import { Sidebar } from '../../shell/Sidebar'
import { useScreen } from '../../shell/router'
import { WindowManager } from '../../shell/WindowManager'
import { PipelinesPanel } from '../PipelinesPanel'

const checkpoint = vi.hoisted(() => ({ mode: 'full' as 'full' | 'editor' | 'hidden' }))
vi.mock('../mode', () => ({ get PIPELINES_MODE() { return checkpoint.mode } }))

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  window.history.replaceState(null, '', '/')
})

describe.each(['full', 'editor', 'hidden'] as const)('Pipelines checkpoint: %s', (mode) => {
  beforeEach(() => {
    checkpoint.mode = mode
    window.history.replaceState(null, '', '/')
  })

  it('shows the selected panel controls and keeps editor-only mode usable', () => {
    const open = vi.spyOn(window, 'open').mockReturnValue(null)
    render(<WindowManager><PipelinesPanel /></WindowManager>)
    if (mode === 'hidden') {
      expect(screen.queryByTestId('pipelines-panel')).toBeNull()
      expect(screen.queryByTestId('open-editor')).toBeNull()
      return
    }
    fireEvent.click(screen.getByTestId('open-editor'))
    expect(open).toHaveBeenCalledWith('/pipelines/editor', '_blank', 'noopener')
    if (mode === 'full') {
      expect(screen.getByRole('heading', { name: 'Ask AI' })).toBeTruthy()
      expect(screen.getByRole('textbox')).toBeTruthy()
      expect(screen.getByRole('group', { name: 'Example prompts' })).toBeTruthy()
      expect(screen.getByRole('button', { name: 'Generate' })).toBeTruthy()
    } else {
      expect(screen.getByText('Build and run a pipeline manually in the editor.')).toBeTruthy()
      expect(screen.queryByRole('heading', { name: 'Ask AI' })).toBeNull()
      expect(screen.queryByRole('textbox')).toBeNull()
      expect(screen.queryByRole('group', { name: 'Example prompts' })).toBeNull()
      expect(screen.queryByRole('button', { name: 'Generate' })).toBeNull()
    }
  })

  it('hides only the Pipelines sidebar destination when hidden', () => {
    const navigate = vi.fn()
    render(<WindowManager><Sidebar screen="home" navigate={navigate} /></WindowManager>)
    expect(screen.getByRole('button', { name: 'Home' })).toBeTruthy()
    if (mode === 'hidden') {
      expect(screen.queryByRole('button', { name: 'Pipelines' })).toBeNull()
    } else {
      fireEvent.click(screen.getByRole('button', { name: 'Pipelines' }))
      expect(navigate).toHaveBeenCalledWith('pipelines')
    }
  })

  it.each([
    ['/pipelines', 'pipelines'],
    ['/pipelines/editor', 'editor'],
    ['/pipelines/editor/', 'editor'],
  ] as const)('resolves direct access to %s', (path, destination) => {
    window.history.replaceState(null, '', `${path}?checkpoint=1`)
    const { result } = renderHook(useScreen)
    expect(result.current.screen).toBe(mode === 'hidden' ? 'home' : destination)
    expect(window.location.pathname).toBe(mode === 'hidden' ? '/' : path)
    expect(window.location.search).toBe('?checkpoint=1')
  })

  it.each(['pipelines', 'editor'] as const)('guards navigation to %s from Live World', (destination) => {
    window.history.replaceState(null, '', '/live')
    const { result } = renderHook(useScreen)
    act(() => result.current.navigate(destination))
    expect(result.current.screen).toBe(mode === 'hidden' ? 'home' : destination)
    expect(window.location.pathname).toBe(mode === 'hidden' ? '/' : destination === 'editor' ? '/pipelines/editor' : '/pipelines')
  })

  it.each(['/pipelines', '/pipelines/editor'])('guards history navigation to %s', (path) => {
    const { result } = renderHook(useScreen)
    act(() => {
      window.history.pushState(null, '', path)
      window.dispatchEvent(new PopStateEvent('popstate'))
    })
    expect(result.current.screen).toBe(mode === 'hidden' ? 'home' : path.endsWith('/editor') ? 'editor' : 'pipelines')
    expect(window.location.pathname).toBe(mode === 'hidden' ? '/' : path)
  })
})
