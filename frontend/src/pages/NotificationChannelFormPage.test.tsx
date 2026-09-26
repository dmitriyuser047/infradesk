// @vitest-environment jsdom
import { QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { notificationChannelsKey } from '../api/notificationChannels'
import { createAppQueryClient } from '../app/queryClient'
import { I18nProvider } from '../i18n'
import type { NotificationChannelResponse, SaveNotificationChannelRequest } from '../types/notificationChannel'
import { NotificationChannelFormPage } from './NotificationChannelFormPage'
import { NotificationChannelsPage } from './NotificationChannelsPage'

const telegram: NotificationChannelResponse = {
  id: 'c1', name: 'Ops Telegram', type: 'TELEGRAM', enabled: false,
  events: ['INCIDENT_OPENED'], reasons: ['THRESHOLD'],
  config: { credentialConfigured: true, chatId: '-123' }, createdAt: '', updatedAt: '',
}
const webhook: NotificationChannelResponse = {
  ...telegram, name: 'Automation webhook', type: 'WEBHOOK', config: { credentialConfigured: true },
}
const email: NotificationChannelResponse = {
  ...telegram, name: 'Mail alerts', type: 'EMAIL',
  config: { credentialConfigured: true, smtpHost: 'smtp.example.test', smtpPort: 587,
    security: 'STARTTLS', username: 'alerts', fromAddress: 'alerts@example.test', recipients: ['ops@example.test'] },
}

type RequestRecord = { url: string; method: string; body?: string }

function json(value: unknown): Response {
  return new Response(JSON.stringify(value), { status: 200, headers: { 'Content-Type': 'application/json' } })
}

function responseFor(body: SaveNotificationChannelRequest, id: string): NotificationChannelResponse {
  const config = body.type === 'WEBHOOK' ? { credentialConfigured: true }
    : body.type === 'TELEGRAM' ? { credentialConfigured: true, chatId: body.telegram!.chatId }
      : { credentialConfigured: true, smtpHost: body.email!.smtpHost, smtpPort: body.email!.smtpPort,
        security: body.email!.security, username: body.email!.username,
        fromAddress: body.email!.fromAddress, recipients: body.email!.recipients }
  return { id, name: body.name, type: body.type, enabled: body.enabled ?? false,
    events: body.events, reasons: body.reasons, config, createdAt: '', updatedAt: '' }
}

function setup(existing?: NotificationChannelResponse, saveGate?: Promise<void>) {
  const client = createAppQueryClient()
  const initialList = existing ? [existing] : []
  client.setQueryData(notificationChannelsKey('org'), initialList, { updatedAt: Date.now() - 60_000 })
  const requests: RequestRecord[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input); const method = init?.method ?? 'GET'
    requests.push({ url, method, body: init?.body as string | undefined })
    if (url === '/api/v1/me/organizations') return json([{ id: 'org', code: 'ORG', name: 'Org', role: 'OWNER' }])
    if (url.includes('/projects')) return json([])
    if (method === 'GET' && /notification-channels\/[^/]+$/.test(url)) return json(existing)
    if ((method === 'POST' || method === 'PUT') && url.includes('/notification-channels')) {
      const body = JSON.parse(String(init?.body)) as SaveNotificationChannelRequest
      if (saveGate) await saveGate
      return json(responseFor(body, existing?.id ?? 'created'))
    }
    if (method === 'GET' && url.endsWith('/notification-channels')) return json(initialList)
    throw new Error(`Unexpected request ${method} ${url}`)
  }))
  const entry = existing ? `/organizations/org/notifications/${existing.id}/edit` : '/organizations/org/notifications/new'
  render(<I18nProvider initialLocale="en"><QueryClientProvider client={client}>
    <MemoryRouter initialEntries={[entry]}><Routes>
      <Route path="/organizations/:organizationId/notifications/new" element={<NotificationChannelFormPage />} />
      <Route path="/organizations/:organizationId/notifications/:channelId/edit" element={<NotificationChannelFormPage />} />
      <Route path="/organizations/:organizationId/notifications" element={<NotificationChannelsPage />} />
    </Routes></MemoryRouter>
  </QueryClientProvider></I18nProvider>)
  return { client, requests }
}

function notificationWrites(requests: RequestRecord[]): RequestRecord[] {
  return requests.filter(request => ['POST', 'PUT'].includes(request.method) && request.url.includes('/notification-channels'))
}

async function selectType(type: 'Webhook' | 'Telegram' | 'Email') {
  fireEvent.change(await screen.findByLabelText('Channel type'), { target: { value: type.toUpperCase() } })
}

async function fillEmail(recipients = 'ops@example.test\nadmin@example.test') {
  await selectType('Email')
  fireEvent.change(screen.getByLabelText('SMTP host'), { target: { value: 'smtp.example.test' } })
  fireEvent.change(screen.getByLabelText('SMTP port'), { target: { value: '465' } })
  fireEvent.change(screen.getByLabelText('Security'), { target: { value: 'TLS' } })
  fireEvent.change(screen.getByLabelText('Username'), { target: { value: 'alerts' } })
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'smtp-secret' } })
  fireEvent.change(screen.getByLabelText('From address'), { target: { value: 'alerts@example.test' } })
  fireEvent.change(screen.getByLabelText('Recipients'), { target: { value: recipients } })
}

async function submitAndBody(requests: RequestRecord[]): Promise<SaveNotificationChannelRequest> {
  fireEvent.click(screen.getByRole('button', { name: 'Save channel' }))
  await waitFor(() => expect(notificationWrites(requests)).toHaveLength(1))
  return JSON.parse(notificationWrites(requests)[0].body!) as SaveNotificationChannelRequest
}

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

describe('notification channel create', () => {
  it('creates a webhook with the exact selected configuration and no list refetch', async () => {
    const { requests } = setup()
    fireEvent.change(await screen.findByLabelText('Name'), { target: { value: 'Automation' } })
    fireEvent.change(screen.getByLabelText('Webhook URL'), { target: { value: 'https://hooks.example.test/private' } })
    expect(await submitAndBody(requests)).toEqual({ name: 'Automation', type: 'WEBHOOK', enabled: true,
      events: ['INCIDENT_OPENED'], reasons: ['THRESHOLD'], webhook: { url: 'https://hooks.example.test/private' } })
    expect(await screen.findByText('Automation')).toBeTruthy()
    expect(requests.filter(request => request.method === 'GET' && request.url.endsWith('/notification-channels'))).toHaveLength(0)
  })

  it('creates Telegram and leaves its secret out of mutation cache', async () => {
    const { client, requests } = setup()
    fireEvent.change(await screen.findByLabelText('Name'), { target: { value: 'Ops bot' } })
    await selectType('Telegram')
    fireEvent.change(screen.getByLabelText('Chat ID'), { target: { value: '-100123' } })
    fireEvent.change(screen.getByLabelText('Bot token'), { target: { value: 'very-secret-token' } })
    expect(await submitAndBody(requests)).toEqual({ name: 'Ops bot', type: 'TELEGRAM', enabled: true,
      events: ['INCIDENT_OPENED'], reasons: ['THRESHOLD'], telegram: { chatId: '-100123', botToken: 'very-secret-token' } })
    expect(await screen.findByText('Ops bot')).toBeTruthy()
    await waitFor(() => expect(JSON.stringify(client.getMutationCache().getAll())).not.toContain('very-secret-token'))
  })

  it('creates email with normalized recipients and only email configuration', async () => {
    const { requests } = setup()
    fireEvent.change(await screen.findByLabelText('Name'), { target: { value: 'Mail alerts' } })
    await fillEmail('ops@example.test\nadmin@example.test\nops@example.test')
    expect(await submitAndBody(requests)).toEqual({ name: 'Mail alerts', type: 'EMAIL', enabled: true,
      events: ['INCIDENT_OPENED'], reasons: ['THRESHOLD'], email: { smtpHost: 'smtp.example.test', smtpPort: 465,
        security: 'TLS', username: 'alerts', password: 'smtp-secret', fromAddress: 'alerts@example.test',
        recipients: ['ops@example.test', 'admin@example.test'] } })
  })

  it('allows only one create when two submits happen before React rerenders', async () => {
    let releaseSave!: () => void
    const saveGate = new Promise<void>(resolve => { releaseSave = resolve })
    const { requests } = setup(undefined, saveGate)
    fireEvent.change(await screen.findByLabelText('Name'), { target: { value: 'Automation' } })
    fireEvent.change(screen.getByLabelText('Webhook URL'), { target: { value: 'https://hooks.example.test/private' } })
    const form = screen.getByRole('button', { name: 'Save channel' }).closest('form')!

    act(() => {
      form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
      form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    })

    await waitFor(() => expect(notificationWrites(requests)).toHaveLength(1))
    releaseSave()
    expect(await screen.findByText('Automation')).toBeTruthy()
    expect(notificationWrites(requests)).toHaveLength(1)
  })
})

describe('notification channel edit', () => {
  it('sends a replacement Telegram token without lifecycle state or cached secret', async () => {
    const { client, requests } = setup(telegram)
    expect((await screen.findByLabelText('Bot token') as HTMLInputElement).value).toBe('')
    fireEvent.change(screen.getByLabelText('Bot token'), { target: { value: 'replacement-token' } })
    const body = await submitAndBody(requests)
    expect(body).toEqual({ name: 'Ops Telegram', type: 'TELEGRAM', events: ['INCIDENT_OPENED'],
      reasons: ['THRESHOLD'], telegram: { chatId: '-123', botToken: 'replacement-token' } })
    expect(body).not.toHaveProperty('enabled')
    expect(JSON.stringify(client.getMutationCache().getAll())).not.toContain('replacement-token')
  })

  it('keeps the stored webhook URL when the empty secret field is submitted', async () => {
    const { requests } = setup(webhook)
    expect((await screen.findByLabelText('Webhook URL') as HTMLInputElement).value).toBe('')
    expect(await submitAndBody(requests)).toEqual({ name: 'Automation webhook', type: 'WEBHOOK',
      events: ['INCIDENT_OPENED'], reasons: ['THRESHOLD'], webhook: {} })
    expect(notificationWrites(requests)[0].method).toBe('PUT')
    expect(requests.filter(request => request.method === 'GET' && request.url.endsWith('/notification-channels'))).toHaveLength(0)
  })

  it('keeps the stored email password when the password field stays empty', async () => {
    const { requests } = setup(email)
    expect((await screen.findByLabelText('Password') as HTMLInputElement).value).toBe('')
    const body = await submitAndBody(requests)
    expect(body.email).toEqual({ smtpHost: 'smtp.example.test', smtpPort: 587, security: 'STARTTLS',
      username: 'alerts', fromAddress: 'alerts@example.test', recipients: ['ops@example.test'] })
    expect(body).not.toHaveProperty('enabled')
  })

  it('requires a new credential when changing Telegram to email and sends no Telegram section', async () => {
    const { requests } = setup(telegram)
    await selectType('Email')
    fireEvent.change(screen.getByLabelText('SMTP host'), { target: { value: 'smtp.example.test' } })
    fireEvent.change(screen.getByLabelText('Username'), { target: { value: 'alerts' } })
    fireEvent.change(screen.getByLabelText('From address'), { target: { value: 'alerts@example.test' } })
    fireEvent.change(screen.getByLabelText('Recipients'), { target: { value: 'ops@example.test' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save channel' }))
    expect(await screen.findByText('Complete the channel configuration.')).toBeTruthy()
    expect(notificationWrites(requests)).toHaveLength(0)
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'new-password' } })
    const body = await submitAndBody(requests)
    expect(body.type).toBe('EMAIL')
    expect(body.email?.password).toBe('new-password')
    expect(body).not.toHaveProperty('telegram')
  })
})

describe('notification channel form validation and accessibility', () => {
  it('rejects more than 50 distinct recipients before a request', async () => {
    const { requests } = setup()
    fireEvent.change(await screen.findByLabelText('Name'), { target: { value: 'Mail alerts' } })
    await fillEmail(Array.from({ length: 51 }, (_, index) => `person${index}@example.test`).join('\n'))
    fireEvent.click(screen.getByRole('button', { name: 'Save channel' }))
    expect(await screen.findByText('Enter no more than 50 recipients.')).toBeTruthy()
    expect(notificationWrites(requests)).toHaveLength(0)
  })

  it('uses valid stable ID references for secret helper text', async () => {
    setup()
    await selectType('Telegram')
    const token = screen.getByLabelText('Bot token')
    const tokenHelp = token.getAttribute('aria-describedby')!
    expect(tokenHelp).not.toContain(' ')
    expect(document.getElementById(tokenHelp)?.textContent).toContain('credential')
    await selectType('Email')
    const password = screen.getByLabelText('Password')
    const passwordHelp = password.getAttribute('aria-describedby')!
    expect(passwordHelp).not.toContain(' ')
    expect(document.getElementById(passwordHelp)?.textContent).toContain('credential')
  })
})
