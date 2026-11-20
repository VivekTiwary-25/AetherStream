import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { WindowManager } from '../../shell/WindowManager'
import { PipelinesPanel } from '../PipelinesPanel'
import { EXAMPLES } from '../examples'
import { EditorPage } from '../EditorPage'

class FakeResizeObserver { observe() {} unobserve() {} disconnect() {} }

const respond = (status: number, body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }))

describe('PipelinesPanel', () => {
  afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

  it('fills the prompt from an example chip and opens the editor in a new tab', () => {
    const open = vi.spyOn(window, 'open').mockReturnValue(null)
    render(<WindowManager><PipelinesPanel /></WindowManager>)
    fireEvent.click(screen.getByRole('button', { name: EXAMPLES[1]!.prompt }))
    expect((screen.getByTestId('ai-prompt') as HTMLTextAreaElement).value).toBe(EXAMPLES[1]!.prompt)
    fireEvent.click(screen.getByTestId('open-editor'))
    expect(open).toHaveBeenCalledWith('/pipelines/editor', '_blank', 'noopener')
  })

  it('shows the fallback note and the review, and validation errors when Run is rejected', async () => {
    vi.stubGlobal('ResizeObserver', FakeResizeObserver)
    const fetchMock = vi.fn((url: string) => url.includes('/ai/generate')
      ? respond(200, { pipeline: EXAMPLES[0]!.pipeline, source: 'example', note: 'Showing a saved example: AI was unavailable', ms: 5 })
      : respond(400, { valid: false, errors: [{ nodeId: 'n2', message: 'bad op' }] }))
    vi.stubGlobal('fetch', fetchMock)
    render(<WindowManager><PipelinesPanel /></WindowManager>)
    fireEvent.change(screen.getByTestId('ai-prompt'), { target: { value: 'speeding' } })
    await act(async () => { fireEvent.click(screen.getByTestId('ai-generate')) })
    expect(screen.getByText('Showing a saved example: AI was unavailable')).toBeTruthy()
    expect(screen.getByText('keep events where speedKmh > 60')).toBeTruthy()
    await act(async () => { fireEvent.click(screen.getByTestId('pipeline-run')) })
    expect(screen.getByRole('alert').textContent).toContain('n2: bad op')
    expect(screen.queryByTestId('pipeline-results')).toBeNull()
  })

  it('says the AI service is unavailable on 502', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response('<html>Bad gateway</html>', { status: 502 }))))
    render(<WindowManager><PipelinesPanel /></WindowManager>)
    fireEvent.change(screen.getByTestId('ai-prompt'), { target: { value: 'x' } })
    await act(async () => { fireEvent.click(screen.getByTestId('ai-generate')) })
    expect(screen.getByRole('alert').textContent).toContain('AI service unavailable')
  })
})

describe('EditorPage', () => {
  afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); window.history.replaceState(null, '', '/') })

  it('loads the AI hand-off from localStorage and shows validation errors on the node', async () => {
    vi.stubGlobal('ResizeObserver', FakeResizeObserver)
    vi.stubGlobal('fetch', vi.fn((url: string) => url.endsWith('/fields')
      ? Promise.resolve(new Response('', { status: 502 }))
      : respond(200, { valid: false, errors: [{ nodeId: 'n2', message: 'value must be a number' }] })))
    window.localStorage.setItem('glorious.pipelines.fromAi', JSON.stringify(EXAMPLES[0]!.pipeline))
    window.history.replaceState(null, '', '/pipelines/editor?from=ai')
    await act(async () => { render(<WindowManager><EditorPage /></WindowManager>) })
    expect((screen.getByLabelText('Pipeline name') as HTMLInputElement).value).toBe('Speeding alert')
    expect(screen.getByText(/Pipeline service unavailable: field list comes from the shared contract/)).toBeTruthy()
    expect(screen.getAllByRole('button', { name: /^Add / }).map((button) => button.textContent)).toEqual(['Add Filter', 'Add Pick Field', 'Add Window Average', 'Add Threshold Alert', 'Add Output'])
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Validate' })) })
    expect(screen.getByLabelText('Errors on n2').textContent).toContain('value must be a number')
  })
})
