// @vitest-environment jsdom
import { QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createAppQueryClient } from '../app/queryClient'
import { I18nProvider } from '../i18n'
import type { NotificationChannelResponse, TestNotificationResponse } from '../types/notificationChannel'
import { NotificationChannelsPage } from './NotificationChannelsPage'

const emailChannel: NotificationChannelResponse = {
  id: 'c1', name: 'Mail alerts', type: 'EMAIL', enabled: true,
  events: ['INCIDENT_OPENED'], reasons: ['THRESHOLD'],
  config: { credentialConfigured: true, smtpHost: 'smtp.example.test', smtpPort: 587,
    security: 'STARTTLS', username: 'alerts', fromAddress: 'alerts@example.test', recipients: ['ops@example.test'] },
  createdAt: '', updatedAt: '',
}

type RequestRecord = { url: string; method: string; body?: string }

function json(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
}

function setup(options: {
  role?: 'OWNER' | 'MEMBER'
  channels?: NotificationChannelResponse[]
  listError?: boolean
  testResults?: TestNotificationResponse[]
  testResponse?: Promise<Response>
  membershipResponse?: Promise<Response>
} = {}) {
  const client = createAppQueryClient()
  const requests: RequestRecord[] = []
  const testResults = [...(options.testResults ?? [{ status: 'SENT', code: null } as TestNotificationResponse])]
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input); const method = init?.method ?? 'GET'
    requests.push({ url, method, body: init?.body as string | undefined })
    if (url === '/api/v1/me/organizations') {
      return options.membershipResponse ?? json([{ id: 'org', code: 'ORG', name: 'Org', role: options.role ?? 'OWNER' }])
    }
    if (url.includes('/projects')) return json([])
    if (url.endsWith('/notification-channels') && method === 'GET') {
      return options.listError ? json({ code: 'INTERNAL_ERROR', message: 'failed' }, 500) : json(options.channels ?? [emailChannel])
    }
    if (url.endsWith('/disable')) return json({ ...emailChannel, enabled: false })
    if (url.endsWith('/enable')) return json({ ...emailChannel, enabled: true })
    if (url.endsWith('/test')) return options.testResponse ?? json(testResults.shift() ?? { status: 'SENT', code: null })
    throw new Error(`Unexpected request ${method} ${url}`)
  }))
  render(<I18nProvider initialLocale="en"><QueryClientProvider client={client}>
    <MemoryRouter initialEntries={['/organizations/org/notifications']}><Routes>
      <Route path="/organizations/:organizationId/notifications" element={<NotificationChannelsPage />} />
    </Routes></MemoryRouter>
  </QueryClientProvider></I18nProvider>)
  return { client, requests }
}

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

describe('notification channels page', () => {
  it('uses production query settings and loads the complete list in one request', async () => {
    const webhook: NotificationChannelResponse = { ...emailChannel, id: 'w', name: 'Automation', type: 'WEBHOOK', config: { credentialConfigured: true } }
    const telegram: NotificationChannelResponse = { ...emailChannel, id: 't', name: 'Ops Telegram', type: 'TELEGRAM', config: { credentialConfigured: true, chatId: '-100123' } }
    const { client, requests } = setup({ channels: [webhook, telegram, emailChannel] })
    expect(client.getDefaultOptions().queries).toMatchObject({ retry: 1, refetchOnWindowFocus: false })
    expect(await screen.findByText('Mail alerts')).toBeTruthy()
    expect(screen.getByText('Automation')).toBeTruthy()
    expect(screen.getByText('Ops Telegram')).toBeTruthy()
    expect(requests.filter(request => request.method === 'GET' && request.url.endsWith('/notification-channels'))).toHaveLength(1)
    expect(screen.getByText('smtp.example.test:587 · STARTTLS')).toBeTruthy()
    expect(screen.getByText('ops@example.test')).toBeTruthy()
    expect(document.body.textContent).not.toContain('password')
  })

  it('waits for permission loading before deciding access or requesting channels', async () => {
    let resolveMembership!: (response: Response) => void
    const membershipResponse = new Promise<Response>(resolve => { resolveMembership = resolve })
    const { requests } = setup({ membershipResponse })
    expect(screen.getByRole('status').textContent).toContain('Checking permissions')
    expect(screen.queryByText('You do not have permission to manage notification channels.')).toBeNull()
    expect(requests.some(request => request.url.endsWith('/notification-channels'))).toBe(false)
    resolveMembership(json([{ id: 'org', code: 'ORG', name: 'Org', role: 'OWNER' }]))
    expect(await screen.findByText('Mail alerts')).toBeTruthy()
  })

  it('denies a member without making the privileged list request', async () => {
    const { requests } = setup({ role: 'MEMBER' })
    expect(await screen.findByText('You do not have permission to manage notification channels.')).toBeTruthy()
    expect(requests.some(request => request.url.endsWith('/notification-channels'))).toBe(false)
  })

  it('renders empty and load-error states', async () => {
    setup({ channels: [] })
    expect(await screen.findByText('No notification channels')).toBeTruthy()
    cleanup(); vi.unstubAllGlobals()
    const { requests } = setup({ listError: true })
    expect(await screen.findByText('Unable to load notification channels', {}, { timeout: 3_000 })).toBeTruthy()
    expect(requests.filter(request => request.url.endsWith('/notification-channels'))).toHaveLength(2)
  })

  it('uses lifecycle responses to update cache without a list refetch', async () => {
    const { requests } = setup()
    fireEvent.click(await screen.findByRole('button', { name: 'Disable' }))
    expect(await screen.findByText('Disabled')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Enable' }))
    await waitFor(() => expect(screen.getByText('Enabled')).toBeTruthy())
    expect(requests.filter(request => request.url.endsWith('/disable'))).toHaveLength(1)
    expect(requests.filter(request => request.url.endsWith('/enable'))).toHaveLength(1)
    expect(requests.filter(request => request.method === 'GET' && request.url.endsWith('/notification-channels'))).toHaveLength(1)
  })

  it.each([
    [{ status: 'RETRYABLE_FAILURE', code: 'HTTP_503' }, 'The destination is temporarily unavailable. Try again later. The webhook is temporarily unavailable.'],
    [{ status: 'PERMANENT_FAILURE', code: 'UNEXPECTED_RESPONSE' }, 'The channel configuration needs attention. The webhook returned an unexpected redirect.'],
  ] as const)('shows a safe localized test result without refetching', async (result, message) => {
    const { requests } = setup({ testResults: [result] })
    fireEvent.click(await screen.findByRole('button', { name: 'Send test' }))
    expect(await screen.findByText(message)).toBeTruthy()
    expect(requests.filter(request => request.url.endsWith('/test'))).toHaveLength(1)
    expect(requests.filter(request => request.method === 'GET' && request.url.endsWith('/notification-channels'))).toHaveLength(1)
  })

  it('blocks a double click while test-send is pending', async () => {
    let resolveTest!: (response: Response) => void
    const pending = new Promise<Response>(resolve => { resolveTest = resolve })
    const { requests } = setup({ testResponse: pending })
    const button = await screen.findByRole('button', { name: 'Send test' })
    fireEvent.click(button)
    fireEvent.click(button)
    await waitFor(() => expect(requests.filter(request => request.url.endsWith('/test'))).toHaveLength(1))
    resolveTest(json({ status: 'SENT', code: null }))
    expect(await screen.findByText('Test notification sent.')).toBeTruthy()
  })
})
