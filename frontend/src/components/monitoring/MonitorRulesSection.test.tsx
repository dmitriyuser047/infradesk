import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'

import { monitorRulesQueryKey } from '../../api/monitorRules'
import type { MonitorRuleResponse } from '../../types/monitorRule'
import { MonitorRulesSection } from './MonitorRulesSection'

const rule: MonitorRuleResponse = {
  id: 'rule',
  resourceId: 'resource',
  metricCode: 'CPU_USAGE_PERCENT',
  operator: 'GREATER_THAN',
  threshold: 90,
  forSeconds: 300,
  noDataSeconds: 900,
  enabled: true,
  status: 'OK',
  createdAt: '2026-09-24T10:00:00Z',
  updatedAt: '2026-09-24T10:00:00Z',
}

function render(role: 'OWNER' | 'MEMBER'): string {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  client.setQueryData(['my-organizations'], [
    { id: 'org', code: 'ORG', name: 'Organization', role },
  ])
  client.setQueryData(monitorRulesQueryKey('org', 'resource'), [rule])

  return renderToStaticMarkup(<QueryClientProvider client={client}>
    <MonitorRulesSection organizationId="org" resourceId="resource" />
  </QueryClientProvider>)
}

describe('monitor rules authorization', () => {
  it('shows management actions to an owner', () => {
    const html = render('OWNER')

    expect(html).toContain('+ Add rule')
    expect(html).toContain('Edit rule')
  })

  it('keeps the list read-only for a member', () => {
    const html = render('MEMBER')

    expect(html).toContain('CPU usage')
    expect(html).not.toContain('+ Add rule')
    expect(html).not.toContain('Edit rule')
    expect(html).not.toContain('<th>Action</th>')
  })
})
