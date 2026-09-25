// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { I18nProvider } from '../i18n'
import type { ConnectionResponse } from '../types/connection'
import { ConnectionFormPage } from './ConnectionFormPage'

const trusted = 'SHA256:trusted-key'

function existing(): ConnectionResponse {
  return {
    id: 'connection', connectorType: 'SSH', code: 'ssh', name: 'finland_node', scope: { type: 'ORGANIZATION' },
    active: true, schedule: { enabled: true, intervalSeconds: 600, nextRunAt: '2026-09-24T10:00:00Z' },
    lastSync: null, createdAt: '', updatedAt: '',
    ssh: { host: 'example.test', port: 22, username: 'deploy', hostKeyFingerprint: trusted,
      credentialConfigured: true, authenticationType: 'PRIVATE_KEY', hostTrusted: true },
  }
}

function renderForm() {
  // Seeded data stays fresh, so the only request the test sees is the one the form makes.
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
  client.setQueryData(['my-organizations'], [{ id: 'org', code: 'ORG', name: 'Org', role: 'OWNER' }])
  client.setQueryData(['me'], { id: 'user', email: 'owner@example.com', displayName: 'Owner' })
  client.setQueryData(['projects', 'org'], [])
  client.setQueryData(['connection', 'org', 'connection'], existing())
  render(<I18nProvider initialLocale="ru"><QueryClientProvider client={client}>
    <MemoryRouter initialEntries={['/organizations/org/connections/connection/edit']}><Routes>
      <Route path="/organizations/:organizationId/connections/:connectionId/edit" element={<ConnectionFormPage />} />
    </Routes></MemoryRouter>
  </QueryClientProvider></I18nProvider>)
}

describe('server trust in the connection form', () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals() })

  it('shows a changed server key as a blocking warning and never replaces the trusted key by itself', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ hostKeyFingerprint: 'SHA256:other-key' }),
      { status: 200, headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)
    renderForm()

    await act(async () => { fireEvent.click(screen.getByRole('button', { name: /Получить ключ сервера/ })) })

    await waitFor(() => expect(document.body.textContent).toContain('Ключ сервера изменился'), { timeout: 3000 })
    // Reading the key sends no credential.
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/v1/organizations/org/connections/ssh/host-key')
    expect(JSON.parse(String(init.body))).toEqual({ host: 'example.test', port: 22, username: 'deploy' })
    // The trusted fingerprint is untouched until the person explicitly replaces it.
    expect(screen.getByLabelText('Подтверждённый отпечаток сервера').textContent).toBe(trusted)
    expect(screen.getByText('SHA256:other-key')).toBeTruthy()
    expect(document.querySelector('.trust-state')?.className).toContain('trust-mismatch')

    fireEvent.click(screen.getByRole('button', { name: 'Заменить подтверждённый ключ' }))
    expect(screen.getByLabelText('Подтверждённый отпечаток сервера').textContent).toBe('SHA256:other-key')
  })

  it('describes a failed key read by its code, in Russian', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(
      JSON.stringify({ code: 'SSH_CONNECT_TIMEOUT', message: 'SSH connection timed out' }),
      { status: 502, headers: { 'Content-Type': 'application/json' } })))
    renderForm()

    await act(async () => { fireEvent.click(screen.getByRole('button', { name: /Получить ключ сервера/ })) })

    await waitFor(() => expect(screen.getByText('Сервер не ответил вовремя.')).toBeTruthy())
    expect(screen.queryByText('SSH connection timed out')).toBeNull()
  })

  describe('after the address changes', () => {
    const state = () => document.querySelector('.trust-state')
    const confirmedKey = () => screen.getByLabelText('Подтверждённый отпечаток сервера').textContent
    const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
    const change = (label: string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } })

    function expectNeedsVerification() {
      expect(state()?.className).toContain('trust-changed')
      expect(state()?.textContent).toContain('Адрес сервера изменён')
      expect(state()?.textContent).toContain('example.test:22')
      expect(confirmedKey()).toBe('Не подтверждён')
      expect(document.body.textContent).not.toContain('Сервер подтверждён')
    }

    it('does not trust a changed host, and saving it is refused before any request', () => {
      const fetchMock = vi.fn()
      vi.stubGlobal('fetch', fetchMock)
      renderForm()
      expect(state()?.className).toContain('trust-trusted')

      change('Адрес', 'other.test')
      expectNeedsVerification()
      expect((screen.getByRole('button', { name: 'Проверить подключение' }) as HTMLButtonElement).disabled).toBe(true)

      fireEvent.click(screen.getByRole('button', { name: 'Сохранить подключение' }))
      expect(screen.getByText('Подтвердите отпечаток сервера перед сохранением.')).toBeTruthy()
      expect(fetchMock).not.toHaveBeenCalled()
    })

    it('does not trust a changed port', () => {
      vi.stubGlobal('fetch', vi.fn())
      renderForm()

      change('Порт', '2222')
      expectNeedsVerification()
    })

    it('trusts the new address only after its key is read and explicitly confirmed', async () => {
      const fetchMock = vi.fn()
        .mockResolvedValueOnce(json({ hostKeyFingerprint: 'SHA256:new-host-key' }))
        .mockResolvedValueOnce(json({ ...existing(), ssh: { ...existing().ssh, host: 'other.test', hostKeyFingerprint: 'SHA256:new-host-key' } }))
      vi.stubGlobal('fetch', fetchMock)
      renderForm()
      change('Адрес', 'other.test')

      await act(async () => { fireEvent.click(screen.getByRole('button', { name: /Получить ключ сервера/ })) })
      await waitFor(() => expect(screen.getByText('SHA256:new-host-key')).toBeTruthy())
      // The key was read at the new address, without any credential, and is not trusted yet.
      expect(JSON.parse(String((fetchMock.mock.calls[0] as [string, RequestInit])[1].body)))
        .toEqual({ host: 'other.test', port: 22, username: 'deploy' })
      expectNeedsVerification()

      fireEvent.click(screen.getByRole('button', { name: 'Подтвердить сервер' }))
      expect(state()?.className).toContain('trust-trusted')
      expect(confirmedKey()).toBe('SHA256:new-host-key')

      await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Сохранить подключение' })) })
      const saved = JSON.parse(String((fetchMock.mock.calls[1] as [string, RequestInit])[1].body))
      expect(saved.ssh).toMatchObject({ host: 'other.test', port: 22, hostKeyFingerprint: 'SHA256:new-host-key' })
    })

    it('restores the stored trust when the original address comes back', () => {
      vi.stubGlobal('fetch', vi.fn())
      renderForm()

      change('Адрес', 'other.test')
      change('Порт', '2222')
      expectNeedsVerification()
      change('Адрес', 'example.test')
      change('Порт', '22')

      expect(state()?.className).toContain('trust-trusted')
      expect(confirmedKey()).toBe(trusted)
    })
  })
})
