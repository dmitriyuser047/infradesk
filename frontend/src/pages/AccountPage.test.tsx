// @vitest-environment jsdom
import { QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createAppQueryClient } from '../app/queryClient'
import { I18nProvider } from '../i18n'
import type { Locale } from '../i18n/types'
import { AccountPage } from './AccountPage'

type RequestRecord = { url: string; method: string; body?: string }

function json(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
}

function setup(options: {
  locale?: Locale
  changePasswordResponse?: () => Promise<Response>
} = {}) {
  const client = createAppQueryClient()
  const requests: RequestRecord[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input); const method = init?.method ?? 'GET'
    requests.push({ url, method, body: init?.body as string | undefined })
    if (url === '/api/v1/me') return json({ id: 'u1', email: 'user@example.com', displayName: 'Dmitriy Ulyanov' })
    if (url === '/api/v1/me/organizations') return json([{ id: 'org', code: 'ORG', name: 'Acme', role: 'OWNER' }])
    if (url === '/api/v1/account' && method === 'PATCH')
      return json({ id: 'u1', email: 'user@example.com', displayName: JSON.parse(init?.body as string).displayName })
    if (url === '/api/v1/account/change-password' && method === 'POST')
      return options.changePasswordResponse ? options.changePasswordResponse() : new Response(null, { status: 204 })
    throw new Error(`Unexpected request ${method} ${url}`)
  }))
  render(<I18nProvider initialLocale={options.locale ?? 'en'}><QueryClientProvider client={client}>
    <MemoryRouter initialEntries={['/settings/account']}><Routes>
      <Route path="/settings/account" element={<AccountPage />} />
    </Routes></MemoryRouter>
  </QueryClientProvider></I18nProvider>)
  return { requests }
}

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

describe('account page', () => {
  it('shows the account details and keeps email read-only', async () => {
    setup()
    expect(await screen.findByDisplayValue('Dmitriy Ulyanov')).toBeTruthy()
    expect(screen.getByText('user@example.com')).toBeTruthy()
    expect(screen.getByText(/Acme/)).toBeTruthy()
    expect(screen.getByText(/Owner/)).toBeTruthy()
    // Email is shown as text, not an editable field.
    expect(screen.queryByDisplayValue('user@example.com')).toBeNull()
  })

  it('renders Russian labels when the locale is ru', async () => {
    setup({ locale: 'ru' })
    expect(await screen.findByText('Профиль')).toBeTruthy()
    expect(screen.getByText('Текущий пароль')).toBeTruthy()
    expect(screen.getByLabelText('Новый пароль')).toBeTruthy()
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

  it('clears the inputs and confirms success after a password change', async () => {
    const { requests } = setup()
    await screen.findByLabelText('Current password')
    fireEvent.change(screen.getByLabelText('Current password'), { target: { value: 'old-password-1' } })
    fireEvent.change(screen.getByLabelText('New password'), { target: { value: 'new-strong-password-2' } })
    fireEvent.change(screen.getByLabelText('Confirm new password'), { target: { value: 'new-strong-password-2' } })
    fireEvent.click(screen.getByRole('button', { name: 'Change password' }))

    expect(await screen.findByText('Password changed.')).toBeTruthy()
    expect((screen.getByLabelText('Current password') as HTMLInputElement).value).toBe('')
    expect((screen.getByLabelText('New password') as HTMLInputElement).value).toBe('')
    expect((screen.getByLabelText('Confirm new password') as HTMLInputElement).value).toBe('')
    expect(requests.filter(r => r.url.endsWith('/change-password'))).toHaveLength(1)
  })

  it('does not submit twice while a change is in flight', async () => {
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
    fireEvent.click(button)
    release()

    await screen.findByText('Password changed.')
    expect(requests.filter(r => r.url.endsWith('/change-password'))).toHaveLength(1)
  })
})
