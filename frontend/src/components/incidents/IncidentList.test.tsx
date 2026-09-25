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

    expect(html).toMatch(/<a class="incident-link"[^>]*>finland-node-01<\/a><span class="incident-type">Сервер<\/span>/)
    expect(html).toMatch(/<a class="incident-link"[^>]*>nginx-web<\/a><span class="incident-type">Контейнер<\/span>/)
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

  it('shows status in words, reads resolved rows quieter, and gives each row one real link', () => {
    const html = render([open, resolved])

    expect(html).toMatch(/<li class="incident-row incident-open"[^>]*><span class="incident-status"><span class="status-indicator status-danger">.*?Открыт</)
    expect(html).toMatch(/<li class="incident-row incident-resolved"[^>]*><span class="incident-status"><span class="status-indicator status-success">.*?Закрыт</)
    expect(html.match(/<a /g)).toHaveLength(2)
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
})
