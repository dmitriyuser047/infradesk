// @vitest-environment jsdom
import { QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createAppQueryClient } from '../app/queryClient'
import { I18nProvider } from '../i18n'
import type { Locale } from '../i18n/types'
import type { SessionResponse } from '../types/auth'
import { AccountPage } from './AccountPage'
import { AccountMenu } from '../components/layout/AccountMenu'

type RequestRecord = { url: string; method: string; body?: string }

function json(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
}

const currentSession: SessionResponse = { id: 'sess-current', createdAt: '2026-09-27T08:00:00Z', expiresAt: '2026-10-04T08:00:00Z', current: true }
const otherSession: SessionResponse = { id: 'sess-other', createdAt: '2026-09-26T18:00:00Z', expiresAt: '2026-10-03T18:00:00Z', current: false }

function setup(options: {
  locale?: Locale
  changePasswordResponse?: () => Promise<Response>
  sessions?: SessionResponse[]
  securityEvents?: unknown[]
} = {}) {
  const client = createAppQueryClient()
  const requests: RequestRecord[] = []
  let sessions = [...(options.sessions ?? [currentSession, otherSession])]
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input); const method = init?.method ?? 'GET'
    requests.push({ url, method, body: init?.body as string | undefined })
    if (url === '/api/v1/me') return json({ id: 'u1', email: 'user@example.com', displayName: 'Dmitriy Ulyanov' })
    if (url === '/api/v1/me/organizations') return json([{ id: 'org', code: 'ORG', name: 'Acme', role: 'OWNER' }])
    if (url === '/api/v1/account/sessions' && method === 'GET') return json(sessions)
    if (url === '/api/v1/account/security-events' && method === 'GET') return json({ items: options.securityEvents ?? [], nextCursor: null })
    if (url === '/api/v1/account/sessions/revoke-others' && method === 'POST') { sessions = sessions.filter(s => s.current); return new Response(null, { status: 204 }) }
    if (url === '/api/v1/account/sessions/revoke-all' && method === 'POST') { sessions = []; return new Response(null, { status: 204 }) }
    if (url.startsWith('/api/v1/account/sessions/') && method === 'DELETE') {
      const id = url.substring(url.lastIndexOf('/') + 1); sessions = sessions.filter(s => s.id !== id); return new Response(null, { status: 204 })
    }
    if (url === '/api/v1/account' && method === 'PATCH')
      return json({ id: 'u1', email: 'user@example.com', displayName: JSON.parse(init?.body as string).displayName })
    if (url === '/api/v1/account/change-password' && method === 'POST')
      return options.changePasswordResponse ? options.changePasswordResponse() : new Response(null, { status: 204 })
    throw new Error(`Unexpected request ${method} ${url}`)
  }))
  render(<I18nProvider initialLocale={options.locale ?? 'en'}><QueryClientProvider client={client}>
    <MemoryRouter initialEntries={['/settings/account']}><Routes>
      <Route path="/settings/account" element={<AccountPage />} />
      <Route path="/login" element={<div>Login screen</div>} />
    </Routes></MemoryRouter>
  </QueryClientProvider></I18nProvider>)
  return { requests, client }
}

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

describe('account page', () => {
  it('shows the account details and keeps email read-only', async () => {
    setup()
    expect(await screen.findByDisplayValue('Dmitriy Ulyanov')).toBeTruthy()
    expect(screen.getAllByText('user@example.com')).toHaveLength(2)
    expect(screen.getByText(/Acme/)).toBeTruthy()
    expect(screen.getByText(/Owner/)).toBeTruthy()
    expect(screen.queryByDisplayValue('user@example.com')).toBeNull()
  })

  it('uses workspace sections and keeps the account menu panel out of settings', async () => {
    setup()
    const page = await screen.findByRole('main')
    await screen.findByText('1 organization')

    expect(page.classList.contains('workspace-page')).toBe(true)
    expect(page.querySelectorAll('.workspace-section')).toHaveLength(4)
    expect(page.querySelector('.account-panel, .account-menu-panel, .account-menu-identity')).toBeNull()
    expect(page.querySelector('.account-settings-grid')?.querySelectorAll('.workspace-section')).toHaveLength(2)
    expect(page.querySelector('.account-summary-avatar')?.textContent).toBe('D')
    expect(page.querySelector('.account-summary-meta')?.textContent).toBe('1 organization')
    expect(page.querySelector('.account-org-list')?.querySelector('.status-neutral')).toBeTruthy()
  })

  it('keeps the account dropdown on its own panel class', async () => {
    const { client } = setup()
    await screen.findByText('Dmitriy Ulyanov')
    render(<I18nProvider initialLocale="en"><QueryClientProvider client={client}>
      <MemoryRouter><AccountMenu /></MemoryRouter>
    </QueryClientProvider></I18nProvider>)
    fireEvent.click(screen.getByRole('button', { name: 'Account menu for Dmitriy Ulyanov' }))
    expect((await screen.findByRole('menu')).classList.contains('account-menu-panel')).toBe(true)
    expect(document.querySelector('.account-panel')).toBeNull()
  })

  it('renders Russian labels when the locale is ru', async () => {
    setup({ locale: 'ru' })
    expect(await screen.findByText('Профиль')).toBeTruthy()
    expect(screen.getByText('Текущий пароль')).toBeTruthy()
    expect(screen.getByText('Активные сессии')).toBeTruthy()
  })

  it('rejects a confirmation that does not match without calling the server', async () => {
    const { requests } = setup()
    await screen.findByLabelText('Current password')
    fireEvent.change(screen.getByLabelText('Current password'), { target: { value: 'old-password-1' } })
    fireEvent.change(screen.getByLabelText('New password'), { target: { value: 'new-strong-password-2' } })
    fireEvent.change(screen.getByLabelText('Confirm new password'), { target: { value: 'different' } })
    fireEvent.click(screen.getByRole('button', { name: 'Change password' }))

    expect(await screen.findByText('The new passwords do not match.')).toBeTruthy()
    expect(requests.some(r => r.url.endsWith('/change-password'))).toBe(false)
  })

  it('shows a stable localized error when the current password is wrong', async () => {
    setup({ changePasswordResponse: () => Promise.resolve(json({ code: 'CURRENT_PASSWORD_INVALID', message: 'x' }, 400)) })
    await screen.findByLabelText('Current password')
    fireEvent.change(screen.getByLabelText('Current password'), { target: { value: 'wrong' } })
    fireEvent.change(screen.getByLabelText('New password'), { target: { value: 'new-strong-password-2' } })
    fireEvent.change(screen.getByLabelText('Confirm new password'), { target: { value: 'new-strong-password-2' } })
    fireEvent.click(screen.getByRole('button', { name: 'Change password' }))

    expect(await screen.findByText('Current password is incorrect.')).toBeTruthy()
  })

  it('clears the inputs and refreshes sessions after a password change', async () => {
    const { requests } = setup()
    await screen.findByLabelText('Current password')
    fireEvent.change(screen.getByLabelText('Current password'), { target: { value: 'old-password-1' } })
    fireEvent.change(screen.getByLabelText('New password'), { target: { value: 'new-strong-password-2' } })
    fireEvent.change(screen.getByLabelText('Confirm new password'), { target: { value: 'new-strong-password-2' } })
    fireEvent.click(screen.getByRole('button', { name: 'Change password' }))

    expect(await screen.findByText('Password changed.')).toBeTruthy()
    expect((screen.getByLabelText('Current password') as HTMLInputElement).value).toBe('')
    expect(requests.filter(r => r.url.endsWith('/change-password'))).toHaveLength(1)
    // The other sessions were revoked server-side, so the list is refetched.
    await waitFor(() => expect(requests.filter(r => r.url === '/api/v1/account/sessions').length).toBeGreaterThanOrEqual(2))
  })

  it('does not submit the password change twice while a change is in flight', async () => {
    let release: () => void = () => {}
    const pending = new Promise<Response>((resolve) => { release = () => resolve(new Response(null, { status: 204 })) })
    const { requests } = setup({ changePasswordResponse: () => pending })
    await screen.findByLabelText('Current password')
    fireEvent.change(screen.getByLabelText('Current password'), { target: { value: 'old-password-1' } })
    fireEvent.change(screen.getByLabelText('New password'), { target: { value: 'new-strong-password-2' } })
    fireEvent.change(screen.getByLabelText('Confirm new password'), { target: { value: 'new-strong-password-2' } })
    const button = screen.getByRole('button', { name: 'Change password' })
    fireEvent.click(button)
    await waitFor(() => expect((button as HTMLButtonElement).disabled).toBe(true))
    fireEvent.click(button)
    release()
    await screen.findByText('Password changed.')
    expect(requests.filter(r => r.url.endsWith('/change-password'))).toHaveLength(1)
  })

  // -- Sessions --------------------------------------------------------------------------------

  it('marks the current session and lists the others', async () => {
    setup()
    expect(await screen.findByText('This device')).toBeTruthy()
    expect(screen.getByText('Current session')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Sign out other sessions' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Sign out from all devices' }).classList.contains('danger-button')).toBe(true)
    expect(screen.getAllByRole('button', { name: 'Sign out' })).toHaveLength(1)
  })

  it('shows meaningful security activity and a trusted login source, not its UUID', async () => {
    setup({ securityEvents: [{ id: 'event-private-id', type: 'LOGIN_SUCCEEDED', occurredAt: '2026-09-27T08:00:00Z', source: '203.0.113.10' }] })
    expect(await screen.findByText('Signed in')).toBeTruthy()
    expect(screen.getByText(/203\.0\.113\.10/)).toBeTruthy()
    expect(screen.queryByText('event-private-id')).toBeNull()
    expect(document.querySelector('.account-security-timeline')).toBeTruthy()
  })

  it('renders an unknown security event without breaking the timeline', async () => {
    setup({ securityEvents: [{ id: 'unknown', type: 'FUTURE_EVENT', occurredAt: '2026-09-27T08:00:00Z', source: null }] })
    expect(await screen.findByText('FUTURE_EVENT')).toBeTruthy()
    expect(document.querySelector('.account-security-timeline')).toBeTruthy()
  })

  it('shows compact empty states for empty sessions and security activity', async () => {
    setup({ sessions: [], securityEvents: [] })
    expect(await screen.findByText('No security activity yet.')).toBeTruthy()
    expect(screen.getByText('No other sessions.')).toBeTruthy()
    expect(document.querySelectorAll('.empty-compact').length).toBeGreaterThanOrEqual(2)
  })

  it('renders organization count in Russian', async () => {
    setup({ locale: 'ru' })
    expect(await screen.findByText('1 организация')).toBeTruthy()
  })

  it('signs out one other session, and it disappears from the list', async () => {
    const { requests } = setup()
    // The single per-session sign-out button belongs to the one other session.
    const signOut = await screen.findByRole('button', { name: 'Sign out' })
    fireEvent.click(signOut)
    await waitFor(() => expect(requests.some(r => r.method === 'DELETE' && r.url.endsWith('/sessions/sess-other'))).toBe(true))
    // After the refetch there is no other session left, so its sign-out button is gone.
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Sign out' })).toBeNull())
  })

  it('signs out other sessions in one request', async () => {
    const { requests } = setup()
    // Wait until the sessions have loaded (the per-session button appears) so the action button is enabled.
    await screen.findByRole('button', { name: 'Sign out' })
    const button = screen.getByRole('button', { name: 'Sign out other sessions' })
    fireEvent.click(button)
    await waitFor(() => expect(requests.some(r => r.url.endsWith('/sessions/revoke-others') && r.method === 'POST')).toBe(true))
    await waitFor(() => expect((screen.getByRole('button', { name: 'Sign out other sessions' }) as HTMLButtonElement).disabled).toBe(true))
  })

  it('signs out from all devices and redirects to login', async () => {
    const { requests } = setup()
    const button = await screen.findByRole('button', { name: 'Sign out from all devices' })
    fireEvent.click(button)
    await waitFor(() => expect(requests.some(r => r.url.endsWith('/sessions/revoke-all') && r.method === 'POST')).toBe(true))
    expect(await screen.findByText('Login screen')).toBeTruthy()
  })
})
