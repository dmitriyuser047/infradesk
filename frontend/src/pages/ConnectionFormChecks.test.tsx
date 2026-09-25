// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { I18nProvider, type Locale } from '../i18n'
import type { ConnectionResponse } from '../types/connection'
import { ConnectionFormPage } from './ConnectionFormPage'

const trusted = 'SHA256:trusted-key'
const privateKey = '-----BEGIN OPENSSH PRIVATE KEY-----\nsecret-material\n-----END OPENSSH PRIVATE KEY-----'

function existing(): ConnectionResponse {
  return {
    id: 'connection', connectorType: 'SSH', code: 'ssh', name: 'finland_node', scope: { type: 'ORGANIZATION' },
    active: true, schedule: { enabled: true, intervalSeconds: 600, nextRunAt: '2026-09-24T10:00:00Z' },
    lastSync: null, createdAt: '', updatedAt: '',
    ssh: { host: 'example.test', port: 22, username: 'deploy', hostKeyFingerprint: trusted,
      credentialConfigured: true, authenticationType: 'PRIVATE_KEY', hostTrusted: true },
  }
}

interface Sent { method: string; path: string; body: unknown }
type Answer = (sent: Sent) => Promise<Response> | Response

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

/** Every request the form sends is recorded; each one answers as the test says. */
function renderForm(answer: Answer = () => json({ success: true, hostKeyFingerprint: trusted }), locale: Locale = 'ru') {
  const sent: Sent[] = []
  vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string, init?: RequestInit) => {
    const request = { method: init?.method ?? 'GET', path: String(url).replace('/api/v1/organizations/org', ''),
      body: init?.body ? JSON.parse(String(init.body)) : null }
    sent.push(request)
    return Promise.resolve(answer(request))
  }))
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
  client.setQueryData(['my-organizations'], [{ id: 'org', code: 'ORG', name: 'Org', role: 'OWNER' }])
  client.setQueryData(['me'], { id: 'user', email: 'owner@example.com', displayName: 'Owner' })
  client.setQueryData(['projects', 'org'], [])
  client.setQueryData(['connection', 'org', 'connection'], existing())
  render(<I18nProvider initialLocale={locale}><QueryClientProvider client={client}>
    <MemoryRouter initialEntries={['/organizations/org/connections/connection/edit']}><Routes>
      <Route path="/organizations/:organizationId/connections/:connectionId/edit" element={<ConnectionFormPage />} />
      <Route path="/organizations/:organizationId/connections/:connectionId" element={<p>saved</p>} />
    </Routes></MemoryRouter>
  </QueryClientProvider></I18nProvider>)
  return sent
}

const field = (label: string, selector?: string) => screen.getByLabelText(label, selector ? { selector } : undefined)
const change = (element: HTMLElement, value: string) => fireEvent.change(element, { target: { value } })
const keyField = () => document.querySelector('textarea') as HTMLTextAreaElement
const success = () => screen.queryByText('Подключение проверено')

/** Enters a key and runs a successful check with the current settings. */
async function checkSucceeds() {
  if ((keyField() as HTMLTextAreaElement).value === '') change(keyField(), privateKey)
  await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Проверить подключение' })) })
  await waitFor(() => expect(success()).not.toBeNull())
}

function deferred() {
  let resolve: (response: Response) => void = () => undefined
  const promise = new Promise<Response>(done => { resolve = done })
  return { promise, resolve }
}

describe('connection checks in the SSH form', () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals() })

  it('forgets a successful check as soon as anything it depended on changes', async () => {
    renderForm()
    const edits: [string, () => void][] = [
      ['host', () => change(field('Адрес'), 'other.test')],
      ['port', () => change(field('Порт'), '2222')],
      ['username', () => change(field('Пользователь'), 'root')],
      ['credential', () => change(keyField(), `${privateKey}\n`)],
      ['passphrase', () => change(field('Парольная фраза (если есть)'), 'phrase')],
    ]
    for (const [, edit] of edits) {
      // Back to the saved endpoint, where the server is trusted and a check can run.
      change(field('Адрес'), 'example.test'); change(field('Порт'), '22')
      await checkSucceeds()
      edit()
      expect(success()).toBeNull()
    }

    change(field('Адрес'), 'example.test'); change(field('Порт'), '22')
    await checkSucceeds()
    fireEvent.click(screen.getByRole('radio', { name: 'Пароль' }))
    expect(success()).toBeNull()
  })

  it('does not bring an old success back when the settings return to the checked ones', async () => {
    renderForm()
    await checkSucceeds()

    change(field('Пользователь'), 'root')
    change(field('Пользователь'), 'deploy')
    expect(success()).toBeNull()
  })

  it('never shows the answer of a check that started with other settings', async () => {
    const pending = deferred()
    renderForm(sent => sent.path === '/connections/ssh/test' ? pending.promise : json({}))
    change(keyField(), privateKey)
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Проверить подключение' })) })

    // Still running: one check at a time.
    const button = await screen.findByRole('button', { name: 'Проверяем…' }) as HTMLButtonElement
    expect(button.disabled).toBe(true)
    expect(button.getAttribute('aria-busy')).toBe('true')

    change(field('Пользователь'), 'root')
    await act(async () => { pending.resolve(json({ success: true, hostKeyFingerprint: trusted })) })
    await waitFor(() => expect(screen.getByRole('button', { name: 'Проверить подключение' })).toBeTruthy())
    expect(success()).toBeNull()
    change(field('Пользователь'), 'deploy')
    expect(success()).toBeNull()
  })

  it('never offers a key read at an old address as the key of the new one', async () => {
    const pending = deferred()
    renderForm(sent => sent.path === '/connections/ssh/host-key' ? pending.promise : json({}))
    change(field('Адрес'), 'first.test')
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: /Получить ключ сервера/ })) })
    expect((await screen.findByRole('button', { name: /Получение…/ }) as HTMLButtonElement).disabled).toBe(true)

    change(field('Адрес'), 'second.test')
    await act(async () => { pending.resolve(json({ hostKeyFingerprint: 'SHA256:first-key' })) })

    expect(screen.queryByText('SHA256:first-key')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Подтвердить сервер' })).toBeNull()
    expect(document.querySelector('.trust-state')?.className).toContain('trust-changed')
  })

  it('shows a failed check by its code, and only for the settings it ran with', async () => {
    renderForm(sent => sent.path === '/connections/ssh/test'
      ? json({ code: 'SSH_PRIVATE_KEY_PASSPHRASE_INVALID', message: 'bad passphrase for key' }, 422) : json({}))
    change(keyField(), privateKey)
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Проверить подключение' })) })

    expect(await screen.findByText('Проверка подключения не удалась')).toBeTruthy()
    expect(screen.getByText('Неверная парольная фраза приватного ключа.')).toBeTruthy()
    expect(document.body.textContent).not.toContain('bad passphrase for key')
    change(field('Парольная фраза (если есть)'), 'another')
    expect(screen.queryByText('Проверка подключения не удалась')).toBeNull()
  })

  it('keeps the three answers apart: key received, server confirmed, connection checked', async () => {
    renderForm(sent => sent.path === '/connections/ssh/host-key' ? json({ hostKeyFingerprint: 'SHA256:new-key' })
      : json({ success: true, hostKeyFingerprint: 'SHA256:new-key' }))
    change(field('Адрес'), 'other.test')
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: /Получить ключ сервера/ })) })

    // Received, not trusted, not checked.
    expect(await screen.findByText('Отпечаток получен')).toBeTruthy()
    expect(screen.getByText('Требует подтверждения')).toBeTruthy()
    expect(success()).toBeNull()
    expect((screen.getByRole('button', { name: 'Проверить подключение' }) as HTMLButtonElement).disabled).toBe(true)

    fireEvent.click(screen.getByRole('button', { name: 'Подтвердить сервер' }))
    expect(document.querySelector('.identity-grid')?.textContent).toContain('Сервер подтверждён')
    expect(success()).toBeNull()

    change(keyField(), privateKey)
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Проверить подключение' })) })
    expect(await screen.findByText('Подключение проверено')).toBeTruthy()
  })

  it('treats a different key at the saved address as a security warning, never next to a success', async () => {
    renderForm(sent => sent.path === '/connections/ssh/host-key' ? json({ hostKeyFingerprint: 'SHA256:other-key' }) : json({}))
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: /Получить ключ сервера/ })) })

    expect(await screen.findByText('SSH-отпечаток отличается от сохранённого')).toBeTruthy()
    expect(screen.getByRole('alert').textContent).toContain('Ключ сервера изменился')
    expect(document.querySelector('.trust-body .status-success')).toBeNull()
  })

  it('copies a fingerprint only when asked, and survives a refused clipboard', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText } })
    const sent = renderForm()
    const copy = screen.getByRole('button', { name: 'Скопировать: Подтверждённый отпечаток' })
    expect(writeText).not.toHaveBeenCalled()

    await act(async () => { fireEvent.click(copy) })
    expect(writeText).toHaveBeenCalledWith(trusted)
    expect(copy.textContent).toBe('Скопировано')
    expect(sent).toEqual([])

    writeText.mockRejectedValueOnce(new Error('denied'))
    await act(async () => { fireEvent.click(copy) })
    expect(screen.getByText('Не удалось скопировать')).toBeTruthy()

    vi.stubGlobal('navigator', { ...navigator, clipboard: undefined })
    await act(async () => { fireEvent.click(copy) })
    expect(screen.getByText('Не удалось скопировать')).toBeTruthy()
  })

  it('names the stored credential without showing or masking it, and keeps it when the method stays', async () => {
    const sent = renderForm(sent => json(sent.method === 'PUT' ? existing() : {}))

    expect(screen.getAllByText('Приватный ключ настроен').length).toBeGreaterThan(0)
    expect((keyField() as HTMLTextAreaElement).value).toBe('')
    expect(document.body.innerHTML).not.toMatch(/\*{4,}|•{4,}/)

    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Сохранить подключение' })) })
    await screen.findByText('saved')
    // One save; the only other request is the saved connection re-read by its existing invalidation.
    expect(sent.map(request => `${request.method} ${request.path}`)).toEqual(['PUT /connections/connection', 'GET /connections/connection'])
    expect(sent[0].body).not.toHaveProperty('credentials')
    expect(sent[0].body).toMatchObject({ ssh: { hostKeyFingerprint: trusted, authenticationType: 'PRIVATE_KEY' } })
  })

  it('asks for a new credential of the chosen kind when the method changes, and sends only that kind', async () => {
    const sent = renderForm(sent => json(sent.method === 'PUT' ? existing() : {}))
    change(keyField(), privateKey)
    fireEvent.click(screen.getByRole('radio', { name: 'Пароль' }))

    expect(screen.getByText('Введите новые учётные данные: сохранённые относятся к другому способу входа.')).toBeTruthy()
    expect(screen.queryByRole('textbox', { name: /Приватный ключ/ })).toBeNull()
    // Past the browser's own required-field check, to the form's rule for the chosen method.
    fireEvent.submit(document.querySelector('form') as HTMLFormElement)
    expect(screen.getByText('Для этого способа нужен пароль.')).toBeTruthy()
    expect(document.activeElement?.className).toBe('focus-target')
    expect(sent).toEqual([])

    change(field('Пароль', 'input[type="password"]'), 'new-password')
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Сохранить подключение' })) })
    await screen.findByText('saved')
    expect(sent[0].body).toMatchObject({ credentials: { type: 'PASSWORD', password: 'new-password' } })
    expect(JSON.stringify(sent[0].body)).not.toContain('secret-material')
  })

  it('sends nothing while typing, one request per check or key read, and nothing to confirm', async () => {
    const sent = renderForm(sent => sent.path === '/connections/ssh/host-key' ? json({ hostKeyFingerprint: 'SHA256:new-key' })
      : json({ success: true, hostKeyFingerprint: trusted }))
    change(field('Адрес'), 'other.test'); change(field('Порт'), '2200'); change(field('Пользователь'), 'root')
    change(field('Адрес'), 'example.test'); change(field('Порт'), '22'); change(field('Пользователь'), 'deploy')
    change(keyField(), privateKey)
    expect(sent).toEqual([])

    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Проверить подключение' })) })
    await waitFor(() => expect(success()).not.toBeNull())
    expect(sent.map(request => `${request.method} ${request.path}`)).toEqual(['POST /connections/ssh/test'])

    change(field('Адрес'), 'other.test')
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: /Получить ключ сервера/ })) })
    await screen.findByText('SHA256:new-key')
    fireEvent.click(screen.getByRole('button', { name: 'Подтвердить сервер' }))
    expect(sent.map(request => `${request.method} ${request.path}`)).toEqual(['POST /connections/ssh/test', 'POST /connections/ssh/host-key'])
    // The key read carries no credential.
    expect(sent[1].body).toEqual({ host: 'other.test', port: 22, username: 'deploy' })
  })

  it('speaks English', async () => {
    renderForm(() => json({ hostKeyFingerprint: 'SHA256:new-key' }), 'en')

    expect(screen.getAllByText('Server identity').length).toBeGreaterThan(0)
    expect(screen.getByRole('button', { name: 'Copy: Confirmed fingerprint' }).textContent).toBe('Copy')
    expect(screen.getAllByText('Private key configured').length).toBeGreaterThan(0)
    fireEvent.change(screen.getByLabelText('Address'), { target: { value: 'other.test' } })
    expect(screen.getByText('This server address is not confirmed yet')).toBeTruthy()
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: /Read server key/ })) })
    expect(await screen.findByText('Requires confirmation')).toBeTruthy()
    expect(screen.getByText('Fingerprint received')).toBeTruthy()
  })
})
