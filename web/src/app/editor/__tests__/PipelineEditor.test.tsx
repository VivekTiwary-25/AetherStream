import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import PipelineEditor from '../PipelineEditor'

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

const examples = [
  { id: 'logistics-demo', name: 'Logistics demo', description: 'Fleet logistics' },
  { id: 'speeding-alerts', name: 'Speeding alerts', description: 'Speed alerts' },
  { id: 'fleet-activity', name: 'Fleet activity', description: 'Fleet activity' },
  { id: 'broken-for-demo', name: 'Broken for demo', description: 'Three mistakes' },
]

const brokenPipeline = {
  id: 'broken-for-demo', name: 'Broken for demo',
  nodes: ['source', 'filter', 'keyBy'].map((id) => ({ id, type: 'filter', position: { x: 0, y: 0 }, config: {} })),
  edges: [{ from: 'source', to: 'filter' }, { from: 'filter', to: 'keyBy' }],
}

function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('PipelineEditor', () => {
  it('keeps Run disabled until validation succeeds', () => {
    vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })
    vi.stubGlobal('fetch', vi.fn(async () => response(examples)))
    render(<PipelineEditor open onClose={() => undefined} />)
    expect((screen.getByRole('button', { name: 'Run' }) as HTMLButtonElement).disabled).toBe(true)
  })

  it('lists the four examples and loads then validates broken-for-demo', async () => {
    vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.endsWith('/examples')) return response(examples)
      if (url.endsWith('/examples/broken-for-demo')) return response(brokenPipeline)
      if (url.endsWith('/validate')) {
        expect(JSON.parse(String(init?.body)).id).toBe('broken-for-demo')
        return response({ valid: false, errors: [1, 2, 3].map((n) => ({ nodeId: `node-${n}`, code: 'INVALID_CONFIG', message: `Mistake ${n}` })), plan: null })
      }
      throw new Error(`Unexpected request ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<PipelineEditor open onClose={() => undefined} />)

    const picker = await screen.findByRole('combobox', { name: 'Examples' })
    await waitFor(() => expect(picker.querySelectorAll('option')).toHaveLength(5))
    for (const example of examples) expect(screen.getByRole('option', { name: example.name })).toBeTruthy()

    fireEvent.change(picker, { target: { value: 'broken-for-demo' } })
    expect(await screen.findByText('This pipeline has 3 mistakes - press Validate')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Validate' }))
    expect(await screen.findAllByText(/Mistake [123]/)).toHaveLength(3)
    expect(screen.getAllByRole('button', { name: 'Show me' })).toHaveLength(3)
  })

  it('falls back to Load demo when examples endpoint returns 404', async () => {
    vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })
    vi.stubGlobal('fetch', vi.fn(async () => response({ message: 'Not found' }, 404)))
    render(<PipelineEditor open onClose={() => undefined} />)
    expect(await screen.findByRole('button', { name: 'Load demo' })).toBeTruthy()
    expect(screen.queryByRole('combobox', { name: 'Examples' })).toBeNull()
  })
})
