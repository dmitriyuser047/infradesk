// @vitest-environment jsdom
import { StrictMode } from 'react'
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { Link, MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { I18nProvider } from '../../i18n'
import type { ConnectionResponse } from '../../types/connection'
import { TerminalWorkspace } from '../workspace/terminalWorkspace'
import type { EmulatorModules } from '../workspace/terminalSession'
import { TerminalWorkspaceProvider } from '../workspace/TerminalWorkspaceProvider'
import { WorkspaceDock } from '../workspace/WorkspaceDock'
import { TerminalPanel, terminalPrerequisite } from './TerminalPanel'

class Socket {
  static instances: Socket[] = []
  binaryType = ''; protocol = 'infradesk-terminal-v1'; readyState = 1; bufferedAmount = 0
  onopen: (() => void) | null = null
  onmessage: ((event: { data: unknown }) => void) | null = null
  onclose: ((event: { reason?: string }) => void) | null = null
  onerror: (() => void) | null = null
  send = vi.fn(); close = vi.fn(() => { this.readyState = 3 })
  constructor(readonly url: string) { Socket.instances.push(this) }
  ready() { this.onmessage?.({ data: JSON.stringify({ type: 'ready', protocolVersion: 1, sessionId: '00000000-0000-0000-0000-000000000001' }) }) }
  // Built from this realm's typed arrays: the transport accepts only a genuine ArrayBuffer as output.
  output(text: string) { this.onmessage?.({ data: Uint8Array.from(text, char => char.charCodeAt(0)).buffer }) }
  control(type: 'closed' | 'error', code: string) { this.onmessage?.({ data: JSON.stringify({ type, protocolVersion: 1, code }) }) }
}

/** A stand-in emulator: records where it is drawn and what it is given, and nothing else. */
class FakeTerminal {
  static instances: FakeTerminal[] = []
  cols = 80; rows = 24
  host: HTMLElement | undefined
  written: string[] = []
  parser = { registerOscHandler: vi.fn((_id: number, _handler: () => boolean) => ({ dispose: vi.fn() })) }
  dispose = vi.fn()
  constructor() { FakeTerminal.instances.push(this) }
  open(host: HTMLElement) { this.host = host }
  loadAddon() {}
  onData() { return { dispose: vi.fn() } }
  write(bytes: Uint8Array) { this.written.push(new TextDecoder().decode(bytes)) }
  focus() {}
  resize() {}
}
const fit = vi.fn()
const modules = { Terminal: FakeTerminal, FitAddon: class { fit = fit } } as unknown as EmulatorModules

function connection(id: string, name: string, overrides: Partial<ConnectionResponse> = {}): ConnectionResponse {
  return {
    id, connectorType: 'SSH', code: id, name, active: true,
    scope: { type: 'ORGANIZATION' }, schedule: null, lastSync: null, createdAt: '', updatedAt: '1',
    ssh: { host: 'example', port: 22, username: 'user', authenticationType: 'PASSWORD', hostKeyFingerprint: 'pin', hostTrusted: true, credentialConfigured: true },
    ...overrides,
  }
}
const finnish = connection('finnish', 'Finnish Node')
const german = connection('german', 'German Node')

function Where() {
  const location = useLocation()
  return <output data-testid="location">{location.pathname + location.search}</output>
}

function ConnectionRoute({ connections }: { connections: ConnectionResponse[] }) {
  const location = useLocation()
  const id = location.pathname.split('/')[4]
  const value = connections.find(item => item.id === id)
  return value ? <TerminalPanel organizationId="org" connection={value} editLink="/edit" /> : null
}

/**
 * The application as far as terminals are concerned: routes that come and go, the dock that stays,
 * and one workspace above them all.
 */
function app(workspace: TerminalWorkspace, options: { path?: string; connections?: ConnectionResponse[] } = {}) {
  const connections = options.connections ?? [finnish, german]
  return <StrictMode><I18nProvider initialLocale="en"><TerminalWorkspaceProvider workspace={workspace}>
    <MemoryRouter initialEntries={[options.path ?? '/organizations/org/connections/finnish?tab=terminal']}>
      <Routes>
        <Route path="/organizations/:organizationId/connections/:connectionId" element={<ConnectionRoute connections={connections} />} />
        <Route path="/organizations/:organizationId/overview" element={<p>Overview</p>} />
      </Routes>
      <nav>
        <Link to="/organizations/org/overview">Go overview</Link>
        <Link to="/organizations/org/connections/finnish?tab=terminal">Go finnish</Link>
        <Link to="/organizations/org/connections/german?tab=terminal">Go german</Link>
        <Link to="/organizations/other/overview">Go other organization</Link>
      </nav>
      <WorkspaceDock />
      <Where />
    </MemoryRouter>
  </TerminalWorkspaceProvider></I18nProvider></StrictMode>
}

const flush = () => act(async () => { await Promise.resolve(); await Promise.resolve() })
async function connect() {
  fireEvent.click(screen.getByRole('button', { name: 'Connect' }))
  await flush()
}
const go = (name: string) => fireEvent.click(screen.getByRole('link', { name }))
const dock = () => screen.queryByRole('navigation', { name: 'Open terminals' })

let workspace: TerminalWorkspace
beforeEach(() => {
  vi.clearAllMocks()
  Socket.instances = []
  FakeTerminal.instances = []
  vi.stubGlobal('WebSocket', Socket)
  vi.stubGlobal('ResizeObserver', class { observe = vi.fn(); disconnect = vi.fn() })
  vi.stubGlobal('requestAnimationFrame', vi.fn(() => 1))
  vi.stubGlobal('cancelAnimationFrame', vi.fn())
  workspace = new TerminalWorkspace({ loadEmulator: async () => modules })
})
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks() })

describe('terminal workspace', () => {
  it('does not connect on mount in StrictMode; one explicit Connect opens one socket', async () => {
    render(app(workspace))
    expect(Socket.instances).toHaveLength(0)
    await connect()
    expect(Socket.instances).toHaveLength(1)
    expect(Socket.instances[0].url).toMatch(/\/api\/v1\/organizations\/org\/connections\/finnish\/terminal$/)
    // Remote output never drives the browser's title or clipboard.
    expect(FakeTerminal.instances[0].parser.registerOscHandler.mock.calls.map(([id]) => id)).toEqual([0, 1, 2, 8, 52])
  })

  it('keeps the same live terminal across navigation and shows it again when coming back', async () => {
    render(app(workspace))
    await connect()
    const socket = Socket.instances[0]
    act(() => socket.ready())
    const emulator = FakeTerminal.instances[0]

    go('Go overview')
    expect(screen.getByText('Overview')).toBeTruthy()
    // Leaving the page is not leaving the terminal.
    expect(socket.close).not.toHaveBeenCalled()
    expect(emulator.dispose).not.toHaveBeenCalled()
    // A hidden terminal still receives its output.
    act(() => socket.output('still running'))
    expect(emulator.written).toContain('still running')

    go('Go finnish')
    expect(document.querySelector('.terminal-toolbar')?.textContent).toContain('Connected')
    expect(Socket.instances).toHaveLength(1)
    expect(FakeTerminal.instances).toHaveLength(1)
    // The very same emulator element is drawn in the new viewport: nothing re-created, nothing replayed.
    expect(screen.getByLabelText('Terminal').contains(emulator.host!)).toBe(true)
    expect(emulator.written).toEqual(['still running'])
  })

  it('reuses the open terminal of a connection instead of opening a second shell', async () => {
    render(app(workspace))
    await connect()
    act(() => Socket.instances[0].ready())
    act(() => { workspace.open('org', { id: 'finnish', name: 'Finnish Node' }) })
    await flush()
    expect(Socket.instances).toHaveLength(1)
    expect(screen.queryByRole('button', { name: 'Connect' })).toBeNull()
  })

  it('keeps independent terminals per connection and lists them all in the dock', async () => {
    render(app(workspace))
    await connect()
    act(() => Socket.instances[0].ready())
    go('Go german')
    await connect()
    act(() => Socket.instances[1].ready())
    expect(Socket.instances).toHaveLength(2)
    expect(FakeTerminal.instances).toHaveLength(2)

    // In another organization the dock still holds both, and leads back to each.
    go('Go other organization')
    const tabs = within(dock()!).getAllByRole('link')
    expect(tabs.map(tab => tab.getAttribute('aria-label'))).toEqual(['Terminal Finnish Node, Connected', 'Terminal German Node, Connected'])
    fireEvent.click(tabs[0])
    expect(screen.getByTestId('location').textContent).toBe('/organizations/org/connections/finnish?tab=terminal')
    expect(within(dock()!).getAllByRole('link')[0].getAttribute('aria-current')).toBe('page')
    expect(Socket.instances).toHaveLength(2)
    expect(Socket.instances.every(socket => socket.close.mock.calls.length === 0)).toBe(true)
  })

  it('closes a live terminal from the dock only after confirmation, and removes it', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true)
    render(app(workspace))
    await connect()
    act(() => Socket.instances[0].ready())
    const close = within(dock()!).getByRole('button', { name: 'Close terminal Finnish Node' })

    fireEvent.click(close)
    expect(Socket.instances[0].close).not.toHaveBeenCalled()
    fireEvent.click(close)
    expect(confirm).toHaveBeenLastCalledWith('Close the SSH session Finnish Node?')
    expect(Socket.instances[0].close).toHaveBeenCalledOnce()
    expect(FakeTerminal.instances[0].dispose).toHaveBeenCalledOnce()
    expect(dock()).toBeNull()
    expect(screen.getByRole('button', { name: 'Connect' })).toBeTruthy()
  })

  it('disconnects but keeps the terminal in the dock; reconnect opens a new socket into the same emulator', async () => {
    render(app(workspace))
    await connect()
    act(() => Socket.instances[0].ready())
    fireEvent.click(screen.getByRole('button', { name: 'Disconnect' }))
    expect(Socket.instances[0].close).toHaveBeenCalledOnce()
    expect(within(dock()!).getByRole('link').getAttribute('aria-label')).toBe('Terminal Finnish Node, Disconnected')
    expect(screen.getByRole('button', { name: 'Close' })).toBeTruthy()

    fireEvent.click(screen.getByRole('button', { name: 'Reconnect' }))
    await flush()
    expect(Socket.instances).toHaveLength(2)
    expect(FakeTerminal.instances).toHaveLength(1)
    expect(FakeTerminal.instances[0].dispose).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: 'Disconnect' }))
    fireEvent.click(screen.getByRole('button', { name: 'Close' }))
    // A finished terminal leaves without a question.
    expect(dock()).toBeNull()
  })

  it('shows why a hidden terminal ended when the server revoked it', async () => {
    render(app(workspace))
    await connect()
    act(() => Socket.instances[0].ready())
    go('Go overview')
    act(() => Socket.instances[0].control('closed', 'AUTH_SESSION_ENDED'))
    expect(within(dock()!).getByRole('link').getAttribute('aria-label')).toBe('Terminal Finnish Node, Disconnected')
    go('Go finnish')
    expect(screen.getByText('Your login session ended.')).toBeTruthy()
    cleanup()

    const second = new TerminalWorkspace({ loadEmulator: async () => modules })
    render(app(second))
    await connect()
    act(() => Socket.instances[1].ready())
    act(() => Socket.instances[1].control('error', 'CONNECTION_CHANGED'))
    expect(screen.getByText('The connection settings changed.')).toBeTruthy()
    expect(within(dock()!).getByRole('link').getAttribute('aria-label')).toBe('Terminal Finnish Node, Connection failed')
    // No automatic reconnect.
    expect(Socket.instances).toHaveLength(2)
  })

  it('ends every terminal when the session is found signed out or the application goes', async () => {
    const view = render(app(workspace))
    await connect()
    act(() => Socket.instances[0].ready())
    act(() => { window.dispatchEvent(new Event('infradesk:unauthenticated')) })
    expect(Socket.instances[0].close).toHaveBeenCalledOnce()
    expect(dock()).toBeNull()

    await connect()
    act(() => Socket.instances[1].ready())
    view.unmount()
    expect(Socket.instances[1].close).toHaveBeenCalledOnce()
  })

  it('keeps nothing in browser storage', async () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem')
    render(app(workspace))
    await connect()
    act(() => Socket.instances[0].ready())
    act(() => Socket.instances[0].output('secret output'))
    go('Go overview')
    go('Go finnish')
    expect(setItem).not.toHaveBeenCalled()
  })

  it.each(['inactive', 'mismatch', 'untrusted', 'missingCredentials'] as const)('blocks %s before opening a socket', async reason => {
    const value = { ...finnish, ssh: { ...finnish.ssh! } }
    if (reason === 'inactive') value.active = false
    if (reason === 'mismatch') value.lastSync = { id: 'sync', status: 'FAILED', startedAt: '', finishedAt: '', errorCode: 'SSH_HOST_KEY_MISMATCH', errorMessage: null }
    if (reason === 'untrusted') value.ssh.hostTrusted = false
    if (reason === 'missingCredentials') value.ssh.credentialConfigured = false
    expect(terminalPrerequisite(value)).toBe(reason)
    render(app(workspace, { connections: [value] }))
    const button = screen.getByRole('button', { name: 'Connect' }) as HTMLButtonElement
    expect(button.disabled).toBe(true)
    fireEvent.click(button)
    await flush()
    expect(Socket.instances).toHaveLength(0)
  })

  it('localizes unknown server errors without exposing their text', async () => {
    render(app(workspace))
    await connect()
    act(() => Socket.instances[0].control('error', 'secret-diagnostic'))
    expect(screen.getByText('The terminal connection ended.')).toBeTruthy()
    expect(screen.queryByText('secret-diagnostic')).toBeNull()
    expect(Socket.instances[0].close).toHaveBeenCalledOnce()
    expect(Socket.instances).toHaveLength(1)
  })

  it('fits to its viewport after ready and detaches its listeners when the page goes', async () => {
    vi.stubGlobal('requestAnimationFrame', vi.fn(callback => { callback(); return 1 }))
    render(app(workspace))
    await connect()
    const host = screen.getByLabelText('Terminal')
    Object.defineProperties(host, { clientWidth: { value: 800 }, clientHeight: { value: 400 } })
    act(() => Socket.instances[0].ready())
    const afterReady = fit.mock.calls.length
    expect(afterReady).toBeGreaterThan(0)
    fireEvent(document, new Event('fullscreenchange'))
    expect(fit).toHaveBeenCalledTimes(afterReady + 1)
    go('Go overview')
    fireEvent(document, new Event('fullscreenchange'))
    expect(fit).toHaveBeenCalledTimes(afterReady + 1)
  })
})
