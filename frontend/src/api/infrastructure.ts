import { useInfiniteQuery, useQuery, type QueryClient } from '@tanstack/react-query'

import { requestJson } from './httpClient'
import { nextIncidentCursor, type IncidentCursor } from './incidents'
import type { IncidentListItemResponse, KnownIncidentStatus } from '../types/incident'
import type {
  ConnectionInfrastructureCounts,
  ConnectionInfrastructureSummary,
  LocatedResourceResponse,
  ResourceContextResponse,
  ResourceSourcesResponse,
} from '../types/infrastructure'

/**
 * Where connections, resources and incidents meet. Each read answers what one page asks about its
 * neighbourhood in a single request; none is repeated per row.
 */

const organizationPath = (organizationId: string) => `/api/v1/organizations/${encodeURIComponent(organizationId)}`

/**
 * Both open and resolved incidents are paged: an open list that stops at a fixed ceiling would hide
 * active problems while looking complete.
 */
export const OpenIncidentPageSize = 50
export const IncidentHistoryPageSize = 20

export function useConnectionInfrastructureSummary(organizationId: string, connectionId: string) {
  return useQuery({
    queryKey: ['connection-summary', organizationId, connectionId],
    queryFn: () => requestJson<ConnectionInfrastructureSummary>(
      `${organizationPath(organizationId)}/connections/${encodeURIComponent(connectionId)}/infrastructure-summary`),
  })
}

/** The full resource list of a connection: asked for only once its tab is open. */
export function useConnectionResources(organizationId: string, connectionId: string, enabled: boolean) {
  return useQuery({
    queryKey: ['connection-resources', organizationId, connectionId],
    queryFn: () => requestJson<LocatedResourceResponse[]>(
      `${organizationPath(organizationId)}/connections/${encodeURIComponent(connectionId)}/resources`),
    enabled,
  })
}

export function useConnectionInfrastructureCounts(organizationId: string) {
  return useQuery({
    queryKey: ['connection-infrastructure', organizationId],
    queryFn: () => requestJson<ConnectionInfrastructureCounts[]>(`${organizationPath(organizationId)}/connection-infrastructure`),
  })
}

export function useResourceContext(organizationId: string, resourceId: string) {
  return useQuery({
    queryKey: ['resource-context', organizationId, resourceId],
    queryFn: () => requestJson<ResourceContextResponse>(
      `${organizationPath(organizationId)}/resources/${encodeURIComponent(resourceId)}/context`),
  })
}

export function useEnvironmentResourceSources(organizationId: string, environmentId: string, enabled = true) {
  return useQuery({
    queryKey: ['environment-resource-sources', organizationId, environmentId],
    queryFn: () => requestJson<ResourceSourcesResponse[]>(
      `${organizationPath(organizationId)}/environments/${encodeURIComponent(environmentId)}/resource-sources`),
    enabled,
  })
}

/** Whose incidents a list shows: the resources a connection discovered, or one resource. */
export type IncidentScope = { kind: 'connection'; id: string } | { kind: 'resource'; id: string }

function scopePath(organizationId: string, scope: IncidentScope): string {
  const collection = scope.kind === 'connection' ? 'connections' : 'resources'
  return `${organizationPath(organizationId)}/${collection}/${encodeURIComponent(scope.id)}/incidents`
}

export function getScopedIncidents(
  organizationId: string,
  scope: IncidentScope,
  status: KnownIncidentStatus,
  limit: number,
  before?: IncidentCursor,
): Promise<IncidentListItemResponse[]> {
  const query = new URLSearchParams({ status, limit: String(limit) })
  if (before) {
    // Both halves together: the page continues after one exact row.
    query.set('beforeOpenedAt', before.openedAt)
    query.set('beforeId', before.id)
  }
  return requestJson<IncidentListItemResponse[]>(`${scopePath(organizationId, scope)}?${query.toString()}`)
}

/** One status of the scope's incidents, a page at a time, newest first. */
export function useScopedIncidentPages(organizationId: string, scope: IncidentScope, status: KnownIncidentStatus, enabled: boolean) {
  const size = status === 'OPEN' ? OpenIncidentPageSize : IncidentHistoryPageSize
  return useInfiniteQuery({
    queryKey: [`${scope.kind}-incidents`, organizationId, scope.id, status],
    queryFn: ({ pageParam }) => getScopedIncidents(organizationId, scope, status, size, pageParam),
    initialPageParam: undefined as IncidentCursor | undefined,
    getNextPageParam: lastPage => nextIncidentCursor(lastPage, size),
    enabled,
  })
}

/**
 * What a synchronization can change about a connection's neighbourhood: which resources it
 * discovered and how many there are. Incidents follow from monitoring, which runs after the sync
 * on its own schedule, so they are refreshed by their own reads rather than here.
 */
export function invalidateConnectionInfrastructure(queryClient: QueryClient, organizationId: string, connectionId: string) {
  void queryClient.invalidateQueries({ queryKey: ['connection-summary', organizationId, connectionId] })
  void queryClient.invalidateQueries({ queryKey: ['connection-resources', organizationId, connectionId] })
  void queryClient.invalidateQueries({ queryKey: ['connection-infrastructure', organizationId] })
  void queryClient.invalidateQueries({ queryKey: ['environment-resource-sources', organizationId] })
}
