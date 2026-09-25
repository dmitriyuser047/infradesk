import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it } from 'vitest'

import type { ConnectionResponse } from '../types/connection'
import { ConnectionsPage } from './ConnectionsPage'

function connection(name: string, overrides: Partial<ConnectionResponse> = {}, ssh: Partial<NonNullable<ConnectionResponse['ssh']>> = {}): ConnectionResponse {
  return {
    id: name, connectorType: 'SSH', code: name, name, scope: { type: 'ORGANIZATION' }, active: true,
    schedule: null, lastSync: null, createdAt: '', updatedAt: '',
    ssh: { host: `${name}.example`, port: 22, username: 'deploy', hostKeyFingerprint: 'SHA256:k',
      credentialConfigured: true, authenticationType: 'PRIVATE_KEY', hostTrusted: true, ...ssh },
    ...overrides,
  }
}

function render(role: 'OWNER' | 'MEMBER', connections: ConnectionResponse[]): string {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  client.setQueryData(['me'], { id: 'user', email: 'a@example.com', displayName: 'Dmitriy' })
  client.setQueryData(['my-organizations'], [{ id: 'org', code: 'ORG', name: 'Northwind', role }])
  client.setQueryData(['projects', 'org'], [])
  client.setQueryData(['connections', 'org'], connections)
  return renderToStaticMarkup(<QueryClientProvider client={client}>
    <MemoryRouter initialEntries={['/organizations/org/connections']}><Routes>
      <Route path="/organizations/:organizationId/connections" element={<ConnectionsPage />} />
    </Routes></MemoryRouter>
  </QueryClientProvider>)
}

describe('connections list', () => {
  it('shows where each connection points and whether that server is confirmed', () => {
    const html = render('OWNER', [
      connection('trusted'),
      connection('untrusted', {}, { hostKeyFingerprint: null, hostTrusted: false, port: 2222 }),
      connection('changed', { lastSync: { id: 's', status: 'FAILED', startedAt: '2026-09-25T10:00:00Z',
        finishedAt: '2026-09-25T10:00:02Z', errorCode: 'SSH_HOST_KEY_MISMATCH', errorMessage: 'SSH host key has changed' } }),
    ])

    expect(html).toContain('deploy@trusted.example')
    expect(html).toContain('deploy@untrusted.example:2222')
    expect(html).toMatch(/trusted<\/a>.*Сервер подтверждён/s)
    expect(html).toMatch(/untrusted<\/a>.*Сервер не подтверждён/s)
    // A connection blocked by a changed key is never shown as confirmed.
    expect(html).toMatch(/changed<\/a>.*status-danger.*Ключ сервера изменился/s)
    expect(html).not.toContain('SHA256:k')
  })

  it('offers "Add connection" to an owner and explains the empty state to a member', () => {
    expect(render('OWNER', [])).toContain('href="/organizations/org/connections/new"')
    const member = render('MEMBER', [])
    expect(member).toContain('Подключений пока нет')
    expect(member).toContain('Владелец организации ещё не добавил подключений.')
    expect(member).not.toContain('/connections/new')
  })
})
