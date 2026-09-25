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
  it('presents the private key first for a new connection and keeps password available', () => {
    const html = render()

    expect(html).toContain('1. Сервер')
    expect(html).toContain('2. Аутентификация')
    expect(html).toContain('3. Подлинность сервера')
    expect(html).toContain('4. Синхронизация')
    expect(html).toMatch(/checked="" value="PRIVATE_KEY"/)
    expect(html).toContain('Приватный ключ')
    expect(html).toContain('Пароль')
    expect(html).toContain('<textarea')
    // A new connection has nothing stored yet.
    expect(html).not.toContain('настроен')
  })

  it('keeps an existing password connection on password, with its stored credential untouched', () => {
    const html = render(connection({ authenticationType: 'PASSWORD' }))

    expect(html).toMatch(/checked="" value="PASSWORD"/)
    expect(html).toContain('type="password"')
    expect(html).not.toContain('<textarea')
    expect(html).toContain('Пароль настроен')
    expect(html).toContain('Оставьте поле пустым, чтобы сохранить текущий ключ или пароль.')
  })

  it('shows the private key and its optional passphrase for a key connection, never the stored key', () => {
    const html = render(connection({ authenticationType: 'PRIVATE_KEY' }))

    expect(html).toContain('<textarea')
    expect(html).toContain('Парольная фраза (если есть)')
    expect(html).toContain('Оставьте поле пустым, чтобы сохранить текущий ключ или пароль.')
    // The form never receives the stored credential, and does not pretend to with a fake mask.
    expect(html).not.toContain('BEGIN OPENSSH')
    expect(html).not.toContain('•••')
    expect(html).not.toContain('****')
  })

  it('explains why the fingerprint exists and states whether the server is confirmed', () => {
    const trusted = render(connection())
    const unverified = render()

    expect(unverified).toContain('Отпечаток подтверждает, что InfraDesk подключается именно к нужному серверу')
    expect(trusted).toContain('SHA256:trusted')
    expect(trusted).toContain('trust-state trust-trusted')
    expect(trusted).toContain('Сервер подтверждён')
    expect(unverified).toContain('trust-state trust-untrusted')
    expect(unverified).toContain('Сервер не подтверждён')
    expect(unverified).toContain('Получить ключ сервера')
    expect(trusted).not.toContain('value="secret"')
  })

  it('cannot test a connection before the host identity is confirmed', () => {
    const html = render()

    expect(html).toContain('Проверить подключение')
    expect(html).toMatch(/<button class="secondary-button" type="button" aria-busy="false" disabled="">Проверить подключение/)
  })
})
