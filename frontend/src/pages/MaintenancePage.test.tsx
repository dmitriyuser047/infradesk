// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { I18nProvider } from '../i18n'
import type { MaintenanceWindowResponse } from '../types/maintenance'
import { MaintenancePage } from './MaintenancePage'

const window = (id: string, state: string, overrides: Partial<MaintenanceWindowResponse> = {}): MaintenanceWindowResponse => ({
  id, resource: { id: 'node', name: 'fin-prod-01', resourceTypeCode: 'NODE' },
  startsAt: '2026-10-10T10:00:00Z', endsAt: '2026-10-10T12:00:00Z', effectiveEnd: '2026-10-10T12:00:00Z', state,
  reason: 'Kernel upgrade', createdByName: 'Owner', createdAt: '2026-10-10T09:00:00Z', cancelledAt: null, cancelledByName: null,
  ...overrides,
})

function renderPage(role = 'OWNER') {
  const requests: { method: string; path: string; body?: unknown }[] = []
  vi.stubGlobal('fetch', vi.fn().mockImplementation((input: string, init?: RequestInit) => {
    const path = String(input).replace('/api/v1/organizations/org', '')
    requests.push({ method: init?.method ?? 'GET', path, body: init?.body ? JSON.parse(String(init.body)) : undefined })
    const body = path === '/maintenance-windows' && !init?.method
      ? { now: '2026-10-10T11:00:00Z', windows: [window('active', 'ACTIVE'),
        window('done', 'FINISHED', { cancelledByName: 'Operator', cancelledAt: '2026-10-10T10:30:00Z', effectiveEnd: '2026-10-10T10:30:00Z' })] }
      : window('new', 'SCHEDULED')
    return Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }))
  }))
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
  client.setQueryData(['me'], { id: 'user', email: 'owner@example.com', displayName: 'Owner' })
  client.setQueryData(['my-organizations'], [{ id: 'org', code: 'ORG', name: 'Org', role }])
  client.setQueryData(['projects', 'org'], [{ id: 'p', organizationId: 'org', code: 'p', name: 'Payments' }])
  client.setQueryData(['environments', 'org', 'p'], [{ id: 'env', organizationId: 'org', projectId: 'p', code: 'prod', name: 'Production', kind: 'PROD' }])
  client.setQueryData(['environment-resources', 'org', 'env'], [
    { id: 'c1', name: 'backend', resourceTypeCode: 'CONTAINER', active: true },
    { id: 'node', name: 'fin-prod-01', resourceTypeCode: 'NODE', active: true },
    { id: 'old', name: 'retired', resourceTypeCode: 'NODE', active: false },
  ])
  render(<I18nProvider initialLocale="en"><QueryClientProvider client={client}>
    <MemoryRouter initialEntries={['/organizations/org/maintenance']}><Routes>
      <Route path="/organizations/:organizationId/maintenance" element={<MaintenancePage />} />
    </Routes></MemoryRouter>
  </QueryClientProvider></I18nProvider>)
  return requests
}

describe('maintenance page', () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks() })

  it('lists windows with their state and lets an owner end the running one only', async () => {
    vi.spyOn(globalThis, 'confirm').mockReturnValue(true)
    const requests = renderPage()
    await screen.findByText('In progress')
    const list = document.querySelector<HTMLElement>('.maintenance-list')!
    expect(within(list).getByText('In progress')).toBeTruthy()
    expect(within(list).getByText('Finished')).toBeTruthy()
    expect(within(list).getByText(/ended by Operator/)).toBeTruthy()
    const end = within(list).getAllByRole('button')
    expect(end.map(button => button.textContent)).toEqual(['End now'])
    await act(async () => { fireEvent.click(end[0]) })
    expect(requests.some(request => request.method === 'POST' && request.path === '/maintenance-windows/active/cancel')).toBe(true)
  })

  it('plans a window on a chosen active server, servers first', async () => {
    const requests = renderPage()
    await screen.findByRole('button', { name: 'Plan window' })
    // The shell has its own project switcher; the plan form is the page's only form.
    const form = within(document.querySelector<HTMLElement>('form.workspace-form')!)
    fireEvent.change(form.getByLabelText('Project'), { target: { value: 'p' } })
    fireEvent.change(form.getByLabelText('Environment'), { target: { value: 'env' } })
    const server = form.getByLabelText('Server') as HTMLSelectElement
    expect([...server.options].map(option => option.value)).toEqual(['', 'node', 'c1'])
    fireEvent.change(server, { target: { value: 'node' } })
    fireEvent.change(form.getByLabelText('Reason'), { target: { value: '  Kernel upgrade ' } })
    await act(async () => { fireEvent.click(form.getByRole('button', { name: 'Plan window' })) })
    const created = requests.find(request => request.method === 'POST' && request.path === '/maintenance-windows')
    expect(created?.body).toMatchObject({ resourceId: 'node', reason: 'Kernel upgrade' })
    const body = created?.body as { startsAt: string; endsAt: string }
    expect(new Date(body.endsAt).getTime() - new Date(body.startsAt).getTime()).toBe(60 * 60_000)
  })

  it('shows a member the windows but no way to plan or end them', async () => {
    renderPage('MEMBER')
    expect(await screen.findByText('In progress')).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'End now' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Plan window' })).toBeNull()
  })
})
