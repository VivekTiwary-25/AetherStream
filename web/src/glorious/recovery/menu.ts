import { createElement } from 'react'
import type { SidebarItem } from '../shell/sections'

// Settings sub-options. Owned by the Logs & Recovery track (folder renamed from logs/,
// which the root .gitignore `logs/` rule silently ignored). Placeholder until the track lands.
export const settingsMenu: SidebarItem[] = [
  {
    id: 'logs-recovery',
    label: 'Logs & Recovery',
    onSelect: (windows) =>
      windows.open('settings.logs-recovery', {
        title: 'Logs & Recovery',
        strong: true,
        render: () => createElement('p', null, 'Logs & Recovery is being built.'),
      }),
  },
]
