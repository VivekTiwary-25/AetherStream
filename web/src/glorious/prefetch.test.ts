import { expect, it, vi } from 'vitest'
import { createPrefetch } from './prefetch'

it('shares one import between repeated prefetches and the lazy loader', async () => {
  const pending = Promise.resolve('loaded')
  const importer = vi.fn(() => pending)
  const prefetch = createPrefetch(importer)

  const first = prefetch()
  expect(prefetch()).toBe(first)
  expect(importer).toHaveBeenCalledTimes(1)

  await expect(first).resolves.toBe('loaded')
  expect(prefetch()).toBe(first)
  expect(importer).toHaveBeenCalledTimes(1)
})

it('retries the import after a failed prefetch instead of caching the failure', async () => {
  const importer = vi.fn<() => Promise<string>>()
    .mockRejectedValueOnce(new Error('chunk load failed'))
    .mockResolvedValueOnce('loaded')
  const prefetch = createPrefetch(importer)

  await expect(prefetch()).rejects.toThrow('chunk load failed')
  await expect(prefetch()).resolves.toBe('loaded')
  expect(importer).toHaveBeenCalledTimes(2)
})
