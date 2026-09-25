// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { I18nProvider, type Locale } from '../i18n'
import type { HistoryEventResponse } from '../types/historyEvent'
import type { ResourceResponse } from '../types/resource'
import { ResourcePage } from './ResourcePage'

const base = { organizationId: 'org', environmentId: 'env', resourceTypeId: 'type', active: true, createdAt: '', updatedAt: '' }
const server: ResourceResponse = { ...base, id: 'server', parentResourceId: null, code: 'finland', name: 'finland_node',
  resourceTypeCode: 'NODE', data: { kind: 'NODE', spec: null,
    status: { online: true, cpuUsagePercent: 4.2, memoryUsagePercent: 31, uptimeSeconds: 1_036_800 } } }
const container: ResourceResponse = { ...base, id: 'postgres', parentResourceId: 'server', code: 'postgres', name: 'postgres',
  resourceTypeCode: 'CONTAINER', data: { kind: 'CONTAINER', spec: { image: 'postgres:17' }, status: { state: 'running' } } }
const event: HistoryEventResponse = {
  id: 'event', eventType: 'RESOURCE_DISCOVERED', source: 'SYSTEM', occurredAt: '2026-09-25T09:00:00Z',
  resource: { id: 'postgres', name: 'postgres' }, connection: null, actor: null, incident: null, operation: null, sync: null,
} as HistoryEventResponse

interface Answers {
  resource: ResourceResponse
  operations?: string[]
  executions?: unknown[]
  history?: HistoryEventResponse[]
}

/** Answers each read the page may make and records it; the shell's own data is seeded. */
function renderPage(answers: Answers, options: { locale?: Locale; cachedList?: ResourceResponse[] } = {}) {
  const requests: string[] = []
  vi.stubGlobal('fetch', vi.fn().mockImplementation((input: string) => {
    const path = String(input)
    requests.push(path.replace('/api/v1/organizations/org', ''))
    const body = path.endsWith('/operations') ? { operations: answers.operations ?? [], unavailableReason: null }
      : path.includes('/operation-executions') ? answers.executions ?? []
        : path.includes('/history-events') ? answers.history ?? []
          : path.includes('/monitor-rules') ? [] : path.includes('/metrics') ? [] : answers.resource
    return Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }))
  }))
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
  client.setQueryData(['me'], { id: 'user', email: 'owner@example.com', displayName: 'Owner' })
  client.setQueryData(['my-organizations'], [{ id: 'org', code: 'ORG', name: 'Org', role: 'OWNER' }])
  client.setQueryData(['projects', 'org'], [{ id: 'project', organizationId: 'org', code: 'app', name: 'App', description: null }])
  client.setQueryData(['environments', 'org', 'project'], [{ id: 'env', organizationId: 'org', projectId: 'project', code: 'prod', name: 'Production', kind: 'PROD' }])
  if (options.cachedList) client.setQueryData(['environment-resources', 'org', 'env'], options.cachedList)
  render(<I18nProvider initialLocale={options.locale ?? 'en'}><QueryClientProvider client={client}>
    <MemoryRouter initialEntries={[`/organizations/org/environments/env/resources/${answers.resource.id}?project=project`]}><Routes>
      <Route path="/organizations/:organizationId/environments/:environmentId/resources/:resourceId" element={<ResourcePage />} />
    </Routes></MemoryRouter>
  </QueryClientProvider></I18nProvider>)
  return requests
}

const tabNames = () => screen.getAllByRole('tab').map(tab => tab.textContent)

describe('resource detail page', () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals() })

  it('makes only the reads it made before: the resource and its operations', async () => {
    const requests = renderPage({ resource: container, operations: ['CONTAINER_STOP'] })
    await screen.findByRole('tab', { name: 'Operations' })

    expect([...requests].sort()).toEqual(['/resources/postgres', '/resources/postgres/operation-executions?limit=20',
      '/resources/postgres/operations'])
  })

  it('shows the operations tab only when the backend offers operations or some ran before', async () => {
    renderPage({ resource: container, operations: [], executions: [] })
    await screen.findByText('postgres:17')
    await waitFor(() => expect(tabNames()).toEqual(['Overview', 'Activity']))
    cleanup()

    renderPage({ resource: container, operations: ['CONTAINER_START', 'CONTAINER_STOP'] })
    expect(await screen.findByRole('tab', { name: 'Operations' })).toBeTruthy()
    expect(tabNames()).toEqual(['Overview', 'Activity', 'Operations'])
    cleanup()

    renderPage({ resource: container, operations: [], executions: [{ id: 'execution', resourceId: 'postgres',
      operationCode: 'CONTAINER_RESTART', status: 'SUCCEEDED', actorUserId: 'user', startedAt: '2026-09-25T09:00:00Z',
      finishedAt: '2026-09-25T09:00:04Z', errorCode: null, errorMessage: null }] })
    expect(await screen.findByRole('tab', { name: 'Operations' })).toBeTruthy()
  })

  it('opens monitoring on a server, in Russian, and loads metrics only then', async () => {
    const requests = renderPage({ resource: server }, { locale: 'ru' })
    await screen.findByText('Время работы')
    expect(tabNames()).toEqual(['Обзор', 'Мониторинг', 'События'])
    expect(requests.some(path => path.includes('/metrics'))).toBe(false)

    fireEvent.click(screen.getByRole('tab', { name: 'Мониторинг' }))
    expect(screen.getByRole('tab', { name: 'Мониторинг' }).getAttribute('aria-selected')).toBe('true')
    await waitFor(() => expect(requests.some(path => path.includes('/metrics'))).toBe(true))
    expect(await screen.findByText('Правила мониторинга')).toBeTruthy()
    expect(screen.getByText('12 д 0 ч')).toBeTruthy()
  })

  it('shows activity through the shared timeline, or says there is none', async () => {
    renderPage({ resource: container, history: [event] })
    fireEvent.click(await screen.findByRole('tab', { name: 'Activity' }))

    expect(await screen.findByText('Resource discovered')).toBeTruthy()
    expect(document.querySelector('.activity-timeline .activity-heading .activity-time')).not.toBeNull()
    cleanup()

    renderPage({ resource: container, history: [] }, { locale: 'ru' })
    fireEvent.click(await screen.findByRole('tab', { name: 'События' }))
    expect(await screen.findByText('Событий пока нет')).toBeTruthy()
  })

  it('names the server of a container from what the browser already holds, without asking for it', async () => {
    const requests = renderPage({ resource: container }, { cachedList: [server, container] })

    expect((await screen.findByRole('link', { name: 'finland_node' })).getAttribute('href'))
      .toBe('/organizations/org/environments/env/resources/server?project=project')
    expect(requests.some(path => path.includes('/resources/server'))).toBe(false)
    cleanup()

    renderPage({ resource: container })
    expect((await screen.findByRole('link', { name: 'Open server' })).getAttribute('href'))
      .toBe('/organizations/org/environments/env/resources/server?project=project')
  })

  it('explains a missing resource and keeps the way back', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: 'RESOURCE_NOT_FOUND', message: 'x' }),
      { status: 404, headers: { 'Content-Type': 'application/json' } })))
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
    client.setQueryData(['me'], { id: 'user', email: 'owner@example.com', displayName: 'Owner' })
    client.setQueryData(['my-organizations'], [{ id: 'org', code: 'ORG', name: 'Org', role: 'OWNER' }])
    client.setQueryData(['projects', 'org'], [{ id: 'project', organizationId: 'org', code: 'app', name: 'App', description: null }])
    client.setQueryData(['environments', 'org', 'project'], [{ id: 'env', organizationId: 'org', projectId: 'project', code: 'prod', name: 'Production', kind: 'PROD' }])
    render(<I18nProvider initialLocale="ru"><QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/organizations/org/environments/env/resources/missing?project=project']}><Routes>
        <Route path="/organizations/:organizationId/environments/:environmentId/resources/:resourceId" element={<ResourcePage />} />
      </Routes></MemoryRouter>
    </QueryClientProvider></I18nProvider>)

    expect(await screen.findByText('Возможно, ресурс больше не существует или находится в другом окружении.')).toBeTruthy()
    // The operations reads wait for the resource, so a missing one costs a single request.
    expect((fetch as unknown as ReturnType<typeof vi.fn>).mock.calls.map(([url]) => String(url)))
      .toEqual(['/api/v1/organizations/org/resources/missing'])
    expect(document.querySelector('.workspace-back')?.getAttribute('href')).toBe('/organizations/org/environments/env?project=project')
  })
})
