// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { I18nProvider } from '../i18n'
import { NotificationChannelsPage } from './NotificationChannelsPage'

const channel = { id: 'c1', name: 'Mail alerts', type: 'EMAIL', enabled: true, events: ['INCIDENT_OPENED'], reasons: ['THRESHOLD'],
  config: { credentialConfigured: true, smtpHost: 'smtp.example.test', smtpPort: 587, security: 'STARTTLS', username: 'alerts', fromAddress: 'alerts@example.test', recipients: ['ops@example.test'] }, createdAt: '', updatedAt: '' }
function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
  client.setQueryData(['my-organizations'], [{ id: 'org', code: 'ORG', name: 'Org', role: 'OWNER' }])
  const requests: { url: string; method: string; body?: string }[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input); const method = init?.method ?? 'GET'; requests.push({ url, method, body: init?.body as string | undefined })
    const payload = method === 'POST' ? { status: 'SENT', code: null } : [channel]
    return new Response(JSON.stringify(payload), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }))
  render(<I18nProvider initialLocale="en"><QueryClientProvider client={client}><MemoryRouter initialEntries={['/organizations/org/notifications']}>
    <Routes><Route path="/organizations/:organizationId/notifications" element={<NotificationChannelsPage />} /></Routes>
  </MemoryRouter></QueryClientProvider></I18nProvider>)
  return { requests }
}
afterEach(() => vi.unstubAllGlobals())

describe('notification channels page', () => {
  it('loads the list in one request and shows safe typed details', async () => {
    const { requests } = setup()
    expect(await screen.findByText('Mail alerts')).toBeTruthy()
    expect(requests.filter(request => request.method === 'GET' && request.url.includes('notification-channels'))).toHaveLength(1)
    expect(screen.getByText('smtp.example.test:587 · STARTTLS')).toBeTruthy()
    expect(screen.getByText('ops@example.test')).toBeTruthy()
    expect(document.body.textContent).not.toContain('password')
  })

  it('sends one test request and does not refetch list afterward', async () => {
    const { requests } = setup()
    fireEvent.click(await screen.findByRole('button', { name: 'Send test' }))
    await screen.findByText('Test notification sent.')
    await waitFor(() => expect(requests.filter(request => request.method === 'POST')).toHaveLength(1))
    expect(requests.filter(request => request.method === 'GET' && request.url.endsWith('/notification-channels'))).toHaveLength(1)
    expect(requests.at(-1)?.url).toContain('/c1/test')
  })
})
