import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen, type RenderResult } from '@testing-library/react'
import { WindowManager } from '../../shell/WindowManager'
import { PipelinesPanel } from '../PipelinesPanel'
import { EXAMPLES } from '../examples'
import { ResultsView } from '../ResultsView'
import { useRunPipeline } from '../useRunPipeline'
import { EditorPage } from '../EditorPage'

class FakeResizeObserver { observe() {} unobserve() {} disconnect() {} }

const respond = (status: number, body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }))

function RunButton() {
  const runner = useRunPipeline()
  return <button onClick={() => void runner.run(EXAMPLES[0]!.pipeline)} disabled={runner.running}>Run test pipeline</button>
}

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
  afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); window.sessionStorage.clear(); window.history.replaceState(null, '', '/') })

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

  it.each(['refresh', 'back'] as const)('restores this tab’s edited pipeline after %s', async (navigation) => {
    vi.stubGlobal('ResizeObserver', FakeResizeObserver)
    vi.stubGlobal('fetch', vi.fn(() => respond(200, { fields: [] })))
    window.localStorage.setItem('glorious.pipelines.fromAi', JSON.stringify(EXAMPLES[0]!.pipeline))
    window.history.replaceState(null, '', '/pipelines/editor?from=ai')
    let editor: RenderResult
    await act(async () => { editor = render(<WindowManager><EditorPage /></WindowManager>) })
    fireEvent.change(screen.getByLabelText('Pipeline name'), { target: { value: 'My edited pipeline' } })
    fireEvent.click(screen.getByRole('button', { name: 'Add Pick Field' }))
    editor!.unmount()
    // A later handoff from another tab must not replace this editor’s draft on refresh.
    window.localStorage.setItem('glorious.pipelines.fromAi', JSON.stringify(EXAMPLES[1]!.pipeline))
    if (navigation === 'back') {
      window.history.replaceState(null, '', '/pipelines')
      window.history.replaceState(null, '', '/pipelines/editor')
    }
    await act(async () => { render(<WindowManager><EditorPage /></WindowManager>) })
    expect((screen.getByLabelText('Pipeline name') as HTMLInputElement).value).toBe('My edited pipeline')
    expect(screen.getByText('n4')).toBeTruthy()
  })

  it.each([200, 400])('ignores an outdated validation response (%s) after editing', async (status) => {
    vi.stubGlobal('ResizeObserver', FakeResizeObserver)
    let finish!: (response: Response) => void
    // The project targets ES2022, which does not include Promise.withResolvers.
    vi.stubGlobal('fetch', vi.fn((url: string) => url.endsWith('/fields')
      ? respond(200, { fields: [] })
      : new Promise<Response>((resolve) => { finish = resolve })))
    window.localStorage.setItem('glorious.pipelines.fromAi', JSON.stringify(EXAMPLES[0]!.pipeline))
    window.history.replaceState(null, '', '/pipelines/editor?from=ai')
    await act(async () => { render(<WindowManager><EditorPage /></WindowManager>) })
    fireEvent.click(screen.getByRole('button', { name: 'Validate' }))
    fireEvent.change(screen.getByLabelText('Pipeline name'), { target: { value: 'Changed during validation' } })
    await act(async () => {
      finish(await respond(status, status === 200 ? { valid: true, errors: [] } : { valid: false, errors: [{ nodeId: 'n2', message: 'outdated validation error' }] }))
    })
    expect(screen.queryByText('Valid.')).toBeNull()
    expect(screen.queryByText(/outdated validation error/)).toBeNull()
    expect((screen.getByRole('button', { name: 'Validate' }) as HTMLButtonElement).disabled).toBe(false)
  })
})

describe('pipeline results lifecycle', () => {
  afterEach(() => { cleanup(); vi.useRealTimers(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

  it.each([0, 200])('opens the new run when the old window closes during start (%s ms)', async (closingDelay) => {
    vi.useFakeTimers()
    vi.stubGlobal('ResizeObserver', FakeResizeObserver)
    let finish!: (response: Response) => void
    // The project targets ES2022, which does not include Promise.withResolvers.
    let starts = 0
    const fetchMock = vi.fn((url: string) => {
      if (url.endsWith('/runs')) {
        starts++
        return starts === 1 ? respond(200, { runId: 'r1' }) : new Promise<Response>((resolve) => { finish = resolve })
      }
      return respond(200, { runId: url.includes('/r2/') ? 'r2' : 'r1', state: 'STOPPED', eventsIn: url.includes('/r2/') ? 22 : 11, results: [] })
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<div className="glorious-app"><WindowManager><RunButton /></WindowManager></div>)
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Run test pipeline' })) })
    expect(screen.getByText('11')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Run test pipeline' }))
    fireEvent.click(screen.getByRole('button', { name: 'Close Pipeline results - Speeding alert' }))
    await act(async () => { vi.advanceTimersByTime(closingDelay) })
    await act(async () => { finish(await respond(200, { runId: 'r2' })) })
    expect(screen.getByRole('dialog', { name: 'Pipeline results - Speeding alert' })).toBeTruthy()
    expect(screen.getByText('22')).toBeTruthy()
    expect(screen.queryByText('11')).toBeNull()
  })

  it('marks failed polling as disconnected and recovers without duplicating rows or leaking timers', async () => {
    vi.useFakeTimers()
    let polls = 0
    const fetchMock = vi.fn(() => {
      polls++
      return polls === 2
        ? respond(502, { error: 'unavailable' })
        : respond(200, { runId: 'r1', state: 'RUNNING', eventsIn: polls, results: [{ seq: 0, ts: 1, output: 'Live output', kind: 'row', data: { truckId: 'Truck A' } }] })
    })
    vi.stubGlobal('fetch', fetchMock)
    let results: RenderResult
    await act(async () => { results = render(<ResultsView runId="r1" />) })
    expect(screen.getByText('Running')).toBeTruthy()
    await act(async () => { vi.advanceTimersByTime(1000) })
    expect(screen.getByText('Disconnected')).toBeTruthy()
    expect(screen.queryByText('Running')).toBeNull()
    await act(async () => { vi.advanceTimersByTime(1000) })
    expect(screen.getByText('Running')).toBeTruthy()
    expect(screen.queryByText('Disconnected')).toBeNull()
    expect(screen.getAllByText('Truck A')).toHaveLength(1)
    results!.unmount()
    await act(async () => { vi.advanceTimersByTime(5000) })
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })
})
