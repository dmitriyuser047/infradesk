// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { shouldRetryQuery } from '../app/queryClient'
import { I18nProvider } from '../i18n'
import { ConnectionsPage } from './ConnectionsPage'

/** The connection list endpoint answers as `answer` says; everything else the page reads is empty. */
function stubBackend(answer: () => Promise<Response> | 'hang') {
  const calls: string[] = []
  const signals: AbortSignal[] = []
  vi.stubGlobal('fetch', vi.fn().mockImplementation((input: string, init?: RequestInit) => {
    const path = new URL(String(input), 'http://localhost').pathname
    if (path === '/api/v1/organizations/org/connections') {
      calls.push(path)
      if (init?.signal) signals.push(init.signal)
      const result = answer()
      return result === 'hang' ? new Promise<Response>(() => undefined) : result
    }
    return Promise.resolve(new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } }))
  }))
  return { calls, signals }
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: shouldRetryQuery, retryDelay: 0 } } })
  client.setQueryData(['me'], { id: 'user', email: 'a@example.com', displayName: 'Dmitriy' })
  client.setQueryData(['my-organizations'], [{ id: 'org', code: 'ORG', name: 'Northwind', role: 'OWNER' }])
  client.setQueryData(['projects', 'org'], [])
  return render(<I18nProvider initialLocale="en"><QueryClientProvider client={client}>
    <MemoryRouter initialEntries={['/organizations/org/connections']}><Routes>
      <Route path="/organizations/:organizationId/connections" element={<ConnectionsPage />} />
    </Routes></MemoryRouter>
  </QueryClientProvider></I18nProvider>)
}

describe('connections loading states', () => {
  afterEach(() => { cleanup(); vi.useRealTimers(); vi.unstubAllGlobals() })

  it('keeps the skeleton for a quick load, then says a slow one is slow and offers a retry', async () => {
    vi.useFakeTimers()
    const { calls, signals } = stubBackend(() => 'hang')
    renderPage()
    expect(screen.getByLabelText('Loading connections')).toBeTruthy()
    expect(screen.queryByText('Loading connections is taking longer than usual')).toBeNull()
    await act(async () => { await vi.advanceTimersByTimeAsync(5000) })
    expect(screen.getByText('Loading connections is taking longer than usual')).toBeTruthy()
    // No more unlabelled bars once the page has said what is going on.
    expect(screen.queryByLabelText('Loading connections')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    expect(calls).toHaveLength(2)
    expect(signals).toHaveLength(2)
    expect(signals[0].aborted).toBe(true)
    expect(signals[1].aborted).toBe(false)
  })

  it('shows a refused request as an error at once, without retrying it', async () => {
    const { calls } = stubBackend(() => Promise.resolve(new Response(JSON.stringify({ code: 'FORBIDDEN', message: 'x' }),
      { status: 403, headers: { 'Content-Type': 'application/json' } })))
    renderPage()
    expect(await screen.findByText('Unable to load connections')).toBeTruthy()
    expect(calls).toHaveLength(1)
  })

  it('retries a server error once, then shows it as an error rather than an endless skeleton', async () => {
    const { calls } = stubBackend(() => Promise.resolve(new Response(JSON.stringify({ code: 'INTERNAL_ERROR', message: 'x' }),
      { status: 500, headers: { 'Content-Type': 'application/json' } })))
    renderPage()
    expect(await screen.findByText('Unable to load connections')).toBeTruthy()
    expect(calls).toHaveLength(2)
    expect(screen.queryByLabelText('Loading connections')).toBeNull()
  })
})
