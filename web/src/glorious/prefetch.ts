export function createPrefetch<T>(importer: () => Promise<T>) {
  let pending: Promise<T> | undefined
  return () => pending ??= importer()
}
