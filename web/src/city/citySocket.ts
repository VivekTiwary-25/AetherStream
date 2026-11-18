import type { WebSocketMessage } from '../api/types'
import type { CityEnvelope } from './cityTypes'

export function toCityEnvelope(message: WebSocketMessage | null): CityEnvelope | null {
  return message
}
