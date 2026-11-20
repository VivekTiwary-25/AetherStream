export function createPrefetch<T>(importer: () => Promise<T>) {
  let pending: Promise<T> | undefined
  return () => pending ??= importer().catch((error: unknown) => {
    // A failed speculative load must not poison the later real load (e.g. Live World click after a network blip).
    pending = undefined
    throw error
  })
}
