import { describe, expect, it } from 'vitest'
import type { HistoryEventResponse } from '../../types/historyEvent'
import { getOverviewActivityEntries } from './overviewActivityPresentation'

const event: HistoryEventResponse = { id: 'a', eventType: 'RESOURCE_DISCOVERED', occurredAt: '2026-10-01T10:00:00Z',
  source: 'SYSTEM', actor: null, incident: null, operation: null,
  resource: { id: 'r', name: 'server', resourceTypeCode: 'NODE', environmentId: 'e' },
  connection: { id: 'c', name: 'Connection' }, sync: { id: 's', status: 'COMPLETED', errorCode: null } }

describe('overview discovery grouping', () => {
  it('groups adjacent discovery facts with explicit shared sync, keeping the bounded facts', () => {
    const gone = { ...event, id: 'b', eventType: 'RESOURCE_DEACTIVATED' as const }
    const entries = getOverviewActivityEntries([event, gone])
    expect(entries).toHaveLength(1)
    expect(entries[0].events).toEqual([event, gone])
    expect(event.id).toBe('a')
  })

  it('does not infer a shared sync from a connection or matching timestamps', () => {
    expect(getOverviewActivityEntries([{ ...event, sync: null }, { ...event, id: 'b', sync: null }])).toHaveLength(2)
    expect(getOverviewActivityEntries([event, { ...event, id: 'b', sync: { ...event.sync!, id: 'different' } }])).toHaveLength(2)
    expect(getOverviewActivityEntries([event, { ...event, id: 'b', connection: { id: 'different', name: 'Other' } }])).toHaveLength(2)
  })

  it('preserves chronology and keeps operational events separate', () => {
    const operation = { ...event, id: 'operation', eventType: 'OPERATION_SUCCEEDED' as const }
    expect(getOverviewActivityEntries([event, operation, { ...event, id: 'b' }]).map(entry => entry.event.id)).toEqual(['a', 'operation', 'b'])
  })
})
