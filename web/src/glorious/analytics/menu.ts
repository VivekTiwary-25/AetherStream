import { createElement } from 'react'
import type { SidebarItem } from '../shell/sections'
import { AnalyticsPanel, type AnalyticsId } from './AnalyticsPanel'

const analyticsOptions: Array<[AnalyticsId, string]> = [
  ['truck-speeds', 'Truck Speeds'],
  ['deliveries', 'Deliveries'],
  ['alerts', 'Alerts'],
  ['events-processed', 'Events Processed'],
  ['failed-records', 'Failed Records'],
]

export const analyticsMenu: SidebarItem[] = analyticsOptions.map(([id, label]) => ({ id, label, onSelect: (windows) => windows.open(`analytics.${id}`, { title: label, strong: true, initial: { x: 260, y: 110, w: 560, h: 420 }, render: () => createElement(AnalyticsPanel, { id }) }) }))
