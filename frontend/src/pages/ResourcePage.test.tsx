// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { I18nProvider, type Locale } from '../i18n'
import type { HistoryEventResponse } from '../types/historyEvent'
import type { ResourceContextResponse } from '../types/infrastructure'
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
  context?: ResourceContextResponse
  incidents?: unknown[]
  operations?: string[]
  executions?: unknown[]
  history?: HistoryEventResponse[]
  bindings?: unknown[]
}

/** Answers each read the page may make and records it; the shell's own data is seeded. */
function renderPage(answers: Answers, options: { locale?: Locale; cachedList?: ResourceResponse[]; path?: string } = {}) {
  const requests: string[] = []
  vi.stubGlobal('fetch', vi.fn().mockImplementation((input: string) => {
    const path = String(input)
    requests.push(path.replace('/api/v1/organizations/org', ''))
    const body = path.endsWith('/context') ? answers.context ?? context
      : path.includes('/incidents?') ? answers.incidents ?? []
      : path.endsWith('/operations') ? { operations: answers.operations ?? [], unavailableReason: null }
      : path.includes('/operation-executions') ? answers.executions ?? []
        : path.includes('/history-events') ? answers.history ?? []
          : path.includes('/integration-bindings') ? { items: answers.bindings ?? [] }
          : path.includes('/monitor-rules') ? [] : path.includes('/metrics') ? [] : answers.resource
    if ((body as unknown) === 'fail') return Promise.resolve(new Response(JSON.stringify({ code: 'INTERNAL_ERROR', message: 'x' }),
      { status: 500, headers: { 'Content-Type': 'application/json' } }))
    return Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }))
  }))
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
  client.setQueryData(['me'], { id: 'user', email: 'owner@example.com', displayName: 'Owner' })
  client.setQueryData(['my-organizations'], [{ id: 'org', code: 'ORG', name: 'Org', role: 'OWNER' }])
  client.setQueryData(['projects', 'org'], [{ id: 'project', organizationId: 'org', code: 'app', name: 'App', description: null }])
  client.setQueryData(['environments', 'org', 'project'], [{ id: 'env', organizationId: 'org', projectId: 'project', code: 'prod', name: 'Production', kind: 'PROD' }])
  if (options.cachedList) client.setQueryData(['environment-resources', 'org', 'env'], options.cachedList)
  render(<I18nProvider initialLocale={options.locale ?? 'en'}><QueryClientProvider client={client}>
    <MemoryRouter initialEntries={[options.path ?? `/organizations/org/environments/env/resources/${answers.resource.id}?project=project`]}><Routes>
      <Route path="/organizations/:organizationId/environments/:environmentId/resources/:resourceId" element={<ResourcePage />} />
    </Routes></MemoryRouter>
  </QueryClientProvider></I18nProvider>)
  return requests
}

const context: ResourceContextResponse = {
  project: { id: 'project', name: 'App' }, environment: { id: 'env', name: 'Production', kind: 'PROD' },
  parentResource: null, sourceConnections: [{ id: 'finnish', name: 'Finnish Node', connectorType: 'SSH', active: true }],
  children: [], activeChildCount: 0, openIncidentCount: 0,
}

const tabNames = () => screen.getAllByRole('tab').map(tab => tab.textContent)

describe('resource detail page', () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals() })

  it('shows the Remnawave node bound to a NODE read-only, and asks nothing for other resource types', async () => {
    const requests = renderPage({ resource: server, bindings: [{
      integration: { id: 'integration', name: 'Main Remnawave', providerType: 'REMNAWAVE' },
      object: { id: 'obj', objectType: 'NODE', externalId: 'uuid', displayName: 'Frankfurt', active: true,
        firstSeenAt: '2026-09-29T10:00:00Z', lastSeenAt: '2026-09-29T10:00:00Z',
        summary: { address: '203.0.113.10', port: 2222, state: 'CONNECTED', xrayVersion: '25.9.11', usersOnline: 4 } } }] })
    expect(await screen.findByText('Main Remnawave')).toBeTruthy()
    expect(screen.getByText('Frankfurt')).toBeTruthy()
    expect(screen.getByText('203.0.113.10:2222')).toBeTruthy()
    expect(screen.getByText('Observed read-only from Remnawave.')).toBeTruthy()
    expect(requests).toContain('/resources/server/integration-bindings')
    cleanup()
    const containerRequests = renderPage({ resource: container })
    await screen.findByRole('tab', { name: 'Overview' })
    expect(containerRequests.some(path => path.includes('integration-bindings'))).toBe(false)
  })

  it('makes one read each for the resource, its place in the infrastructure and its operations', async () => {
    const requests = renderPage({ resource: container, operations: ['CONTAINER_STOP'] })
    await screen.findByRole('tab', { name: 'Operations' })

    expect([...requests].sort()).toEqual(['/resources/postgres', '/resources/postgres/context',
      '/resources/postgres/operation-executions?limit=20', '/resources/postgres/operations'])
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
    expect(tabNames()).toEqual(['Обзор', 'Мониторинг', 'Инциденты', 'События', 'Конфигурации'])
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
    // The operations reads wait for the resource; only the resource and its context are asked.
    expect((fetch as unknown as ReturnType<typeof vi.fn>).mock.calls.map(([url]) => String(url)).sort())
      .toEqual(['/api/v1/organizations/org/resources/missing', '/api/v1/organizations/org/resources/missing/context'])
    expect(document.querySelector('.workspace-back')?.getAttribute('href')).toBe('/organizations/org/environments/env?project=project')
  })

  it('says where the resource lives in its path, not in a context table, and keeps its children', async () => {
    const requests = renderPage({ resource: server, context: { ...context,
      children: [container], activeChildCount: 1 } })

    const path = await screen.findByRole('navigation', { name: 'Location in the infrastructure' })
    // One source: it is part of the path, and remembers where it was followed from.
    expect(within(path).getAllByRole('link').map(link => [link.textContent, link.getAttribute('href')])).toEqual([
      ['App', '/organizations/org/resources?project=project'],
      ['Production', '/organizations/org/environments/env?project=project&environment=env'],
      ['Finnish Node', '/organizations/org/connections/finnish?project=project&fromResource=server&fromEnvironment=env'],
    ])
    expect(within(path).getByText('finland_node').getAttribute('aria-current')).toBe('page')
    // Project, environment and source are not repeated as a section below.
    expect(screen.queryByRole('heading', { name: 'Context' })).toBeNull()
    expect(screen.queryByText('Sources · 1')).toBeNull()
    // What it contains is operational and stays.
    expect(await screen.findByRole('heading', { name: 'Contained resources' })).toBeTruthy()
    expect(within(screen.getByRole('tree', { name: 'Contained resources' })).getByRole('link', { name: /postgres/ })).toBeTruthy()
    // Direct navigation: nothing was in the cache, and no list was loaded to find the context.
    expect(requests.some(value => value.includes('/environments/'))).toBe(false)
  })

  it('lists several sources compactly, pretending none is the one', async () => {
    renderPage({ resource: container, context: { ...context,
      parentResource: { id: 'server', name: 'fin-prod-01', resourceTypeCode: 'NODE' },
      sourceConnections: [...context.sourceConnections, { id: 'prom', name: 'Prometheus', connectorType: 'PROMETHEUS', active: true }] } })

    const path = await screen.findByRole('navigation', { name: 'Location in the infrastructure' })
    expect(within(path).getAllByRole('link').map(link => link.textContent)).toEqual(['App', 'Production', 'fin-prod-01'])
    expect(screen.getByText('Sources · 2')).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Open connection Finnish Node' }).getAttribute('href'))
      .toBe('/organizations/org/connections/finnish?project=project&fromResource=postgres&fromEnvironment=env')
    expect(screen.getByRole('link', { name: 'Open connection Prometheus' })).toBeTruthy()
    expect(screen.queryByRole('heading', { name: 'Context' })).toBeNull()
  })

  it('returns to the incident or connection it was opened from', async () => {
    renderPage({ resource: container }, { path: '/organizations/org/environments/env/resources/postgres?fromIncident=cpu' })
    await screen.findByText('postgres:17')
    expect(document.querySelector('.workspace-back')?.getAttribute('href')).toBe('/organizations/org/incidents/cpu')
    cleanup()

    renderPage({ resource: container }, { path: '/organizations/org/environments/env/resources/postgres?fromConnection=finnish' })
    await screen.findByText('postgres:17')
    await waitFor(() => expect(document.querySelector('.workspace-back')?.textContent).toBe('Finnish Node'))
    expect(document.querySelector('.workspace-back')?.getAttribute('href')).toBe('/organizations/org/connections/finnish?tab=resources')
  })

  it('keeps the resource usable when its context cannot be read', async () => {
    renderPage({ resource: container, context: 'fail' as unknown as ResourceContextResponse })
    expect(await screen.findByText('postgres:17')).toBeTruthy()
    expect(await screen.findByText('Unable to load where this resource lives')).toBeTruthy()
  })

  it('lists the incidents of a monitored resource in its own tab, open first', async () => {
    const requests = renderPage({ resource: server, context: { ...context, openIncidentCount: 1 } })
    fireEvent.click(await screen.findByRole('tab', { name: 'Incidents 1' }))
    expect(await screen.findByText('No active incidents')).toBeTruthy()
    await waitFor(() => expect(requests).toContain('/resources/server/incidents?status=OPEN&limit=50'))
    expect(requests).toContain('/resources/server/incidents?status=RESOLVED&limit=20')
  })
})
