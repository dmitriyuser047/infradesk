import { describe, expect, it } from 'vitest'

import type { AttentionItemResponse, OverviewSummaryResponse } from '../../types/overview'
import { getAttentionPresentation, getSummaryCards, isEmptyInfrastructure } from './overviewPresentation'

const resource = { id: 'resource', name: 'api-1', resourceTypeCode: 'CONTAINER', environmentId: 'env' }

function item(overrides: Partial<AttentionItemResponse>): AttentionItemResponse {
  return {
    kind: 'OPERATION_FAILED', priority: 5, id: 'item', occurredAt: '2026-09-25T10:00:00Z',
    resource, connection: null, incident: null, operation: null, sync: null, ...overrides,
  }
}

const links = { infrastructure: null, incidents: '/organizations/org/incidents', connections: '/organizations/org/connections' }

function summary(overrides: Partial<OverviewSummaryResponse> = {}): OverviewSummaryResponse {
  return {
    nodes: { total: 0, online: 0, offline: 0 },
    containers: { total: 0, running: 0, stopped: 0 },
    connections: { total: 0, healthy: 0, failing: 0, neverSynced: 0 },
    incidents: { open: 0, threshold: 0, noData: 0 },
    operations: { failed: 0, unknown: 0 },
    ...overrides,
  }
}

describe('attention presentation', () => {
  it('keeps an unknown operation result apart from a failure', () => {
    const operation = { operationCode: 'CONTAINER_STOP', errorCode: 'X', errorMessage: 'Docker container stop failed' }
    const unknown = getAttentionPresentation('org', item({ kind: 'OPERATION_UNKNOWN', priority: 1, operation }))
    const failed = getAttentionPresentation('org', item({ kind: 'OPERATION_FAILED', operation }))

    expect(unknown.title).toBe('Stop: result unknown')
    expect(unknown.status).toBe('Result unknown')
    expect(unknown.tone).toBe('warning')
    expect(unknown.detail).not.toContain('failed')
    expect(failed.title).toBe('Stop: operation failed')
    expect(failed.status).toBe('Failed')
    expect(failed.detail).toBe('Docker container stop failed')
    expect(failed.to).toBe('/organizations/org/environments/env/resources/resource')
  })

  it('names the incident reason and leads to the incident', () => {
    const noData = getAttentionPresentation('org', item({
      kind: 'INCIDENT', priority: 2, id: 'incident',
      incident: { reason: 'NO_DATA', metricCode: 'CPU_USAGE_PERCENT' },
    }))
    const threshold = getAttentionPresentation('org', item({
      kind: 'INCIDENT', priority: 2, incident: { reason: 'THRESHOLD', metricCode: 'MEMORY_USAGE_PERCENT' },
    }))

    expect(noData.title).toBe('No metrics received')
    expect(noData.detail).toBe('CPU usage')
    expect(noData.status).toBe('Incident open')
    expect(noData.to).toBe('/organizations/org/incidents/incident')
    expect(threshold.title).toBe('Metric threshold exceeded')
  })

  it('words a failed synchronization by its safe code and leads to that session', () => {
    const hostKey = getAttentionPresentation('org', item({
      kind: 'SYNC_FAILED', priority: 4, id: 'session', resource: null,
      connection: { id: 'connection', name: 'finland_node' },
      sync: { errorCode: 'SSH_HOST_KEY_MISMATCH', errorMessage: 'SSH host key does not match' },
    }))
    const other = getAttentionPresentation('org', item({
      kind: 'SYNC_FAILED', resource: null, connection: { id: 'c', name: 'c' },
      sync: { errorCode: 'SOMETHING_NEW', errorMessage: null },
    }))

    expect(hostKey.title).toBe('Host identity changed')
    expect(hostKey.subject).toBe('finland_node')
    expect(hostKey.detail).toBe('SSH host key does not match')
    expect(hostKey.to).toBe('/organizations/org/connections/connection/sync-sessions/session')
    expect(other.title).toBe('Synchronization failed')
    expect(other.detail).toBe('Synchronization failed')
  })

  it('leads an offline node to its resource page', () => {
    const offline = getAttentionPresentation('org', item({ kind: 'NODE_OFFLINE', priority: 3,
      resource: { ...resource, name: 'node-1', resourceTypeCode: 'NODE' } }))

    expect(offline.title).toBe('Node offline')
    expect(offline.subject).toBe('node-1')
    expect(offline.to).toBe('/organizations/org/environments/env/resources/resource')
  })
})

describe('fleet summary cards', () => {
  it('reads well with nothing, one and hundreds', () => {
    const empty = getSummaryCards(summary(), links, 24)
    const busy = getSummaryCards(summary({
      nodes: { total: 1, online: 1, offline: 0 },
      containers: { total: 240, running: 212, stopped: 28 },
      incidents: { open: 150, threshold: 100, noData: 50 },
      connections: { total: 5, healthy: 3, failing: 1, neverSynced: 1 },
      operations: { failed: 1, unknown: 1 },
    }), links, 24)

    expect(empty.map(card => card.value)).toEqual(['0', '0', '0', '0', '0'])
    expect(empty.find(card => card.id === 'incidents')?.detail).toBe('None open')
    expect(empty.find(card => card.id === 'connections')?.detail).toBe('None configured')
    expect(busy.find(card => card.id === 'nodes')?.detail).toBe('1 online · 0 offline')
    expect(busy.find(card => card.id === 'containers')?.value).toBe('240')
    expect(busy.find(card => card.id === 'incidents')?.detail).toBe('100 threshold · 50 no data')
    expect(busy.find(card => card.id === 'connections')?.detail).toBe('1 failing')
    expect(busy.find(card => card.id === 'operations')?.label).toBe('Operations · 24h')
    expect(busy.find(card => card.id === 'operations')?.detail).toBe('1 unknown · 1 failed')
    expect(busy.find(card => card.id === 'operations')?.tone).toBe('warning')
  })

  it('links cards only to pages that exist for the current scope', () => {
    const cards = getSummaryCards(summary(), links, 24)

    expect(cards.find(card => card.id === 'nodes')?.to).toBeNull()
    expect(cards.find(card => card.id === 'incidents')?.to).toBe('/organizations/org/incidents')
    expect(cards.find(card => card.id === 'connections')?.to).toBe('/organizations/org/connections')
    expect(cards.find(card => card.id === 'operations')?.to).toBeNull()
  })

  it('treats a scope without inventory or connections as empty, not as an error', () => {
    expect(isEmptyInfrastructure(summary())).toBe(true)
    expect(isEmptyInfrastructure(summary({ connections: { total: 1, healthy: 0, failing: 0, neverSynced: 1 } }))).toBe(false)
  })
})
