import type { Terminal } from '@xterm/xterm'
import type { FitAddon } from '@xterm/addon-fit'

import { TerminalTransport, terminalUrl, type TerminalState } from '../terminal/terminalTransport'

/** The emulator is loaded on the first connect only, so pages without a terminal never pay for it. */
export interface EmulatorModules {
  Terminal: typeof Terminal
  FitAddon: typeof FitAddon
}

export async function loadEmulator(): Promise<EmulatorModules> {
  const [xterm, fit] = await Promise.all([import('@xterm/xterm'), import('@xterm/addon-fit'),
    import('@xterm/xterm/css/xterm.css')])
  return { Terminal: xterm.Terminal, FitAddon: fit.FitAddon }
}

/** What the interface knows about one open terminal. Never a credential, a token or output. */
export interface TerminalSessionSnapshot {
  key: string
  organizationId: string
  connectionId: string
  connectionName: string
  state: TerminalState
  /** The stable reason code of the last close or failure, if any. */
  code?: string
  /** The server's id of the live session, from `ready`. Memory only; never a credential. */
  serverSessionId?: string
  createdAt: number
}

export interface TerminalSessionDependencies {
  location: Pick<Location, 'protocol' | 'host'>
  createTransport: (url: string, sink: ConstructorParameters<typeof TerminalTransport>[1]) => TerminalTransport
  loadEmulator: () => Promise<EmulatorModules>
  onChange: () => void
}

export function terminalSessionKey(organizationId: string, connectionId: string): string {
  return `${organizationId}/${connectionId}`
}

/**
 * One terminal of one connection, alive for as long as the application is — not as long as a page.
 *
 * It owns the WebSocket transport, the xterm emulator and the element the emulator draws into.
 * Pages only attach that element to a container while they show it and detach it when they go;
 * the emulator, its scrollback and the SSH session behind it carry on meanwhile. Nothing here is
 * written to storage: a reload ends the browser side and the server closes the shell.
 */
export class TerminalSessionController {
  /** The emulator's own host element; it moves between containers, the emulator never changes. */
  readonly element: HTMLDivElement
  private emulator: Terminal | undefined
  private fitAddon: FitAddon | undefined
  private opened = false
  private transport: TerminalTransport | undefined
  private container: HTMLElement | undefined
  private cleanup: (() => void)[] = []
  private current: TerminalSessionSnapshot
  /** Each connect attempt; a slower, older one never overrides a newer one or a close. */
  private attempt = 0
  private closed = false

  constructor(organizationId: string, connectionId: string, connectionName: string, private deps: TerminalSessionDependencies) {
    this.element = document.createElement('div')
    this.element.className = 'terminal-host'
    this.current = {
      key: terminalSessionKey(organizationId, connectionId), organizationId, connectionId, connectionName,
      state: 'idle', createdAt: Date.now(),
    }
  }

  get snapshot(): TerminalSessionSnapshot { return this.current }

  get running(): boolean {
    return this.current.state === 'connecting' || this.current.state === 'connected' || this.current.state === 'closing'
  }

  rename(connectionName: string): void {
    if (connectionName !== this.current.connectionName) this.update({ connectionName })
  }

  /** Opens a new SSH session unless one is already running; the emulator and its history stay. */
  connect(): void {
    if (this.running || this.closed) return
    const attempt = ++this.attempt
    // A finished transport is gone for good; Disconnect during loading must not reach it.
    this.transport?.dispose()
    this.transport = undefined
    this.update({ state: 'connecting', code: undefined, serverSessionId: undefined })
    const start = (modules: EmulatorModules) => {
      if (attempt !== this.attempt || this.closed) return
      this.ensureEmulator(modules)
      this.openTransport()
    }
    this.deps.loadEmulator().then(start, () => {
      if (attempt === this.attempt && !this.closed) this.update({ state: 'error', code: 'TERMINAL_UNAVAILABLE' })
    })
  }

  private openTransport(): void {
    this.transport?.dispose()
    let transport: TerminalTransport | undefined
    try {
      transport = this.deps.createTransport(terminalUrl(this.deps.location, this.current.organizationId, this.current.connectionId), {
        write: bytes => { if (this.transport === transport) this.emulator?.write(bytes) },
        ready: () => {
          if (this.transport !== transport) return
          this.update({ serverSessionId: transport?.sessionId })
          this.fit()
          this.emulator?.focus()
        },
        state: (state, code) => {
          if (this.transport !== transport && transport !== undefined) return
          const ended = state === 'closed' || state === 'error'
          this.update({ state, code, ...(ended ? { serverSessionId: undefined } : {}) })
        },
      })
      this.transport = transport
      this.fit()
    } catch {
      this.transport = undefined
      this.update({ state: 'error', code: 'TERMINAL_UNAVAILABLE', serverSessionId: undefined })
    }
  }

  /** Ends the SSH session but keeps this terminal, its history and its place in the dock. */
  disconnect(): void {
    if (this.transport && this.running) {
      this.transport.disconnect()
    } else if (this.current.state === 'connecting') {
      // Still loading the emulator: the attempt is abandoned before any socket exists.
      this.attempt++
      this.update({ state: 'closed', code: 'CLIENT_CLOSE' })
    }
  }

  /** Ends the SSH session and releases the emulator. The controller is finished afterwards. */
  close(): void {
    this.closed = true
    this.attempt++
    const transport = this.transport
    this.transport = undefined
    transport?.dispose()
    this.cleanup.forEach(dispose => dispose())
    this.cleanup = []
    this.emulator?.dispose()
    this.emulator = undefined
    this.fitAddon = undefined
    this.opened = false
    this.element.replaceChildren()
    this.element.remove()
    this.container = undefined
    if (this.current.state !== 'closed' || this.current.code === undefined) {
      this.update({ state: 'closed', code: this.current.code ?? 'CLIENT_CLOSE', serverSessionId: undefined })
    }
  }

  /**
   * Shows the terminal in a container. The emulator draws into its own element, which moves here
   * from wherever it was; nothing is re-created, nothing is replayed.
   */
  attach(container: HTMLElement): () => void {
    this.container = container
    container.appendChild(this.element)
    if (this.emulator && !this.opened) this.openEmulator()
    this.fit()
    return () => {
      if (this.container !== container) return
      this.container = undefined
      if (this.element.parentElement === container) container.removeChild(this.element)
    }
  }

  /** Fits the emulator to its visible container and tells the server the new size. */
  fit(): void {
    const container = this.container
    if (!this.emulator || !this.fitAddon || !this.opened || !container || !container.clientWidth || !container.clientHeight) return
    this.fitAddon.fit()
    if (this.emulator.cols > 500 || this.emulator.rows > 200) {
      this.emulator.resize(Math.min(this.emulator.cols, 500), Math.min(this.emulator.rows, 200))
    }
    this.transport?.resize(this.emulator.cols, this.emulator.rows)
  }

  focus(): void {
    this.emulator?.focus()
  }

  private ensureEmulator(modules: EmulatorModules): void {
    if (this.emulator) return
    const colors = getComputedStyle(document.documentElement)
    const emulator = new modules.Terminal({
      cursorBlink: true, scrollback: 2000, fontSize: 13,
      fontFamily: colors.getPropertyValue('--font-mono').trim(),
      theme: {
        background: colors.getPropertyValue('--surface').trim(),
        foreground: colors.getPropertyValue('--text-primary').trim(),
        cursor: colors.getPropertyValue('--text-primary').trim(),
      },
    })
    const fit = new modules.FitAddon()
    emulator.loadAddon(fit)
    // Consume OSC clipboard/title requests. Remote output never drives browser side effects.
    const handlers = [0, 1, 2, 8, 52].map(id => emulator.parser.registerOscHandler(id, () => true))
    const input = emulator.onData(value => this.transport?.input(value))
    this.cleanup = [...handlers.map(handler => () => handler.dispose()), () => input.dispose()]
    this.emulator = emulator
    this.fitAddon = fit
    if (this.container) this.openEmulator()
  }

  private openEmulator(): void {
    if (!this.emulator || this.opened) return
    this.emulator.open(this.element)
    this.opened = true
  }

  private update(change: Partial<TerminalSessionSnapshot>): void {
    this.current = { ...this.current, ...change }
    this.deps.onChange()
  }
}
