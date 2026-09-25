import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'

import { ResourceActivitySection } from './ResourceActivitySection'
import { getHistoryActorLabel, getHistoryEventPresentation } from './historyEventPresentation'
import type { HistoryEventResponse } from '../../types/historyEvent'

function event(overrides: Partial<HistoryEventResponse>): HistoryEventResponse {
  return {
    id: crypto.randomUUID(),
    eventType: 'RESOURCE_DISCOVERED',
    source: 'SYSTEM',
    occurredAt: '2026-09-24T10:00:00Z',
    resource: { id: 'resource', name: 'api-1', resourceTypeCode: 'CONTAINER', environmentId: 'environment' },
    connection: null,
    actor: null,
    incident: null,
    operation: null,
    sync: null,
    ...overrides,
  }
}

const timeline: HistoryEventResponse[] = [
  event({
    eventType: 'OPERATION_FAILED', occurredAt: '2026-09-24T15:41:00Z',
    operation: {
      id: 'execution', operationCode: 'CONTAINER_RESTART', status: 'FAILED',
      errorCode: 'DOCKER_OPERATION_FAILED', errorMessage: 'Docker container restart failed',
    },
  }),
  event({
    eventType: 'OPERATION_REQUESTED', source: 'USER', occurredAt: '2026-09-24T15:40:00Z',
    actor: { id: 'actor', displayName: 'Dmitriy' },
    // The projection joins the execution as it is now, so the request carries the outcome the
    // execution reached a minute later.
    operation: {
      id: 'execution', operationCode: 'CONTAINER_RESTART', status: 'FAILED',
      errorCode: 'DOCKER_OPERATION_FAILED', errorMessage: 'Docker container restart failed',
    },
  }),
  event({
    eventType: 'INCIDENT_RESOLVED', occurredAt: '2026-09-24T15:32:00Z',
    incident: { id: 'incident', status: 'RESOLVED', reason: 'THRESHOLD', monitorRuleId: 'rule' },
  }),
  event({
    eventType: 'INCIDENT_OPENED', occurredAt: '2026-09-24T15:27:00Z',
    incident: { id: 'incident', status: 'OPEN', reason: 'NO_DATA', monitorRuleId: 'rule' },
  }),
  event({ eventType: 'RESOURCE_DISCOVERED', occurredAt: '2026-09-24T15:15:00Z' }),
]

function render(pages: HistoryEventResponse[][]) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  client.setQueryData(['resource-history', 'org', 'resource'], {
    pages, pageParams: pages.map(() => undefined),
  })
  return renderToStaticMarkup(<QueryClientProvider client={client}>
    <ResourceActivitySection organizationId="org" resourceId="resource" />
  </QueryClientProvider>)
}

describe('resource activity', () => {
  it('renders the timeline newest first with safe details only', () => {
    const html = render([timeline])

    expect(html.indexOf('Restart: operation failed')).toBeLessThan(html.indexOf('Restart: operation requested'))
    expect(html.indexOf('Restart: operation requested')).toBeLessThan(html.indexOf('Incident resolved'))
    expect(html.indexOf('Incident opened')).toBeLessThan(html.indexOf('Resource discovered'))
    // The failure is shown once, on the entry that reports the outcome.
    expect(html.match(/Docker container restart failed/g)?.length).toBe(1)
    expect(html).toContain('Metric threshold exceeded')
    expect(html).toContain('No metrics received')
    expect(html).toContain('Dmitriy')
    expect(html).toContain('System')
    // Nothing about how the command reached the host.
    expect(html).not.toContain('docker restart')
    expect(html).not.toContain('stderr')
  })

  it('offers more only while a full page came back', () => {
    const full = Array.from({ length: 20 }, () => event({}))

    expect(render([full])).toContain('Load more')
    expect(render([timeline])).not.toContain('Load more')
  })

  it('shows an empty state when nothing has happened', () => {
    const html = render([[]])

    expect(html).toContain('Nothing has happened yet')
    expect(html).not.toContain('Load more')
  })

  it('never shows a later outcome on the entry that only requested it', () => {
    const requested = timeline[1]

    expect(getHistoryEventPresentation(requested)).toEqual({
      title: 'Restart: operation requested',
      detail: null,
      tone: 'neutral',
    })
    // The failure belongs to the entry that reports it.
    expect(getHistoryEventPresentation(timeline[0]).detail).toBe('Docker container restart failed')
    expect(getHistoryEventPresentation({
      ...requested, eventType: 'OPERATION_SUCCEEDED', source: 'SYSTEM', actor: null,
    }).detail).toBe(null)
  })

  it('maps every event type to a title and a tone', () => {
    const unknown = event({
      eventType: 'OPERATION_UNKNOWN',
      operation: {
        id: 'execution', operationCode: 'CONTAINER_STOP', status: 'UNKNOWN',
        errorCode: 'OPERATION_RESULT_UNKNOWN',
        errorMessage: 'Operation result is unknown because execution was interrupted',
      },
    })
    const syncFailure = event({
      eventType: 'SYNC_FAILED', resource: null,
      connection: { id: 'connection', name: 'prod-ssh' },
      sync: { id: 'session', status: 'FAILED', errorCode: 'SSH_CONNECT_TIMEOUT' },
    })
    const deactivated = event({ eventType: 'RESOURCE_DEACTIVATED' })

    expect(getHistoryEventPresentation(unknown)).toEqual({
      title: 'Stop: operation result unknown',
      detail: 'Operation result is unknown because execution was interrupted',
      tone: 'warning',
    })
    expect(getHistoryEventPresentation(syncFailure)).toEqual({
      title: 'Synchronization failed',
      detail: 'Error code SSH_CONNECT_TIMEOUT',
      tone: 'critical',
    })
    expect(getHistoryEventPresentation(deactivated).tone).toBe('warning')
    expect(getHistoryEventPresentation(timeline[4]).tone).toBe('neutral')
    expect(getHistoryActorLabel(syncFailure)).toBe('System')
    expect(getHistoryActorLabel(timeline[1])).toBe('Dmitriy')
  })
})
