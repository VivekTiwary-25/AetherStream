import { createElement } from 'react'
import type { SidebarItem } from '../shell/sections'
import { LogsRecovery } from './LogsRecovery'
import { TruckBreakdown } from './TruckBreakdown'

export const settingsMenu: SidebarItem[] = [
  {
    id: 'logs-recovery',
    label: 'Logs & Recovery',
    onSelect: (windows) =>
      windows.open('settings.logs-recovery', {
        title: 'Logs & Recovery',
        strong: true,
        initial: { x: 250, y: 90, w: 980, h: 720 },
        render: () => createElement(LogsRecovery),
      }),
  },
  {
    id: 'truck-breakdown',
    label: 'Truck breakdown',
    onSelect: (windows) =>
      windows.open('settings.truck-breakdown', {
        title: 'Truck breakdown',
        strong: true,
        initial: { x: 280, y: 130, w: 450, h: 390 },
        render: () => createElement(TruckBreakdown),
      }),
  },
]
