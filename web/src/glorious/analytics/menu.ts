import { createElement } from 'react'
import type { SidebarItem } from '../shell/sections'
import { AnalyticsPanel } from './AnalyticsPanel'

export const analyticsMenu: SidebarItem[] = [
  ['truck-speeds', 'Truck Speeds'],
  ['deliveries', 'Deliveries'],
  ['alerts', 'Alerts'],
  ['events-processed', 'Events Processed'],
  ['failed-records', 'Failed Records'],
].map(([id, label]) => ({ id, label, onSelect: (windows) => windows.open(`analytics.${id}`, { title: label, strong: true, render: () => createElement(AnalyticsPanel) }) }))
