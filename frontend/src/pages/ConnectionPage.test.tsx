// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { I18nProvider, type Locale } from '../i18n'
import type { ConnectionResponse } from '../types/connection'
import type { IncidentListItemResponse } from '../types/incident'
import type { ConnectionInfrastructureSummary, LocatedResourceResponse } from '../types/infrastructure'
import { ConnectionPage } from './ConnectionPage'

const environment = { id: 'env', name: 'Production', kind: 'PROD' }
const base = { organizationId: 'org', environmentId: 'env', resourceTypeId: 'type', active: true, createdAt: '', updatedAt: '', environment }
const node: LocatedResourceResponse = { ...base, id: 'node', parentResourceId: null, code: 'fin-prod-01', name: 'fin-prod-01',
  resourceTypeCode: 'NODE', data: { kind: 'NODE', spec: null, status: { online: true, cpuUsagePercent: null, memoryUsagePercent: null, uptimeSeconds: null } } }
const backend: LocatedResourceResponse = { ...base, id: 'backend', parentResourceId: 'node', code: 'backend', name: 'backend',
  resourceTypeCode: 'CONTAINER', data: { kind: 'CONTAINER', spec: { image: 'backend:1' }, status: { state: 'running' } } }
const redis: LocatedResourceResponse = { ...backend, id: 'redis', code: 'redis', name: 'redis' }

const incident: IncidentListItemResponse = {
  id: 'cpu', monitorRuleId: 'rule', resourceId: 'backend', status: 'OPEN', reason: 'THRESHOLD',
  startedAt: '2026-09-27T10:00:00Z', openedAt: '2026-09-27T10:00:00Z', resolvedAt: null, createdAt: '', updatedAt: '',
  resource: { id: 'backend', name: 'backend', resourceTypeCode: 'CONTAINER' },
  project: { id: 'project', name: 'SvinPeak' }, environment,
  monitorRule: { id: 'rule', metricCode: 'CPU_USAGE_PERCENT', operator: 'GREATER_THAN', threshold: 85, forSeconds: 300, noDataSeconds: 900 },
  parentResource: { id: 'node', name: 'fin-prod-01', resourceTypeCode: 'NODE' },
  sourceConnections: [{ id: 'finnish', name: 'Finnish Node', connectorType: 'SSH', active: true }],
}

const synced = { id: 'session', status: 'COMPLETED', startedAt: '2026-09-27T10:00:00Z', finishedAt: '2026-09-27T10:00:04Z',
  errorCode: null, errorMessage: null }

function connection(overrides: Partial<ConnectionResponse> = {}): ConnectionResponse {
  return {
    id: 'finnish', connectorType: 'SSH', code: 'fin-node', name: 'Finnish Node',
    scope: { type: 'ENVIRONMENT', projectId: 'project', environmentId: 'env' }, active: true,
    schedule: null, lastSync: synced, createdAt: '', updatedAt: '',
    ssh: { host: 'fin.example', port: 22, username: 'root', hostKeyFingerprint: 'SHA256:k',
      credentialConfigured: true, authenticationType: 'PRIVATE_KEY', hostTrusted: true },
    ...overrides,
  }
}

const summary: ConnectionInfrastructureSummary = {
  activeResourceCount: 3, inactiveResourceCount: 0, openIncidentCount: 1,
  resourceTypeCounts: [{ resourceTypeCode: 'CONTAINER', count: 2 }, { resourceTypeCode: 'NODE', count: 1 }],
  openIncidents: [incident], resources: [node, backend, redis],
}

interface Answers {
  connection?: ConnectionResponse
  open?: (url: string) => IncidentListItemResponse[]
  summary?: ConnectionInfrastructureSummary | 'fail'
}

/** Answers every read the page may make and records them; the shell's own data is seeded. */
function renderPage(answers: Answers = {}, options: { locale?: Locale; role?: 'OWNER' | 'MEMBER'; path?: string } = {}) {
  const requests: string[] = []
  const json = (body: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(body),
    { status, headers: { 'Content-Type': 'application/json' } }))
  vi.stubGlobal('fetch', vi.fn().mockImplementation((input: string) => {
    const path = String(input).replace('/api/v1/organizations/org', '')
    requests.push(path)
    if (path.endsWith('/infrastructure-summary')) {
      return answers.summary === 'fail' ? json({ code: 'INTERNAL_ERROR', message: 'x' }, 500) : json(answers.summary ?? summary)
    }
    if (path.endsWith('/finnish/resources')) return json([node, backend, redis])
    if (path.includes('/finnish/incidents?status=OPEN')) return json(answers.open ? answers.open(path) : [incident])
    if (path.includes('/finnish/incidents?status=RESOLVED')) return json([])
    if (path.endsWith('/sync-sessions')) return json([synced])
    return json(answers.connection ?? connection())
  }))
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
  client.setQueryData(['me'], { id: 'user', email: 'owner@example.com', displayName: 'Owner' })
  client.setQueryData(['my-organizations'], [{ id: 'org', code: 'ORG', name: 'Org', role: options.role ?? 'OWNER' }])
  client.setQueryData(['projects', 'org'], [{ id: 'project', organizationId: 'org', code: 'svin', name: 'SvinPeak', description: null }])
  client.setQueryData(['environments', 'org', 'project'], [{ id: 'env', organizationId: 'org', projectId: 'project', code: 'prod', name: 'Production', kind: 'PROD' }])
  render(<I18nProvider initialLocale={options.locale ?? 'en'}><QueryClientProvider client={client}>
    <MemoryRouter initialEntries={[options.path ?? '/organizations/org/connections/finnish']}><Routes>
      <Route path="/organizations/:organizationId/connections/:connectionId" element={<ConnectionPage />} />
    </Routes></MemoryRouter>
  </QueryClientProvider></I18nProvider>)
  return requests
}

const tabNames = () => screen.getAllByRole('tab').map(tab => tab.textContent)

describe('connection workspace', () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals() })

  it('shows what the connection discovered, with counts in the tabs, and loads no full list up front', async () => {
    const requests = renderPage()
    expect(await screen.findByRole('tree', { name: 'Infrastructure · Production' })).toBeTruthy()
    expect(tabNames()).toEqual(['Overview', 'Resources 3', 'Terminal', 'Incidents 1', 'Synchronization'])
    // The containers sit under their server.
    const server = screen.getAllByRole('treeitem')[0]
    const names = (item: HTMLElement) => item.querySelector('.resource-name')?.firstChild?.textContent
    expect(names(server)).toBe('fin-prod-01')
    expect(within(server).getAllByRole('treeitem').map(names)).toEqual(['backend', 'redis'])
    expect(screen.getByText('1 server · 2 containers')).toBeTruthy()
    // The open incident is named by its resource and leads to the incident, remembering this page.
    expect(screen.getByRole('link', { name: /^backend — Threshold exceeded/ }).getAttribute('href'))
      .toBe('/organizations/org/incidents/cpu?fromConnection=finnish')
    expect(requests.some(path => path.endsWith('/finnish/resources'))).toBe(false)
    expect(requests.some(path => path.includes('/incidents?'))).toBe(false)
  })

  it('keeps previously discovered resources separate from the active count', async () => {
    renderPage({ summary: { ...summary, inactiveResourceCount: 4 } }, { locale: 'ru' })
    expect(await screen.findByText('1 сервер · 2 контейнера')).toBeTruthy()
    expect(screen.getByText('4 ранее обнаруженных ресурса больше не найдены')).toBeTruthy()
    expect(screen.getByText('Активные ресурсы').nextElementSibling?.textContent).toContain('3')
  })

  it('opens the resources tab from the URL, loads the list then, and links each resource back here', async () => {
    const requests = renderPage({}, { path: '/organizations/org/connections/finnish?tab=resources&project=project' })
    const tree = await screen.findByRole('tree', { name: 'Resources · Production' })
    expect(screen.getByRole('tab', { name: 'Resources 3' }).getAttribute('aria-selected')).toBe('true')
    expect(within(tree).getByRole('link', { name: /backend/ }).getAttribute('href'))
      .toBe('/organizations/org/environments/env/resources/backend?project=project&fromConnection=finnish')
    expect(requests.filter(path => path.endsWith('/finnish/resources'))).toHaveLength(1)
  })

  it('loads the incidents of its resources only when their tab opens', async () => {
    const requests = renderPage()
    await screen.findByRole('tree', { name: 'Infrastructure · Production' })
    fireEvent.click(screen.getByRole('tab', { name: 'Incidents 1' }))
    expect(await screen.findByRole('list', { name: 'Open incidents' })).toBeTruthy()
    expect(await screen.findByText('No resolved incidents yet')).toBeTruthy()
    await waitFor(() => expect(requests).toContain('/connections/finnish/incidents?status=OPEN&limit=50'))
    expect(requests).toContain('/connections/finnish/incidents?status=RESOLVED&limit=20')
    // The environment and source of each row come from the list response.
    expect(screen.getByText('Production · Finnish Node')).toBeTruthy()
  })

  it('explains an empty connection: nothing yet before the first sync, nothing found after one', async () => {
    const empty = { ...summary, activeResourceCount: 0, openIncidentCount: 0, resourceTypeCounts: [], openIncidents: [], resources: [] }
    renderPage({ summary: empty, connection: connection({ lastSync: null }) })
    expect(await screen.findByText('Resources appear after the first synchronization')).toBeTruthy()
    expect(screen.getAllByRole('button', { name: 'Synchronize' }).length).toBeGreaterThan(1)
    expect(screen.getAllByText('No active incidents').length).toBeGreaterThan(0)
    cleanup()

    renderPage({ summary: empty })
    expect(await screen.findByText('No resources discovered')).toBeTruthy()
  })

  it('keeps the connection itself usable when its infrastructure summary fails', async () => {
    renderPage({ summary: 'fail' })
    expect(await screen.findByText('Unable to load the infrastructure of this connection')).toBeTruthy()
    expect(screen.getByRole('heading', { level: 1, name: 'Finnish Node' })).toBeTruthy()
    expect(screen.getByText('fin.example')).toBeTruthy()
    // Without counts the tabs still name themselves.
    expect(tabNames()).toEqual(['Overview', 'Resources', 'Terminal', 'Incidents', 'Synchronization'])
  })

  it('places the connection in its project and environment, by name', async () => {
    renderPage()
    const path = await screen.findByRole('navigation', { name: 'Location in the infrastructure' })
    expect(within(path).getByRole('link', { name: 'SvinPeak' }).getAttribute('href')).toBe('/organizations/org/resources?project=project')
    expect(within(path).getByRole('link', { name: 'Production' }).getAttribute('href'))
      .toBe('/organizations/org/environments/env?project=project&environment=env')
  })

  it('speaks Russian', async () => {
    renderPage({}, { locale: 'ru' })
    expect(await screen.findByRole('tab', { name: 'Ресурсы 3' })).toBeTruthy()
    expect(screen.getByText('Открытые инциденты', { selector: 'dt' })).toBeTruthy()
    expect(screen.getByText('1 открытый инцидент')).toBeTruthy()
  })

  it('pages open incidents and keeps the authoritative total, never the loaded count', async () => {
    const opened = (index: number) => ({ ...incident, id: `open-${index}`, resource: { ...incident.resource, name: `svc-${index}` } })
    const requests = renderPage({ summary: { ...summary, openIncidentCount: 237 },
      open: url => url.includes('beforeId=') ? Array.from({ length: 50 }, (_, index) => opened(50 + index))
        : Array.from({ length: 50 }, (_, index) => opened(index)) }, { path: '/organizations/org/connections/finnish?tab=incidents' })
    expect(await screen.findByText('50 of 237')).toBeTruthy()
    fireEvent.click(screen.getAllByRole('button', { name: 'Show more' })[0])
    expect(await screen.findByText('100 of 237')).toBeTruthy()
    // The page continues after the exact last row, even though every row opened at the same instant.
    const next = requests.find(path => path.includes('beforeId='))
    expect(next).toContain('beforeId=open-49')
    expect(next).toContain(`beforeOpenedAt=${encodeURIComponent(incident.openedAt)}`)
  })

  it('returns to the resource or incident it was opened from, and to the list otherwise', async () => {
    renderPage({}, { path: '/organizations/org/connections/finnish?fromResource=backend&fromEnvironment=env' })
    await screen.findByRole('heading', { level: 1, name: 'Finnish Node' })
    expect(document.querySelector('.workspace-back')?.getAttribute('href')).toBe('/organizations/org/environments/env/resources/backend')
    cleanup()

    renderPage({}, { path: '/organizations/org/connections/finnish?fromIncident=cpu' })
    await screen.findByRole('heading', { level: 1, name: 'Finnish Node' })
    expect(document.querySelector('.workspace-back')?.getAttribute('href')).toBe('/organizations/org/incidents/cpu')
    cleanup()

    renderPage()
    await screen.findByRole('heading', { level: 1, name: 'Finnish Node' })
    expect(document.querySelector('.workspace-back')?.getAttribute('href')).toBe('/organizations/org/connections')
  })
})
