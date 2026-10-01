import type { HistoryEventResponse } from '../../types/historyEvent'

export interface OverviewActivityEntry {
  event: HistoryEventResponse
  events: HistoryEventResponse[]
}

/** Group adjacent facts with an explicit shared session, source, actor and timestamp.
 * Counts describe this bounded feed, never the full sync result or active inventory.
 */
export function getOverviewActivityEntries(events: readonly HistoryEventResponse[]): OverviewActivityEntry[] {
  const entries: OverviewActivityEntry[] = []
  const groupKey = (event: HistoryEventResponse): string | null =>
    (event.eventType === 'RESOURCE_DISCOVERED' || event.eventType === 'RESOURCE_DEACTIVATED') && event.sync && event.connection
      ? JSON.stringify([event.sync.id, event.connection.id, event.occurredAt, event.source, event.actor?.id ?? null]) : null
  for (const event of events) {
    const previous = entries.at(-1)
    const key = groupKey(event)
    if (previous && key !== null && groupKey(previous.event) === key) previous.events.push(event)
    else entries.push({ event, events: [event] })
  }
  return entries
}
