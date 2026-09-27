// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { I18nProvider, type Locale } from '../i18n'
import type { IncidentListItemResponse } from '../types/incident'
import { IncidentPage } from './IncidentPage'

const incident: IncidentListItemResponse = {
  id: 'cpu', monitorRuleId: '0b69c4d2-5d1e-4f7a-9d3e-1c2b3a4d5e6f', resourceId: 'backend', status: 'OPEN', reason: 'THRESHOLD',
  startedAt: '2026-09-27T10:00:00Z', openedAt: '2026-09-27T10:05:00Z', resolvedAt: null, createdAt: '', updatedAt: '',
  resource: { id: 'backend', name: 'backend', resourceTypeCode: 'CONTAINER' },
  project: { id: 'project', name: 'SvinPeak' }, environment: { id: 'env', name: 'Production', kind: 'PROD' },
  monitorRule: { id: '0b69c4d2-5d1e-4f7a-9d3e-1c2b3a4d5e6f', metricCode: 'CPU_USAGE_PERCENT', operator: 'GREATER_THAN',
    threshold: 85, forSeconds: 300, noDataSeconds: 900 },
  parentResource: { id: 'node', name: 'fin-prod-01', resourceTypeCode: 'NODE' },
  sourceConnections: [{ id: 'finnish', name: 'Finnish Node', connectorType: 'SSH', active: true }],
}

/** The incident is one read; the operations reads decide whether "Go to operations" is offered. */
function renderPage(value: IncidentListItemResponse = incident, options: { locale?: Locale; path?: string; operations?: string[] } = {}) {
  const requests: string[] = []
  vi.stubGlobal('fetch', vi.fn().mockImplementation((input: string) => {
    const path = String(input).replace('/api/v1/organizations/org', '')
    requests.push(path)
    const body = path.endsWith('/operations') ? { operations: options.operations ?? [], unavailableReason: null }
      : path.includes('/operation-executions') ? []
        : path.endsWith('/environments') ? [{ id: 'env', organizationId: 'org', projectId: 'project', code: 'prod', name: 'Production', kind: 'PROD' }]
          : value
    return Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }))
  }))
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
  client.setQueryData(['me'], { id: 'user', email: 'owner@example.com', displayName: 'Owner' })
  client.setQueryData(['my-organizations'], [{ id: 'org', code: 'ORG', name: 'Org', role: 'OWNER' }])
  client.setQueryData(['projects', 'org'], [])
  render(<I18nProvider initialLocale={options.locale ?? 'en'}><QueryClientProvider client={client}>
    <MemoryRouter initialEntries={[options.path ?? '/organizations/org/incidents/cpu']}><Routes>
      <Route path="/organizations/:organizationId/incidents/:incidentId" element={<IncidentPage />} />
    </Routes></MemoryRouter>
  </QueryClientProvider></I18nProvider>)
  return requests
}

describe('incident page', () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals() })

  it('names what happened and where, never by identifier', async () => {
    const requests = renderPage()
    expect(await screen.findByRole('heading', { level: 1, name: 'CPU usage threshold breached' })).toBeTruthy()
    expect(screen.getByText('backend · Container', { selector: '.workspace-subtitle' })).toBeTruthy()
    expect(document.body.textContent).not.toContain('0b69')
    expect(screen.getAllByText('CPU usage > 85% for 5m').length).toBeGreaterThan(0)
    // Everything above came from the incident read itself: no resource or connection is read.
    expect(requests.filter(path => path.startsWith('/incidents'))).toEqual(['/incidents/cpu'])
    expect(requests.some(path => /^\/resources\/[^/]+$/.test(path) || path.startsWith('/connections'))).toBe(false)
  })

  it('links every related object by its canonical route', async () => {
    renderPage()
    const path = await screen.findByRole('navigation', { name: 'Location in the infrastructure' })
    expect(within(path).getAllByRole('link').map(link => [link.textContent, link.getAttribute('href')])).toEqual([
      ['SvinPeak', '/organizations/org/resources?project=project'],
      ['Production', '/organizations/org/environments/env?project=project&environment=env'],
      // Links out of the incident remember it, so their "back" returns to it.
      ['Finnish Node', '/organizations/org/connections/finnish?fromIncident=cpu'],
      ['fin-prod-01', '/organizations/org/environments/env/resources/node?fromIncident=cpu'],
      ['backend', '/organizations/org/environments/env/resources/backend?fromIncident=cpu'],
    ])
    expect(screen.getByRole('link', { name: 'Open resource backend' }).getAttribute('href'))
      .toBe('/organizations/org/environments/env/resources/backend?fromIncident=cpu')
    expect(screen.getByRole('link', { name: 'Open connection' }).getAttribute('href'))
      .toBe('/organizations/org/connections/finnish?fromIncident=cpu')
    expect(screen.getByRole('link', { name: 'CPU usage > 85% for 5m' }).getAttribute('href'))
      .toBe('/organizations/org/environments/env/resources/backend?fromIncident=cpu&tab=monitoring')
    // Operations are only led to when the resource offers them.
    expect(screen.queryByRole('link', { name: 'Go to operations' })).toBeNull()
  })

  it('offers operations only as a way to the resource page, where they are confirmed', async () => {
    renderPage(incident, { operations: ['CONTAINER_RESTART'] })
    expect((await screen.findByRole('link', { name: 'Go to operations' })).getAttribute('href'))
      .toBe('/organizations/org/environments/env/resources/backend?fromIncident=cpu&tab=operations')
  })

  it('keeps every source connection and pretends none is the one', async () => {
    renderPage({ ...incident, sourceConnections: [...incident.sourceConnections,
      { id: 'prom', name: 'Prometheus', connectorType: 'PROMETHEUS', active: false }] })
    const path = await screen.findByRole('navigation', { name: 'Location in the infrastructure' })
    expect(within(path).queryByText('Finnish Node')).toBeNull()
    expect(screen.getByRole('link', { name: 'Open connection Finnish Node' })).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Open connection Prometheus' })).toBeTruthy()
    expect(screen.queryByRole('link', { name: 'Open connection' })).toBeNull()
    expect(screen.getByText('Sources')).toBeTruthy()
  })

  it('returns to the page it was opened from, or to the incident list', async () => {
    renderPage(incident, { path: '/organizations/org/incidents/cpu?fromConnection=finnish' })
    await screen.findByRole('heading', { level: 1, name: 'CPU usage threshold breached' })
    const back = document.querySelector('.workspace-back')
    expect(back?.textContent).toBe('Finnish Node')
    expect(back?.getAttribute('href')).toBe('/organizations/org/connections/finnish?tab=incidents')
    cleanup()

    renderPage(incident, { path: '/organizations/org/incidents/cpu?status=RESOLVED' })
    await screen.findByRole('heading', { level: 1, name: 'CPU usage threshold breached' })
    expect(document.querySelector('.workspace-back')?.getAttribute('href')).toBe('/organizations/org/incidents?status=RESOLVED')
  })

  it('shows a resource with no parent and no source without breaking', async () => {
    renderPage({ ...incident, reason: 'NO_DATA', parentResource: null, sourceConnections: [] }, { locale: 'ru' })
    expect(await screen.findByRole('heading', { level: 1, name: 'Нет данных: Загрузка процессора' })).toBeTruthy()
    expect(screen.getByText('Не обнаружен подключением')).toBeTruthy()
    expect(screen.getByText('Нет данных дольше')).toBeTruthy()
  })
})
