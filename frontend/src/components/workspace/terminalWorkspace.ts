import { TerminalTransport } from '../terminal/terminalTransport'
import {
  loadEmulator, TerminalSessionController, terminalSessionKey, type TerminalSessionDependencies, type TerminalSessionSnapshot,
} from './terminalSession'

/**
 * The open terminals of this browser tab, independent of routes.
 *
 * At most one terminal per organization and connection: opening a connection's terminal again
 * brings back the one already open. The route only decides which terminal is visible; the store
 * decides which are alive. It keeps nothing outside memory — no storage, no transcript.
 */
export class TerminalWorkspace {
  private sessions = new Map<string, TerminalSessionController>()
  private snapshot: readonly TerminalSessionSnapshot[] = []
  private listeners = new Set<() => void>()
  private deps: TerminalSessionDependencies

  constructor(options: Partial<Omit<TerminalSessionDependencies, 'onChange'>> = {}) {
    this.deps = {
      location: options.location ?? window.location,
      createTransport: options.createTransport ?? ((url, sink) => new TerminalTransport(url, sink)),
      loadEmulator: options.loadEmulator ?? loadEmulator,
      onChange: () => this.publish(),
    }
  }

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener)
    return () => { this.listeners.delete(listener) }
  }

  /** Immutable and stable between changes, as `useSyncExternalStore` requires. */
  getSnapshot = (): readonly TerminalSessionSnapshot[] => this.snapshot

  get(organizationId: string, connectionId: string): TerminalSessionController | undefined {
    return this.sessions.get(terminalSessionKey(organizationId, connectionId))
  }

  /** The connection's terminal, connected: the existing one when there is one, never a second shell. */
  open(organizationId: string, connection: { id: string; name: string }): TerminalSessionController {
    const key = terminalSessionKey(organizationId, connection.id)
    let session = this.sessions.get(key)
    if (!session) {
      session = new TerminalSessionController(organizationId, connection.id, connection.name, this.deps)
      this.sessions.set(key, session)
      this.publish()
    } else {
      session.rename(connection.name)
    }
    session.connect()
    return session
  }

  /** Ends the terminal and removes it from the workspace. */
  close(key: string): void {
    const session = this.sessions.get(key)
    if (!session) return
    this.sessions.delete(key)
    session.close()
    this.publish()
  }

  /**
   * Ends every terminal, as signing out does. Closing a socket does not wait for the server, so
   * this never holds anything up; the server's own checks remain the safety net.
   */
  closeAll(): void {
    if (this.sessions.size === 0) return
    const sessions = [...this.sessions.values()]
    this.sessions.clear()
    sessions.forEach(session => session.close())
    this.publish()
  }

  private publish(): void {
    this.snapshot = [...this.sessions.values()].map(session => session.snapshot)
    this.listeners.forEach(listener => listener())
  }
}
