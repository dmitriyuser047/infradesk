import type { TerminalState } from '../terminal/terminalTransport'

/** Connected is healthy, a failure is a problem; everything else is neutral. Always shown with text. */
export function terminalStateTone(state: TerminalState): 'success' | 'danger' | 'neutral' {
  return state === 'connected' ? 'success' : state === 'error' ? 'danger' : 'neutral'
}
