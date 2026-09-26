// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { I18nProvider } from '../i18n'
import { NotificationChannelFormPage } from './NotificationChannelFormPage'

describe('notification channel form', () => {
  it('sends only the selected typed configuration and omits enabled from updates', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
    client.setQueryData(['my-organizations'], [{ id: 'org', code: 'ORG', name: 'Org', role: 'OWNER' }])
    client.setQueryData(['projects', 'org'], [])
    const existing = { id: 'c1', name: 'Ops', type: 'TELEGRAM', enabled: false, events: ['INCIDENT_OPENED'], reasons: ['THRESHOLD'],
      config: { credentialConfigured: true, chatId: '-123' }, createdAt: '', updatedAt: '' }
    client.setQueryData(['notification-channel', 'org', 'c1'], existing)
    let requestBody = ''
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      requestBody = String(init?.body ?? '')
      return new Response(JSON.stringify({ ...existing, name: 'Ops revised' }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<I18nProvider initialLocale="en"><QueryClientProvider client={client}><MemoryRouter initialEntries={['/organizations/org/notifications/c1/edit']}>
      <Routes><Route path="/organizations/:organizationId/notifications/:channelId/edit" element={<NotificationChannelFormPage />} />
        <Route path="/organizations/:organizationId/notifications" element={<div>Saved channel list</div>} /></Routes>
    </MemoryRouter></QueryClientProvider></I18nProvider>)
    fireEvent.change(await screen.findByLabelText('Name'), { target: { value: 'Ops revised' } })
    expect((screen.getByLabelText('Bot token') as HTMLInputElement).value).toBe('')
    expect(document.body.textContent).not.toContain('fake-secret')
    fireEvent.change(screen.getByLabelText('Bot token'), { target: { value: 'very-secret-token' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save channel' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledOnce())
    const body = JSON.parse(requestBody)
    expect(body).toEqual({ name: 'Ops revised', type: 'TELEGRAM', events: ['INCIDENT_OPENED'], reasons: ['THRESHOLD'], telegram: { chatId: '-123', botToken: 'very-secret-token' } })
    expect(body).not.toHaveProperty('enabled')
    expect(body).not.toHaveProperty('email')
    expect(client.getMutationCache().getAll().every(mutation => mutation.state.variables === undefined)).toBe(true)
    expect(JSON.stringify(client.getMutationCache().getAll())).not.toContain('very-secret-token')
    vi.unstubAllGlobals()
  })
})
