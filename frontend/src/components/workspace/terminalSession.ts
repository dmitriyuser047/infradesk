import type { Terminal } from '@xterm/xterm'
import type { FitAddon } from '@xterm/addon-fit'

import { TerminalTransport, terminalUrl, type TerminalState } from '../terminal/terminalTransport'

export type TerminalTheme = 'light' | 'dark'
const themeKey = 'infradesk.terminal.theme'
const darkTheme = {
  background: '#0d0d0d', foreground: '#d4d4d4', cursor: '#ffffff', selectionBackground: '#565f78',
  black: '#000000', red: '#cd3131', green: '#0dbc79', yellow: '#e5e510', blue: '#2472c8',
  magenta: '#bc3fbc', cyan: '#11a8cd', white: '#e5e5e5', brightBlack: '#666666',
  brightRed: '#f14c4c', brightGreen: '#23d18b', brightYellow: '#f5f543', brightBlue: '#3b8eea',
  brightMagenta: '#d670d6', brightCyan: '#29b8db', brightWhite: '#ffffff',
}

function savedTheme(): TerminalTheme {
  try { return localStorage.getItem(themeKey) === 'dark' ? 'dark' : 'light' } catch { return 'light' }
}

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
  theme: TerminalTheme
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
  private retryTimer: ReturnType<typeof setTimeout> | undefined
  private retries = 0
  private manualDisconnect = false
  private everReady = false

  constructor(organizationId: string, connectionId: string, connectionName: string, private deps: TerminalSessionDependencies) {
    this.element = document.createElement('div')
    this.element.className = 'terminal-host'
    this.current = {
      key: terminalSessionKey(organizationId, connectionId), organizationId, connectionId, connectionName,
      state: 'idle', createdAt: Date.now(), theme: savedTheme(),
    }
  }

  get snapshot(): TerminalSessionSnapshot { return this.current }
  get hasHistory(): boolean { return this.everReady }

  get running(): boolean {
    return this.current.state === 'connecting' || this.current.state === 'reconnecting' || this.current.state === 'connected' || this.current.state === 'closing'
  }

  rename(connectionName: string): void {
    if (connectionName !== this.current.connectionName) this.update({ connectionName })
  }

  /** Opens a new SSH session unless one is already running; the emulator and its history stay. */
  connect(): void {
    if (this.running || this.closed) return
    this.manualDisconnect = false
    this.retries = 0
    clearTimeout(this.retryTimer)
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
          if (this.everReady) this.emulator?.clear()
          this.everReady = true
          this.update({ serverSessionId: transport?.sessionId })
          this.retries = 0
          this.fit()
          this.emulator?.focus()
        },
        state: (state, code) => {
          if (this.transport !== transport && transport !== undefined) return
          if (state === 'connecting' && this.retries > 0) state = 'reconnecting'
          const ended = state === 'closed' || state === 'error'
          if (ended && !this.manualDisconnect && this.shouldRetry(code) && this.retries < 3) {
            this.scheduleRetry()
          } else {
            this.update({ state, code, ...(ended ? { serverSessionId: undefined } : {}) })
          }
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
    this.manualDisconnect = true
    clearTimeout(this.retryTimer)
    if (this.transport && this.running) {
      this.transport.disconnect()
    } else if (this.current.state === 'connecting' || this.current.state === 'reconnecting') {
      // Still loading the emulator: the attempt is abandoned before any socket exists.
      this.attempt++
      this.update({ state: 'closed', code: 'CLIENT_CLOSE' })
    }
  }

  /** Ends the SSH session and releases the emulator. The controller is finished afterwards. */
  close(): void {
    this.closed = true
    this.manualDisconnect = true
    clearTimeout(this.retryTimer)
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

  get hasSelection(): boolean { return !!this.emulator?.hasSelection() }
  copy(): void {
    const value = this.emulator?.getSelection()
    if (value) void navigator.clipboard?.writeText(value).catch(() => {})
  }
  paste(): void {
    void navigator.clipboard?.readText().then(value => { if (this.current.state === 'connected') this.emulator?.paste(value) }).catch(() => {})
  }
  selectAll(): void { this.emulator?.selectAll() }
  clear(): void { this.emulator?.clear() }
  setTheme(theme: TerminalTheme): void {
    if (theme === this.current.theme) return
    try { localStorage.setItem(themeKey, theme) } catch { /* private browsing may disable storage */ }
    this.update({ theme })
    if (this.emulator) this.emulator.options.theme = this.colors(theme)
  }

  private colors(theme: TerminalTheme) {
    if (theme === 'dark') return darkTheme
    const colors = getComputedStyle(document.documentElement)
    return {
      background: colors.getPropertyValue('--surface').trim() || '#ffffff',
      foreground: colors.getPropertyValue('--text-primary').trim() || '#202020',
      cursor: colors.getPropertyValue('--text-primary').trim() || '#202020',
      selectionBackground: '#a9c7ef',
    }
  }

  private shouldRetry(code?: string): boolean {
    return !code || ['TRANSPORT_CLOSED', 'TERMINAL_UNAVAILABLE', 'SESSION_VALIDATION_FAILED'].includes(code)
  }

  private scheduleRetry(): void {
    const generation = ++this.attempt
    const delay = 1000 * 2 ** this.retries++
    this.transport?.dispose()
    this.transport = undefined
    this.update({ state: 'reconnecting', code: undefined, serverSessionId: undefined })
    clearTimeout(this.retryTimer)
    this.retryTimer = setTimeout(() => {
      if (this.closed || this.manualDisconnect || generation !== this.attempt) return
      this.openTransport()
    }, delay)
  }

  private ensureEmulator(modules: EmulatorModules): void {
    if (this.emulator) return
    const emulator = new modules.Terminal({
      cursorBlink: true, scrollback: 2000, fontSize: 13,
      fontFamily: getComputedStyle(document.documentElement).getPropertyValue('--font-mono').trim(),
      theme: this.colors(this.current.theme),
    })
    const fit = new modules.FitAddon()
    emulator.loadAddon(fit)
    // Consume OSC clipboard/title requests. Remote output never drives browser side effects.
    const handlers = [0, 1, 2, 8, 52].map(id => emulator.parser.registerOscHandler(id, () => true))
    const input = emulator.onData(value => this.transport?.input(value))
    emulator.attachCustomKeyEventHandler(event => {
      if (event.type !== 'keydown') return true
      const key = event.key.toLowerCase()
      const mac = /Mac|iPhone|iPad/.test(navigator.platform)
      if ((event.ctrlKey && event.shiftKey || mac && event.metaKey || event.ctrlKey && this.hasSelection) && key === 'c') {
        if (typeof navigator.clipboard?.writeText === 'function') { this.copy(); return false }
      }
      return true
    })
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
