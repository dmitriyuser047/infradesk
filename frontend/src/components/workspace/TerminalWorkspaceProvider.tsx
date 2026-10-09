import { createContext, useContext, useEffect, useState, useSyncExternalStore, type ReactNode } from 'react'

import type { TerminalSessionSnapshot } from './terminalSession'
import { TerminalWorkspace } from './terminalWorkspace'

const WorkspaceContext = createContext<TerminalWorkspace | null>(null)
const noSessions: readonly TerminalSessionSnapshot[] = []
const noSubscription = () => () => undefined
const noSnapshot = () => noSessions

/**
 * Holds the open terminals for the whole application, above the router, so that navigating never
 * ends one. Live transport state is kept here rather than in the query cache: it is not server
 * data to refetch but a connection this tab owns.
 *
 * Every terminal ends when the provider goes (the application is torn down), when the page is
 * left and when the session is found to be signed out.
 */
export function TerminalWorkspaceProvider({ children, workspace: supplied }: { children: ReactNode; workspace?: TerminalWorkspace }) {
  const [workspace] = useState(() => supplied ?? new TerminalWorkspace())
  useEffect(() => {
    const signedOut = () => workspace.closeAll()
    window.addEventListener('infradesk:unauthenticated', signedOut)
    // Leaving or reloading the page ends its shells: the server would otherwise keep each one
    // detached, awaiting a resume this page can no longer make.
    window.addEventListener('pagehide', signedOut)
    return () => {
      window.removeEventListener('infradesk:unauthenticated', signedOut)
      window.removeEventListener('pagehide', signedOut)
      workspace.closeAll()
    }
  }, [workspace])
  return <WorkspaceContext.Provider value={workspace}>{children}</WorkspaceContext.Provider>
}

export function useTerminalWorkspace(): TerminalWorkspace {
  const workspace = useContext(WorkspaceContext)
  if (!workspace) throw new Error('TerminalWorkspaceProvider is missing')
  return workspace
}

/** The workspace when the application provides one; a page rendered on its own has none. */
export function useOptionalTerminalWorkspace(): TerminalWorkspace | null {
  return useContext(WorkspaceContext)
}

/** Every open terminal, re-rendering on any change of any of them. */
export function useTerminalSessions(): readonly TerminalSessionSnapshot[] {
  const workspace = useContext(WorkspaceContext)
  const snapshot = workspace?.getSnapshot ?? noSnapshot
  // Server rendering (and static test rendering) has no live terminals.
  return useSyncExternalStore(workspace?.subscribe ?? noSubscription, snapshot, noSnapshot)
}

export function useTerminalSession(organizationId: string, connectionId: string): TerminalSessionSnapshot | undefined {
  return useTerminalSessions().find(session => session.organizationId === organizationId && session.connectionId === connectionId)
}
