import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it } from 'vitest'

import { ConnectionFormPage } from './ConnectionFormPage'
import type { ConnectionResponse } from '../types/connection'

function connection(overrides: Partial<ConnectionResponse['ssh']> = {}): ConnectionResponse {
  return {
    id: 'connection',
    connectorType: 'SSH',
    code: 'ssh',
    name: 'SSH host',
    scope: { type: 'ORGANIZATION' },
    active: true,
    schedule: { enabled: true, intervalSeconds: 600, nextRunAt: '2026-09-24T10:00:00Z' },
    lastSync: null,
    createdAt: '2026-09-24T10:00:00Z',
    updatedAt: '2026-09-24T10:00:00Z',
    ssh: {
      host: 'example.test',
      port: 22,
      username: 'infradesk',
      hostKeyFingerprint: 'SHA256:trusted',
      credentialConfigured: true,
      authenticationType: 'PASSWORD',
      ...overrides,
    },
  } as ConnectionResponse
}

function render(existing?: ConnectionResponse) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  client.setQueryData(['my-organizations'], [{ id: 'org', code: 'ORG', name: 'Org', role: 'OWNER' }])
  client.setQueryData(['projects', 'org'], [])
  if (existing) {
    client.setQueryData(['connection', 'org', 'connection'], existing)
  }

  const path = existing ? '/organizations/org/connections/connection/edit' : '/organizations/org/connections/new'
  const route = existing
    ? '/organizations/:organizationId/connections/:connectionId/edit'
    : '/organizations/:organizationId/connections/new'

  return renderToStaticMarkup(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes><Route path={route} element={<ConnectionFormPage />} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('SSH connection form', () => {
  it('offers both authentication methods and shows the password fields by default', () => {
    const html = render()

    expect(html).toContain('>Пароль</option>')
    expect(html).toContain('>Приватный ключ</option>')
    expect(html).toContain('type="password"')
    // The private key field only appears once that method is selected.
    expect(html).not.toContain('<textarea')
  })

  it('shows the private key and its optional passphrase for a key connection', () => {
    const html = render(connection({ authenticationType: 'PRIVATE_KEY' }))

    expect(html).toContain('<textarea')
    expect(html).toContain('Парольная фраза (если есть)')
    // An existing credential is kept when the field is left blank.
    expect(html).toContain('Оставьте поле пустым, чтобы сохранить текущий ключ или пароль.')
    // The form never receives the stored credential to display.
    expect(html).not.toContain('BEGIN OPENSSH')
  })

  it('states the host identity and never renders a credential', () => {
    const trusted = render(connection())
    const unverified = render()

    expect(trusted).toContain('SHA256:trusted')
    expect(trusted).toContain('Сервер подтверждён')
    expect(unverified).toContain('Сервер не подтверждён')
    expect(unverified).toContain('Получить ключ сервера')
    expect(trusted).not.toContain('value="secret"')
  })

  it('cannot test a connection before the host identity is confirmed', () => {
    const html = render()

    // Both the test button and the probe button are present, and testing starts disabled.
    expect(html).toContain('Проверить подключение')
    expect(html).toContain('disabled=""')
  })
})
