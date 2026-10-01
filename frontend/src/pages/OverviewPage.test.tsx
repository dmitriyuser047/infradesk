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

function body(state: Partial<OverviewState>, locale: Locale = 'en'): string {
  const full: OverviewState = { isPending: false, isError: false, error: null, data: undefined, refetch: () => undefined, ...state }
  return renderToStaticMarkup(<I18nProvider initialLocale={locale}><MemoryRouter>
    <OverviewBody organizationId="org" projectId={null} environmentId={null} state={full} />
  </MemoryRouter></I18nProvider>)
}

describe('operations overview page', () => {
  it('shows an honest problem state and emphasizes incidents after the overall health', () => {
    const html = body({ data: loaded }, 'ru')
    expect(html).toContain('Есть проблемы, требующие внимания')
    expect(html).toContain('1 сервер недоступен')
    expect(html).toContain('id="overview-attention"')
    expect(html).toMatch(/summary-card-priority[^>]+data-card="incidents"/)
    expect(html.indexOf('data-health="warning"')).toBeLessThan(html.indexOf('summary-grid'))
    expect(html.indexOf('summary-grid')).toBeLessThan(html.indexOf('id="overview-attention"'))
  })

  it('groups only explicitly related discovery facts without claiming a full sync result', () => {
    const discovered: HistoryEventResponse = { ...activity, id: 'discovered', eventType: 'RESOURCE_DISCOVERED',
      sync: { id: 'session', status: 'COMPLETED', errorCode: null },
      resource: { id: 'node', name: 'node-1', resourceTypeCode: 'NODE', environmentId: 'environment' } }
    const html = body({ data: { ...loaded, recentActivity: [discovered,
      { ...discovered, id: 'gone', eventType: 'RESOURCE_DEACTIVATED' }] } }, 'ru')
    expect(html.match(/class="activity-entry/g)).toHaveLength(1)
    expect(html).toContain('Изменения ресурсов при синхронизации')
    expect(html).toContain('В загруженных событиях: обнаружено 1 ресурс · больше не найдено 1 ресурс')
    expect(html).toContain('activity-neutral activity-discovery')
    expect(html).not.toContain('Синхронизация завершена успешно')
  })

  it('bounds activity at eight entries and keeps timestamps without an invented journal link', () => {
    const events = Array.from({ length: 15 }, (_, index) => ({ ...activity, id: `event-${index}`,
      eventType: 'OPERATION_SUCCEEDED' as const, sync: null }))
    const html = body({ data: { ...loaded, recentActivity: events } })
    expect(html.match(/class="activity-entry/g)).toHaveLength(8)
    expect(html).toContain('Showing 8 of 15 loaded recent events')
    expect(html).toContain('dateTime="2026-09-25T09:00:00Z"')
    expect(html).not.toContain('activity-critical')
    expect(html).not.toContain('All events')
  })

  it.each(['ru', 'en'] as const)('keeps discovery events and their order with clear stale wording in %s', locale => {
    const discovered: HistoryEventResponse = { ...activity, id: 'discovered', eventType: 'RESOURCE_DISCOVERED',
      sync: null, resource: { id: 'node', name: 'node-1', resourceTypeCode: 'NODE', environmentId: 'environment' } }
    const gone: HistoryEventResponse = { ...discovered, id: 'gone', eventType: 'RESOURCE_DEACTIVATED' }
    const html = body({ data: { ...loaded, recentActivity: [gone, discovered] } }, locale)
    const stale = locale === 'ru' ? 'Ресурс больше не найден' : 'Resource no longer found'
    const found = locale === 'ru' ? 'Ресурс обнаружен' : 'Resource discovered'
    expect(html.match(/class="activity-entry/g)).toHaveLength(2)
    expect(html.indexOf(stale)).toBeGreaterThan(-1)
    expect(html.indexOf(found)).toBeGreaterThan(html.indexOf(stale))
    expect(html).not.toContain('больше не обнаруживается')
  })

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

  it('loads inside the same frame the overview fills, so nothing jumps when data arrives', () => {
    const html = body({ isPending: true })

    expect(html).toContain('aria-busy="true"')
    expect(html.match(/summary-card summary-card-skeleton/g)).toHaveLength(5)
    expect(html).toMatch(/class="overview-columns".*Needs attention.*Recent activity/s)
    expect(html).not.toContain('role="alert"')
    expect(html).toContain('overview-health-skeleton')
  })

  it('guides a new organization through setup instead of showing zeros', () => {
    const html = render('/organizations/org/overview', 'OWNER', { key: ['overview', 'org', null, null], value: empty }, 'ru')

    expect(html).toContain('Добро пожаловать в InfraDesk')
    expect(html).toContain('Инфраструктура ещё не подключена')
    expect(html).not.toContain('Все системы работают нормально')
    expect(html).toContain('Добавить подключение')
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

    expect(html).toContain('Infrastructure is not connected yet')
    expect(html).not.toContain('All systems are operating normally')
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

  describe('Stage 17.6A presentation', () => {
    const healthy: OperationsOverviewResponse = { ...loaded, attention: { items: [], total: 0 },
      summary: { ...empty.summary, nodes: { total: 2, online: 2, offline: 0 },
        containers: { total: 4, running: 4, stopped: 0 }, connections: { total: 2, healthy: 2, failing: 0, neverSynced: 0 } } }

    it('shows no problems as one compact healthy line, in English and Russian', () => {
      const en = body({ data: healthy })
      const ru = body({ data: healthy }, 'ru')

      expect(en).toContain('data-health="normal"')
      expect(en).toContain('All systems are operating normally')
      expect(ru).toContain('Все системы работают нормально')
      expect(ru).toContain('2 сервера в сети · 4 контейнера работают · активных инцидентов нет')
      expect(en).not.toContain('attention-section')
      expect(ru).not.toContain('Требует внимания')
      expect(en).toContain('summary-grid')
      expect(ru).not.toContain('Инфраструктура не требует внимания')
      expect(ru).not.toContain('Всё в порядке')
      // A healthy state is not an alert and not a missing list.
      expect(en).not.toContain('attention-list')
      expect(en).not.toContain('role="alert"')
    })

    it('separates the problem and its status from the object and the detail', () => {
      const html = body({ data: loaded })
      const item = html.slice(html.indexOf('data-kind="INCIDENT"'))

      expect(item).toMatch(/class="attention-heading"><span class="attention-title">No metrics received<\/span><span class="status-indicator status-warning">/)
      expect(item).toMatch(/class="attention-secondary"><span class="attention-subject">s260540\.love-is\.nexus<\/span><span class="attention-detail">/)
      expect(item).toMatch(/<time class="attention-time" dateTime="2026-09-25T09:05:00Z"/)
    })

    it('reads an activity entry as event and time first, then object, detail and actor', () => {
      const html = body({ data: loaded })
      const entry = html.slice(html.indexOf('class="activity-entry'))

      expect(entry).toMatch(/class="activity-heading"><span class="activity-title">Synchronization failed<\/span><time class="activity-time"/)
      expect(entry).toMatch(/class="activity-secondary"><a class="activity-subject" href="\/organizations\/org\/connections\/connection"[^>]*>finland_node<\/a><span class="activity-detail">[^<]+<\/span><span class="activity-actor">System<\/span>/)
      // The scrolling list stays reachable from the keyboard.
      expect(html).toContain('class="activity-scroll" tabindex="0" role="region" aria-label="Recent activity"')
      // Beside fewer than three attention items it does not scroll, so it is no tab stop.
      const few = body({ data: { ...loaded, attention: { items: loaded.attention.items.slice(0, 2), total: 2 } } })
      expect(few).toContain('class="activity-scroll" role="region" aria-label="Recent activity"')
    })

    it('explains an empty history in one compact line, in English and Russian', () => {
      const data = { ...loaded, recentActivity: [] }

      expect(body({ data })).toMatch(/empty-compact.*No recent activity/s)
      expect(body({ data }, 'ru')).toContain('Событий пока нет')
      expect(body({ data })).not.toContain('activity-timeline')
      expect(body({ data })).toContain('Recent infrastructure changes will appear here.')
      expect(body({ data })).not.toContain('empty-success')
    })

    it('keeps the last overview on screen when a refresh fails, and says how old it is', () => {
      const updatedAt = Date.parse('2026-09-25T09:30:00Z')
      const failed = new ApiError(500, 'INTERNAL_ERROR', 'database password leaked')
      const en = body({ data: loaded, isError: true, error: failed, dataUpdatedAt: updatedAt })
      const ru = body({ data: loaded, isError: true, error: failed, dataUpdatedAt: updatedAt }, 'ru')

      expect(en).toContain('Unable to refresh data')
      expect(en).toContain('Showing state from')
      expect(en).toContain('2 online · 1 offline')
      expect(en).toContain('No metrics received')
      expect(en).not.toContain('database password')
      expect(ru).toContain('Не удалось обновить данные')
      expect(ru).toContain('Показано состояние на')
      const staleNormal = body({ data: healthy, isError: true, error: failed, dataUpdatedAt: updatedAt })
      expect(staleNormal).toContain('data-health="normal"')
      expect(staleNormal).toContain('Last known state from')
      expect(staleNormal).not.toContain('overview-health-warning')
    })

    it('replaces the last overview when its scope no longer exists', () => {
      const html = body({ data: loaded, isError: true, error: new ApiError(404, 'PROJECT_NOT_FOUND', 'Project was not found') })

      expect(html).toContain('Project not found')
      expect(html).not.toContain('summary-grid')
    })

    it('renders the same layout whatever the number of attention items', () => {
      const item = loaded.attention.items[1]
      const many = Array.from({ length: 20 }, (_, index) => ({ ...item, id: `incident-${index}` }))
      const structure = (data: OperationsOverviewResponse) => {
        const html = body({ data })
        const columns = html.slice(html.indexOf('<div class="overview-columns">'))
        return [html.match(/<div class="overview-columns">/g)?.length,
          columns.match(/<section class="workspace-section [a-z-]+"/g)]
      }
      const expected = [1, ['<section class="workspace-section attention-section"', '<section class="workspace-section activity-section"']]

      expect(structure(healthy)).toEqual([1, ['<section class="workspace-section activity-section"']])
      expect(structure(loaded)).toEqual(expected)
      expect(structure({ ...loaded, attention: { items: many, total: 20 } })).toEqual(expected)
    })
  })
})
