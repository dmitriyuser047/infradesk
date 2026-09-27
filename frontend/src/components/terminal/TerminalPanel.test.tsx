// @vitest-environment jsdom
import { StrictMode } from 'react'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { I18nProvider } from '../../i18n'
import type { ConnectionResponse } from '../../types/connection'

const mocks = vi.hoisted(() => ({ dispose: vi.fn(), fit: vi.fn(), osc: vi.fn((_id: number, _handler: () => boolean) => ({ dispose: vi.fn() })), data: vi.fn(() => ({ dispose: vi.fn() })), write: vi.fn() }))
vi.mock('@xterm/xterm', () => ({ Terminal: class {
  cols = 80; rows = 24
  parser = { registerOscHandler: mocks.osc }
  open = vi.fn(); loadAddon = vi.fn(); dispose = mocks.dispose; onData = mocks.data; write = mocks.write; focus = vi.fn()
} }))
vi.mock('@xterm/addon-fit', () => ({ FitAddon: class { fit = mocks.fit } }))
import { TerminalPanel, terminalPrerequisite } from './TerminalPanel'

class Socket {
  static instances: Socket[] = []
  binaryType = ''; protocol = 'infradesk-terminal-v1'; readyState = 1; bufferedAmount = 0
  onopen: (() => void) | null = null
  onmessage: ((event: { data: unknown }) => void) | null = null
  onclose: (() => void) | null = null
  onerror: (() => void) | null = null
  send = vi.fn(); close = vi.fn(() => { this.readyState = 3 })
  constructor() { Socket.instances.push(this) }
  ready() { this.onmessage?.({ data: JSON.stringify({ type: 'ready', protocolVersion: 1 }) }) }
}
const connection: ConnectionResponse = {
  id: 'connection', connectorType: 'SSH', code: 'ssh', name: 'Host', active: true,
  scope: { type: 'ORGANIZATION' }, schedule: null, lastSync: null, createdAt: '', updatedAt: '1',
  ssh: { host: 'example', port: 22, username: 'user', authenticationType: 'PASSWORD', hostKeyFingerprint: 'pin', hostTrusted: true, credentialConfigured: true },
}
function panel(value = connection) {
  return <StrictMode><MemoryRouter><I18nProvider initialLocale="en"><TerminalPanel organizationId="org" connection={value} editLink="/edit" /></I18nProvider></MemoryRouter></StrictMode>
}
beforeEach(() => {
  vi.clearAllMocks()
  Socket.instances = []
  vi.stubGlobal('WebSocket', Socket)
  vi.stubGlobal('ResizeObserver', class { observe = vi.fn(); disconnect = vi.fn() })
  vi.stubGlobal('requestAnimationFrame', vi.fn(() => 1))
  vi.stubGlobal('cancelAnimationFrame', vi.fn())
})
afterEach(() => { cleanup(); vi.unstubAllGlobals() })
describe('terminal panel lifecycle', () => {
  it('does not connect on mount in StrictMode; connects only once per explicit action', () => {
    const view = render(panel())
    expect(Socket.instances).toHaveLength(0)
    fireEvent.click(screen.getByRole('button', { name: 'Connect' }))
    expect(Socket.instances).toHaveLength(1)
    expect(mocks.osc.mock.calls.map(([id]) => id)).toEqual([0, 1, 2, 52])
    view.unmount()
    expect(Socket.instances[0].close).toHaveBeenCalledTimes(1)
    expect(mocks.dispose).toHaveBeenCalledTimes(1)
  })
  it.each(['inactive', 'mismatch', 'untrusted', 'missingCredentials'] as const)('blocks %s before opening a socket', reason => {
    const value = { ...connection, ssh: { ...connection.ssh! } }
    if (reason === 'inactive') value.active = false
    if (reason === 'mismatch') value.lastSync = { id: 'sync', status: 'FAILED', startedAt: '', finishedAt: '', errorCode: 'SSH_HOST_KEY_MISMATCH', errorMessage: null }
    if (reason === 'untrusted') value.ssh.hostTrusted = false
    if (reason === 'missingCredentials') value.ssh.credentialConfigured = false
    expect(terminalPrerequisite(value)).toBe(reason)
    render(panel(value))
    const button = screen.getByRole('button', { name: 'Connect' }) as HTMLButtonElement
    expect(button.disabled).toBe(true)
    fireEvent.click(button)
    expect(Socket.instances).toHaveLength(0)
  })
  it('disconnects, disposes emulator and reconnects with a new socket', () => {
    render(panel())
    fireEvent.click(screen.getByRole('button', { name: 'Connect' }))
    fireEvent.click(screen.getByRole('button', { name: 'Disconnect' }))
    expect(Socket.instances[0].close).toHaveBeenCalledOnce()
    expect(mocks.dispose).toHaveBeenCalledOnce()
    fireEvent.click(screen.getByRole('button', { name: 'Connect' }))
    expect(Socket.instances).toHaveLength(2)
    expect(Socket.instances[0].onmessage).toBeNull()
  })
  it('localizes unknown server errors without exposing their text or reconnecting', () => {
    render(panel())
    fireEvent.click(screen.getByRole('button', { name: 'Connect' }))
    const socket = Socket.instances[0]
    act(() => socket.onmessage?.({ data: JSON.stringify({ type: 'error', code: 'secret-diagnostic' }) }))
    expect(screen.getByText('The terminal connection ended.')).toBeTruthy()
    expect(screen.queryByText('secret-diagnostic')).toBeNull()
    expect(socket.close).toHaveBeenCalledOnce()
    expect(Socket.instances).toHaveLength(1)
  })
})
