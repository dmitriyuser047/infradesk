// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { I18nProvider } from '../../i18n'
import { AppShell } from '../layout/AppShell'
import type { EmulatorModules } from './terminalSession'
import { TerminalWorkspace } from './terminalWorkspace'
import { TerminalWorkspaceProvider } from './TerminalWorkspaceProvider'

class Socket {
  static instances: Socket[] = []
  binaryType = ''; protocol = 'infradesk-terminal-v1'; readyState = 1; bufferedAmount = 0
  onopen = null; onmessage: ((event: { data: unknown }) => void) | null = null; onclose = null; onerror = null
  send = vi.fn(); close = vi.fn(() => { this.readyState = 3; events.push('socket.close') })
  constructor() { Socket.instances.push(this) }
  ready() { this.onmessage?.({ data: JSON.stringify({ type: 'ready', protocolVersion: 1, sessionId: '00000000-0000-0000-0000-000000000001' }) }) }
}
const modules = {
  Terminal: class {
    cols = 80; rows = 24; parser = { registerOscHandler: () => ({ dispose() {} }) }
    attachCustomKeyEventHandler() {}
    open() {} loadAddon() {} onData() { return { dispose() {} } } write() {} focus() {} dispose() {} resize() {}
  },
  FitAddon: class { fit() {} },
} as unknown as EmulatorModules
let events: string[] = []

function shell(workspace: TerminalWorkspace) {
  const queries = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
  queries.setQueryData(['me'], { id: 'user', email: 'dmitriy@example.com', displayName: 'Dmitriy' })
  queries.setQueryData(['my-organizations'], [{ id: 'org', code: 'ORG', name: 'InfraDesk', role: 'OWNER' }])
  queries.setQueryData(['projects', 'org'], [])
  return render(<I18nProvider initialLocale="en"><QueryClientProvider client={queries}><TerminalWorkspaceProvider workspace={workspace}>
    <MemoryRouter initialEntries={['/organizations/org/overview']}><Routes>
      <Route path="/organizations/:organizationId/overview" element={<AppShell><h1>overview</h1></AppShell>} />
      <Route path="/login" element={<h1>login</h1>} />
    </Routes></MemoryRouter>
  </TerminalWorkspaceProvider></QueryClientProvider></I18nProvider>)
}

async function openTerminal(workspace: TerminalWorkspace, id: string, name: string) {
  act(() => { workspace.open('org', { id, name }) })
  await act(async () => { await Promise.resolve(); await Promise.resolve() })
  act(() => Socket.instances[Socket.instances.length - 1].ready())
}

let workspace: TerminalWorkspace
beforeEach(() => {
  events = []
  Socket.instances = []
  vi.stubGlobal('WebSocket', Socket)
  workspace = new TerminalWorkspace({ loadEmulator: async () => modules })
})
afterEach(() => { cleanup(); vi.unstubAllGlobals() })

describe('workspace dock', () => {
  it('appears inside the application only while a terminal is open, and makes room for itself', async () => {
    const view = shell(workspace)
    expect(screen.queryByRole('navigation', { name: 'Open terminals' })).toBeNull()
    expect(view.container.querySelector('.app-shell')?.classList.contains('has-dock')).toBe(false)

    await openTerminal(workspace, 'finnish', 'Finnish Node')
    const dock = screen.getByRole('navigation', { name: 'Open terminals' })
    // The status is words, not only a colour.
    expect(within(dock).getByText('Connected')).toBeTruthy()
    expect(within(dock).getByRole('link', { name: 'Terminal Finnish Node, Connected' }).getAttribute('href'))
      .toBe('/organizations/org/connections/finnish?tab=terminal')
    expect(within(dock).getByRole('button', { name: 'Close terminal Finnish Node' })).toBeTruthy()
    expect(view.container.querySelector('.app-shell')?.classList.contains('has-dock')).toBe(true)
  })

  it('ends every terminal before signing out, without waiting for them', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => {
      if (String(url).endsWith('/auth/logout')) events.push('logout')
      return Promise.resolve(new Response(String(url).endsWith('/auth/logout') ? null : '[]',
        { status: String(url).endsWith('/auth/logout') ? 204 : 200, headers: { 'Content-Type': 'application/json' } }))
    }))
    shell(workspace)
    await openTerminal(workspace, 'finnish', 'Finnish Node')
    await openTerminal(workspace, 'german', 'German Node')

    fireEvent.click(screen.getByRole('button', { name: 'Account menu for Dmitriy' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Sign out' }))
    await waitFor(() => expect(screen.getByRole('heading', { name: 'login' })).toBeTruthy())
    expect(events).toEqual(['socket.close', 'socket.close', 'logout'])
    expect(workspace.getSnapshot()).toEqual([])
  })
})
