import { useInfiniteQuery, useQuery, type QueryClient } from '@tanstack/react-query'

import { requestJson } from './httpClient'
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

/** Open incidents are all shown at once; the history is paged. */
export const OpenIncidentLimit = 200
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
  before?: Pick<IncidentListItemResponse, 'openedAt' | 'id'>,
): Promise<IncidentListItemResponse[]> {
  const query = new URLSearchParams({ status, limit: String(limit) })
  if (before) {
    // Both halves together: the page continues after one exact row.
    query.set('beforeOpenedAt', before.openedAt)
    query.set('beforeId', before.id)
  }
  return requestJson<IncidentListItemResponse[]>(`${scopePath(organizationId, scope)}?${query.toString()}`)
}

/** Every open incident of the scope, newest first. */
export function useOpenScopedIncidents(organizationId: string, scope: IncidentScope, enabled: boolean) {
  return useQuery({
    queryKey: [`${scope.kind}-incidents`, organizationId, scope.id, 'OPEN'],
    queryFn: () => getScopedIncidents(organizationId, scope, 'OPEN', OpenIncidentLimit),
    enabled,
  })
}

/** Resolved incidents of the scope, one page at a time, newest first. */
export function useResolvedScopedIncidents(organizationId: string, scope: IncidentScope, enabled: boolean) {
  return useInfiniteQuery({
    queryKey: [`${scope.kind}-incidents`, organizationId, scope.id, 'RESOLVED'],
    queryFn: ({ pageParam }) => getScopedIncidents(organizationId, scope, 'RESOLVED', IncidentHistoryPageSize, pageParam),
    initialPageParam: undefined as Pick<IncidentListItemResponse, 'openedAt' | 'id'> | undefined,
    getNextPageParam: lastPage => lastPage.length < IncidentHistoryPageSize ? undefined : lastPage[lastPage.length - 1],
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
