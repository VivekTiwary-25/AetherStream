export type IconName = 'home' | 'live' | 'analytics' | 'pipelines' | 'settings' | 'back' | 'close' | 'stream'
const paths: Record<IconName, string> = {
  home: 'm3 10 9-7 9 7M5 9v12h5v-7h4v7h5V9',
  live: 'm3 5 6-2 6 2 6-2v16l-6 2-6-2-6 2ZM9 3v16M15 5v16',
  analytics: 'M4 21V11h4v10M10 21V7h4v14M16 21V3h4v18',
  pipelines: 'm3 7 9-4 9 4-9 4ZM3 12l9 4 9-4M3 17l9 4 9-4',
  settings: 'm9 3-1 3-3 1-2 3 2 2v3l2 3 3-1 2 3 3-1 1-3 3-1 2-3-2-2V9l-2-3-3 1-2-3ZM15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0',
  back: 'm14 5-7 7 7 7M7 12h14',
  close: 'm6 6 12 12M6 18 18 6',
  stream: 'M3 7c6-7 12 7 18 0M3 12c6-7 12 7 18 0M3 17c6-7 12 7 18 0',
}
export function Icon({ name }: { name: IconName }) {
  return <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={paths[name]} /></svg>
}
