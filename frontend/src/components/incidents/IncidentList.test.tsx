import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'

import type { IncidentListItemResponse } from '../../types/incident'
import { IncidentList } from './IncidentList'

function incident(overrides: Partial<IncidentListItemResponse>): IncidentListItemResponse {
  return {
    id: 'incident', monitorRuleId: 'rule', resourceId: 'node', status: 'OPEN', reason: 'THRESHOLD',
    startedAt: '2026-09-25T10:00:00Z', openedAt: '2026-09-25T10:00:00Z', resolvedAt: null,
    createdAt: '', updatedAt: '', resource: { id: 'node', name: 'finland-node-01', resourceTypeCode: 'NODE' },
    ...overrides,
  }
}

function render(incidents: IncidentListItemResponse[]): string {
  // An empty cache: everything a row shows comes from the list response itself.
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return renderToStaticMarkup(<QueryClientProvider client={client}><MemoryRouter>
    <IncidentList organizationId="org" incidents={incidents} />
  </MemoryRouter></QueryClientProvider>)
}

describe('incident list', () => {
  it('tells NO_DATA and THRESHOLD apart by wording and icon, and names the resource', () => {
    const html = render([
      incident({ id: 'threshold', reason: 'THRESHOLD' }),
      incident({ id: 'no-data', reason: 'NO_DATA', openedAt: '2026-09-25T09:00:00Z',
        resourceId: 'web', resource: { id: 'web', name: 'nginx-web', resourceTypeCode: 'CONTAINER' } }),
    ])

    expect(html).toContain('Превышен порог')
    expect(html).toContain('lucide-trending-up')
    expect(html).toContain('Нет данных')
    expect(html).toContain('lucide-eye-off')
    expect(html).toContain('finland-node-01')
    expect(html).toContain('Сервер')
    expect(html).toContain('nginx-web')
    expect(html).toContain('Контейнер')
  })

  it('lists open incidents first and shows resolved ones quieter', () => {
    const html = render([
      incident({ id: 'resolved', status: 'RESOLVED', resolvedAt: '2026-09-25T11:00:00Z', openedAt: '2026-09-25T10:30:00Z' }),
      incident({ id: 'open', openedAt: '2026-09-25T08:00:00Z' }),
    ])

    expect(html.indexOf('/incidents/open')).toBeLessThan(html.indexOf('/incidents/resolved'))
    expect(html).toMatch(/<tr class="clickable-row row-quiet">.*\/incidents\/resolved/s)
    expect(html).toContain('Закрыт')
    expect(html).toContain('Продолжается')
  })
})
