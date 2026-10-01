import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'

import { I18nProvider, type Locale } from '../../i18n'
import type { IncidentListItemResponse } from '../../types/incident'
import { IncidentList } from './IncidentList'

const now = Date.parse('2026-09-25T14:40:00Z')

function incident(overrides: Partial<IncidentListItemResponse>): IncidentListItemResponse {
  return {
    id: 'incident', monitorRuleId: 'rule', resourceId: 'node', status: 'OPEN', reason: 'THRESHOLD',
    startedAt: '2026-09-25T14:32:00Z', openedAt: '2026-09-25T14:32:00Z', resolvedAt: null,
    createdAt: '', updatedAt: '', resource: { id: 'node', name: 'finland-node-01', resourceTypeCode: 'NODE' },
    project: { id: 'project', name: 'SvinPeak' }, environment: { id: 'env', name: 'Production', kind: 'PROD' },
    monitorRule: { id: 'rule', metricCode: 'CPU_USAGE_PERCENT', operator: 'GREATER_THAN', threshold: 85, forSeconds: 300, noDataSeconds: 900 },
    parentResource: null, sourceConnections: [{ id: 'connection', name: 'Finnish Node', connectorType: 'SSH', active: true }],
    ...overrides,
  }
}

function render(incidents: IncidentListItemResponse[], locale: Locale = 'ru'): string {
  // No query client at all: everything a row shows comes from the list response itself.
  return renderToStaticMarkup(<I18nProvider initialLocale={locale}><MemoryRouter>
    <IncidentList organizationId="org" incidents={incidents} now={now} />
  </MemoryRouter></I18nProvider>)
}

describe('incident list', () => {
  const open = incident({ id: 'open', reason: 'NO_DATA' })
  const resolved = incident({ id: 'resolved', status: 'RESOLVED', openedAt: '2026-09-25T13:26:00Z',
    resolvedAt: '2026-09-25T14:40:00Z', resource: { id: 'web', name: 'nginx-web', resourceTypeCode: 'CONTAINER' } })

  it('names the resource, what happened and when, from the list response alone', () => {
    const html = render([open, resolved])

    expect(html).toMatch(/<a class="incident-link"[^>]*>Нет данных: Загрузка процессора<\/a><span class="incident-type">Сервер<\/span>/)
    expect(html).toMatch(/<a class="incident-link"[^>]*>Нарушен порог: Загрузка процессора<\/a><span class="incident-type">Контейнер<\/span>/)
    expect(html).toContain('Данные не поступают')
    expect(html).toContain('Пороговое значение превышено')
    expect(html).not.toMatch(/>(THRESHOLD|NO_DATA)</)
    expect(html).toMatch(/<time dateTime="2026-09-25T14:32:00Z"[^>]*>Открыт [^<]+<\/time><span class="incident-duration">длится 8 мин 0 с<\/span>/)
    expect(html).toMatch(/<time dateTime="2026-09-25T14:40:00Z"[^>]*>Закрыт [^<]+<\/time><span class="incident-duration">длился 1 ч 14 мин<\/span>/)
  })

  it('speaks English too', () => {
    const html = render([open, resolved], 'en')

    expect(html).toContain('No data received')
    expect(html).toContain('Threshold exceeded')
    expect(html).toContain('ongoing for 8m 0s')
    expect(html).toContain('lasted 1h 14m')
    expect(html).toMatch(/>Opened [^<]+<\/time>/)
    expect(html).toMatch(/>Resolved [^<]+<\/time>/)
  })

  it('keeps the order the backend returned, open or resolved', () => {
    const html = render([resolved, open])

    expect(html.indexOf('/incidents/resolved')).toBeLessThan(html.indexOf('/incidents/open'))
  })

  it('shows status in words, reads resolved rows quieter, and links each problem and its affected resource', () => {
    const html = render([open, resolved])

    expect(html).toMatch(/<li class="incident-row incident-open"[^>]*><span class="incident-status"><span class="status-indicator status-danger">.*?Открыт</)
    expect(html).toMatch(/<li class="incident-row incident-resolved"[^>]*><span class="incident-status"><span class="status-indicator status-success">.*?Закрыт</)
    expect(html.match(/<a /g)).toHaveLength(4)
    expect(html).toContain('href="/organizations/org/environments/env/resources/node?fromIncident=open"')
    expect(html).toContain('href="/organizations/org/incidents/open"')
    expect(html).toContain('<ol class="incident-list" aria-label="Список инцидентов">')
  })

  it('names each link by resource, reason and time, so incidents of one resource stay apart', () => {
    const sameResource = [
      incident({ id: 'a', reason: 'NO_DATA', openedAt: '2026-09-25T14:32:00Z' }),
      incident({ id: 'b', reason: 'THRESHOLD', openedAt: '2026-09-25T12:00:00Z' }),
      incident({ id: 'c', reason: 'NO_DATA', status: 'RESOLVED', openedAt: '2026-09-24T08:00:00Z', resolvedAt: '2026-09-24T09:00:00Z' }),
    ]
    const names = [...render(sameResource).matchAll(/<a class="incident-link"[^>]*aria-label="([^"]+)"/g)].map(match => match[1])

    expect(new Set(names).size).toBe(3)
    expect(names[0]).toMatch(/^finland-node-01 — Данные не поступают, Открыт /)
    expect(names[2]).toMatch(/^finland-node-01 — Данные не поступают, Закрыт /)
    expect([...render(sameResource, 'en').matchAll(/aria-label="(finland-node-01 — [^"]+)"/g)][1][1])
      .toMatch(/^finland-node-01 — Threshold exceeded, Opened /)
  })

  it('places each row in its environment and source connections, from the row itself', () => {
    const shared = incident({ id: 'shared', sourceConnections: [
      { id: 'a', name: 'Finnish Node', connectorType: 'SSH', active: true },
      { id: 'b', name: 'Prometheus', connectorType: 'PROMETHEUS', active: true }] })
    const html = render([shared], 'en')
    expect(html).toContain('<span class="incident-context">Production · Finnish Node +1</span>')
  })

  it('names the rule instead of the resource in the list of one resource', () => {
    const html = renderToStaticMarkup(<I18nProvider initialLocale="en"><MemoryRouter>
      <IncidentList organizationId="org" incidents={[open]} now={now}
        options={{ showResource: false, showContext: false, linkQuery: '?fromResource=node&fromEnvironment=env' }} />
    </MemoryRouter></I18nProvider>)
    expect(html).toContain('href="/organizations/org/incidents/open?fromResource=node&amp;fromEnvironment=env"')
    expect(html).toContain('>CPU usage &gt; 85% for 5m</a>')
    expect(html).not.toContain('incident-context')
    expect(html).not.toContain('finland-node-01')
  })
})
