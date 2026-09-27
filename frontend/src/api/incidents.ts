import { useInfiniteQuery, useQuery } from '@tanstack/react-query'

import { requestJson } from './httpClient'
import type { IncidentListItemResponse, KnownIncidentStatus } from '../types/incident'

/** One page of the organization's incident list; the whole history is never read at once. */
export const IncidentPageSize = 50

/** Where a page continues: the exact row the previous page ended on. */
export type IncidentCursor = Pick<IncidentListItemResponse, 'openedAt' | 'id'>

export function getIncidents(
  organizationId: string,
  status: KnownIncidentStatus | undefined,
  limit = IncidentPageSize,
  before?: IncidentCursor,
): Promise<IncidentListItemResponse[]> {
  const query = new URLSearchParams({ limit: String(limit) })
  if (status !== undefined) query.set('status', status)
  if (before) {
    // Both halves together, so rows opened at the same instant are neither skipped nor repeated.
    query.set('beforeOpenedAt', before.openedAt)
    query.set('beforeId', before.id)
  }
  return requestJson<IncidentListItemResponse[]>(
    `/api/v1/organizations/${encodeURIComponent(organizationId)}/incidents?${query.toString()}`,
  )
}

/** The page after a full one starts after its last row; a short page is the last one. */
export function nextIncidentCursor(page: readonly IncidentListItemResponse[], size: number): IncidentCursor | undefined {
  return page.length < size ? undefined : page[page.length - 1]
}

/**
 * The organization's incidents a page at a time, newest first. Each filter is its own query, so
 * changing the filter starts from its first page instead of continuing another filter's cursor.
 */
export function useIncidentPages(organizationId: string, status?: KnownIncidentStatus) {
  return useInfiniteQuery({
    queryKey: ['incidents', organizationId, status ?? 'ALL'],
    queryFn: ({ pageParam }) => getIncidents(organizationId, status, IncidentPageSize, pageParam),
    initialPageParam: undefined as IncidentCursor | undefined,
    getNextPageParam: lastPage => nextIncidentCursor(lastPage, IncidentPageSize),
  })
}

export function getIncident(
  organizationId: string,
  incidentId: string,
): Promise<IncidentListItemResponse> {
  return requestJson<IncidentListItemResponse>(
    `/api/v1/organizations/${encodeURIComponent(organizationId)}/incidents/${encodeURIComponent(incidentId)}`,
  )
}

export function useIncident(
  organizationId: string | undefined,
  incidentId: string | undefined,
) {
  return useQuery({
    queryKey: ['incident', organizationId, incidentId],
    queryFn: () => getIncident(requireId(organizationId), requireId(incidentId)),
    enabled: Boolean(organizationId && incidentId),
  })
}

function requireId(value: string | undefined): string {
  if (value === undefined || value.length === 0) {
    throw new Error('Missing route identifier')
  }

  return value
}
