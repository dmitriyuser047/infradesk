import { useInfiniteQuery } from '@tanstack/react-query'

import { requestJson } from './httpClient'
import type { HistoryEventResponse } from '../types/historyEvent'

export const HistoryPageSize = 20

function page(path: string, cursor?: HistoryEventResponse): Promise<HistoryEventResponse[]> {
  const query = new URLSearchParams({ limit: String(HistoryPageSize) })
  if (cursor !== undefined) {
    query.set('beforeOccurredAt', cursor.occurredAt)
    query.set('beforeId', cursor.id)
  }

  return requestJson<HistoryEventResponse[]>(`${path}?${query.toString()}`)
}

/** Keyset pagination: the next page continues after the last row of the previous one. */
export function useResourceHistory(organizationId: string, resourceId: string) {
  const path = `/api/v1/organizations/${encodeURIComponent(organizationId)}` +
    `/resources/${encodeURIComponent(resourceId)}/history-events`

  return useInfiniteQuery({
    queryKey: ['resource-history', organizationId, resourceId],
    queryFn: ({ pageParam }) => page(path, pageParam),
    initialPageParam: undefined as HistoryEventResponse | undefined,
    getNextPageParam: (lastPage: HistoryEventResponse[]) =>
      lastPage.length < HistoryPageSize ? undefined : lastPage[lastPage.length - 1],
  })
}
