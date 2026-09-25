import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it } from 'vitest'

import { ApiError } from '../api/httpClient'
import type { HistoryEventResponse } from '../types/historyEvent'
import type { OperationsOverviewResponse } from '../types/overview'
import { OverviewBody, OverviewPage, type OverviewState } from './OverviewPage'
import { I18nProvider, type Locale } from '../i18n'

const activity: HistoryEventResponse = {
  id: 'event', eventType: 'SYNC_FAILED', source: 'SYSTEM', occurredAt: '2026-09-25T09:00:00Z',
  resource: null, connection: { id: 'connection', name: 'finland_node' }, actor: null, incident: null,
  operation: null, sync: { id: 'session', status: 'FAILED', errorCode: 'SSH_HOST_KEY_MISMATCH' },
}

const loaded: OperationsOverviewResponse = {
  scope: { type: 'ORGANIZATION' },
  summary: {
    nodes: { total: 3, online: 2, offline: 1 },
    containers: { total: 24, running: 21, stopped: 3 },
    connections: { total: 5, healthy: 3, failing: 1, neverSynced: 1 },
    incidents: { open: 2, threshold: 1, noData: 1 },
    operations: { failed: 1, unknown: 1 },
  },
  attention: {
    total: 3,
    items: [
      {
        kind: 'OPERATION_UNKNOWN', priority: 1, id: 'execution', occurredAt: '2026-09-25T09:10:00Z',
        resource: { id: 'api', name: 'api-1', resourceTypeCode: 'CONTAINER', environmentId: 'environment' },
        connection: null, incident: null, sync: null,
        operation: { operationCode: 'CONTAINER_STOP', errorCode: 'OPERATION_RESULT_UNKNOWN', errorMessage: 'x' },
      },
      {
        kind: 'INCIDENT', priority: 2, id: 'incident', occurredAt: '2026-09-25T09:05:00Z',
        resource: { id: 'node', name: 's260540.love-is.nexus', resourceTypeCode: 'NODE', environmentId: 'environment' },
        connection: null, operation: null, sync: null,
        incident: { reason: 'NO_DATA', metricCode: 'CPU_USAGE_PERCENT' },
      },
      {
        kind: 'SYNC_FAILED', priority: 4, id: 'session', occurredAt: '2026-09-25T09:00:00Z',
        resource: null, connection: { id: 'connection', name: 'finland_node' }, incident: null, operation: null,
        sync: { errorCode: 'SSH_HOST_KEY_MISMATCH', errorMessage: 'SSH host key does not match' },
      },
    ],
  },
  recentActivity: [activity],
  operationsHorizonHours: 24,
}

const empty: OperationsOverviewResponse = {
  ...loaded,
  summary: {
    nodes: { total: 0, online: 0, offline: 0 },
    containers: { total: 0, running: 0, stopped: 0 },
    connections: { total: 0, healthy: 0, failing: 0, neverSynced: 0 },
    incidents: { open: 0, threshold: 0, noData: 0 },
    operations: { failed: 0, unknown: 0 },
  },
  attention: { items: [], total: 0 },
  recentActivity: [],
}

function render(path: string, role: 'OWNER' | 'MEMBER', data?: { key: unknown[]; value: OperationsOverviewResponse }, locale: Locale = 'en') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  client.setQueryData(['me'], { id: 'user', email: 'member@example.com', displayName: 'Member' })
  client.setQueryData(['my-organizations'], [{ id: 'org', code: 'ORG', name: 'Example org', role }])
  client.setQueryData(['projects', 'org'], [{ id: 'project', organizationId: 'org', code: 'app', name: 'App', description: null }])
  client.setQueryData(['environments', 'org', 'project'], [
    { id: 'environment', organizationId: 'org', projectId: 'project', code: 'prod', name: 'Production', kind: 'PROD' },
  ])
  client.setQueryData(['connections', 'org'], [])
  if (data !== undefined) client.setQueryData(data.key, data.value)
  return renderToStaticMarkup(<I18nProvider initialLocale={locale}><QueryClientProvider client={client}>
    <MemoryRouter initialEntries={[path]}><Routes>
      <Route path="/organizations/:organizationId/overview" element={<OverviewPage />} />
    </Routes></MemoryRouter>
  </QueryClientProvider></I18nProvider>)
}

function body(state: Partial<OverviewState>): string {
  const full: OverviewState = { isPending: false, isError: false, error: null, data: undefined, refetch: () => undefined, ...state }
  return renderToStaticMarkup(<I18nProvider initialLocale="en"><MemoryRouter>
    <OverviewBody organizationId="org" projectId={null} environmentId={null} state={full} />
  </MemoryRouter></I18nProvider>)
}

describe('operations overview page', () => {
  it('renders the fleet summary, attention in server order and recent activity', () => {
    const html = render('/organizations/org/overview', 'OWNER', { key: ['overview', 'org', null, null], value: loaded })

    expect(html).toContain('Infrastructure overview')
    expect(html).toContain('Whole organization')
    expect(html).toContain('2 online · 1 offline')
    expect(html).toContain('21 running · 3 stopped')
    expect(html).toContain('1 threshold · 1 no data')
    expect(html).toContain('1 failing')
    expect(html).toContain('1 unknown · 1 failed')
    // The backend decides the order; the page keeps it.
    expect(html.indexOf('Stop: result unknown')).toBeLessThan(html.indexOf('No metrics received'))
    expect(html.indexOf('No metrics received')).toBeLessThan(html.indexOf('Server key has changed'))
    expect(html).toContain('Result unknown')
    expect(html).toContain('Incident open')
    expect(html).toContain('Sync failed')
    // No raw identifiers are shown as text.
    expect(html).not.toMatch(/>[^<]*execution[^<]*</)
  })

  it('renders the overview in Russian by default', () => {
    const html = render('/organizations/org/overview', 'OWNER', { key: ['overview', 'org', null, null], value: loaded }, 'ru')

    expect(html).toContain('Обзор инфраструктуры')
    expect(html).toContain('Требует внимания')
    expect(html).toContain('Последние события')
    expect(html).toContain('Остановить: результат неизвестен')
    expect(html).toContain('Метрики не поступают')
    expect(html).toContain('Ключ сервера изменился')
    expect(html).not.toContain('Needs attention')
  })

  it('leads every attention item and activity entry to the real object', () => {
    const html = render('/organizations/org/overview', 'MEMBER', { key: ['overview', 'org', null, null], value: loaded })

    expect(html).toContain('href="/organizations/org/environments/environment/resources/api"')
    expect(html).toContain('href="/organizations/org/incidents/incident"')
    expect(html).toContain('href="/organizations/org/connections/connection/sync-sessions/session"')
    expect(html).toContain('href="/organizations/org/connections/connection"')
    expect(html).toContain('href="/organizations/org/incidents"')
  })

  it('reads the scope from the workspace selection', () => {
    const html = render('/organizations/org/overview?project=project&environment=environment', 'MEMBER',
      { key: ['overview', 'org', 'project', 'environment'], value: { ...loaded,
        scope: { type: 'ENVIRONMENT', projectId: 'project', environmentId: 'environment' } } })

    expect(html).toContain('Environment · Production')
    expect(html).toContain('2 online · 1 offline')
    // Inside an environment the inventory cards open its infrastructure.
    expect(html).toContain('href="/organizations/org/environments/environment?project=project&amp;environment=environment"')
  })

  it('shows a member the overview without any owner-only control', () => {
    const page = render('/organizations/org/overview', 'MEMBER', { key: ['overview', 'org', null, null], value: loaded })
    // The shell's sign-out button is not part of the overview.
    const html = page.slice(page.indexOf('<main'))

    for (const control of ['New project', 'Add SSH connection', 'Synchronize', 'Start', 'Restart', '<button']) {
      expect(html).not.toContain(control)
    }
  })

  it('shows a loading state until the overview arrives', () => {
    const html = render('/organizations/org/overview', 'OWNER')

    expect(html).toContain('aria-label="Loading overview"')
  })

  it('guides a new organization through setup instead of showing zeros', () => {
    const html = render('/organizations/org/overview', 'OWNER', { key: ['overview', 'org', null, null], value: empty }, 'ru')

    expect(html).toContain('Добро пожаловать в InfraDesk')
    expect(html).toContain('Организация создана')
    // The seeded project and environment exist; the next step is connecting a server.
    expect(html).toMatch(/aria-current="step".*Подключите сервер/s)
    expect(html).toContain('href="/organizations/org/connections/new"')
    expect(html).not.toContain('summary-grid')
    expect(html).not.toContain('role="alert"')
  })

  it('shows a member the setup state without actions they cannot take', () => {
    const html = render('/organizations/org/overview', 'MEMBER', { key: ['overview', 'org', null, null], value: empty }, 'ru')

    expect(html).toContain('Этот шаг выполняет владелец организации.')
    expect(html).not.toContain('href="/organizations/org/connections/new"')
  })

  it('treats an empty project scope as empty, not as an error, and says when all is well', () => {
    const html = render('/organizations/org/overview?project=project', 'OWNER',
      { key: ['overview', 'org', 'project', null], value: empty })

    expect(html).toContain('No infrastructure discovered yet')
    expect(html).toContain('Everything is working')
    expect(html).toContain('No recent activity')
    expect(html).not.toContain('Welcome to InfraDesk')
    expect(html).not.toContain('role="alert"')
  })

  it('marks an unknown operation result differently from a failure', () => {
    const html = render('/organizations/org/overview', 'OWNER', { key: ['overview', 'org', null, null], value: loaded })

    expect(html).toMatch(/class="attention-item tone-warning" data-kind="OPERATION_UNKNOWN"/)
    expect(html).toMatch(/class="attention-item tone-danger" data-kind="SYNC_FAILED"/)
  })

  it('shows a safe error with a retry instead of a blank page', () => {
    const failed = body({ isError: true, error: new ApiError(500, 'INTERNAL_ERROR', 'database password leaked') })
    const missing = body({ isError: true, error: new ApiError(404, 'ENVIRONMENT_NOT_FOUND', 'Environment was not found') })

    expect(failed).toContain('Unable to load overview')
    expect(failed).toContain('Retry')
    expect(failed).not.toContain('database password')
    expect(missing).toContain('Environment not found')
  })

  it('says how many items the bounded list leaves out', () => {
    const html = body({ data: { ...loaded, attention: { ...loaded.attention, total: 27 } } })

    expect(html).toContain('Showing 3 of 27')
  })
})
