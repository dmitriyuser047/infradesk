import { SquareTerminal, X } from 'lucide-react'
import { Link, useLocation } from 'react-router-dom'

import { useI18n } from '../../i18n'
import { connectionPath } from '../infrastructure/infrastructureLinks'
import { terminalStateTone } from './terminalPresentation'
import type { TerminalSessionSnapshot } from './terminalSession'
import { useOptionalTerminalWorkspace, useTerminalSessions } from './TerminalWorkspaceProvider'

/**
 * The live work of this browser tab, along the bottom of the application: today, open terminals.
 * It is not navigation history — it lists what is running, whichever page is shown, and in any
 * organization. A tab leads back to its terminal; closing it ends the session.
 */
export function WorkspaceDock() {
  const { t } = useI18n()
  const sessions = useTerminalSessions()
  const location = useLocation()
  if (sessions.length === 0) return null
  const showing = new URLSearchParams(location.search).get('tab') === 'terminal'
  return <nav className="workspace-dock" aria-label={t.terminal.dock}>
    <ul>
      {sessions.map(session => <DockTab key={session.key} session={session}
        selected={showing && location.pathname === connectionPath(session.organizationId, session.connectionId)} />)}
    </ul>
  </nav>
}

function DockTab({ session, selected }: { session: TerminalSessionSnapshot; selected: boolean }) {
  const { t } = useI18n()
  const workspace = useOptionalTerminalWorkspace()
  const state = t.terminal.states[session.state]
  const running = session.state === 'connecting' || session.state === 'connected' || session.state === 'closing'
  const close = () => {
    // A live shell is ended only on purpose; a finished one just leaves the dock.
    if (running && !window.confirm(t.terminal.closeConfirm(session.connectionName))) return
    workspace?.close(session.key)
  }
  return <li className={`dock-tab ${selected ? 'dock-tab-selected' : ''} dock-${terminalStateTone(session.state)}`}>
    <Link className="dock-tab-link" to={connectionPath(session.organizationId, session.connectionId, '?tab=terminal')}
      aria-current={selected ? 'page' : undefined} aria-label={t.terminal.dockOpen(session.connectionName, state)}>
      <SquareTerminal aria-hidden size={14} />
      <span className="dock-tab-name">{session.connectionName}</span>
      <span className="dock-tab-state"><span className="status-dot" aria-hidden />{state}</span>
    </Link>
    <button type="button" className="dock-tab-close" aria-label={t.terminal.dockClose(session.connectionName)}
      title={t.terminal.dockClose(session.connectionName)} onClick={close}><X aria-hidden size={14} /></button>
  </li>
}
