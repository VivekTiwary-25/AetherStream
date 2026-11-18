export function MockBadge() {
  if (import.meta.env.VITE_USE_MOCK !== '1') return null

  return <span className="mock-badge">MOCK DATA</span>
}
