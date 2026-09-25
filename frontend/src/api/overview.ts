import { useQuery, type QueryClient } from '@tanstack/react-query'

import { requestJson } from './httpClient'
import type { OperationsOverviewResponse } from '../types/overview'

/** The overview follows the infrastructure by polling; nothing is pushed to the browser. */
export const OverviewRefreshIntervalMs = 30_000

export interface OverviewScope {
  projectId?: string | null
  environmentId?: string | null
}

/** Every overview of an organization, whatever its scope: the prefix mutations invalidate. */
export function overviewQueryKey(organizationId: string) {
  return ['overview', organizationId] as const
}

/** An environment is only meaningful inside its project, so it is sent together with it. */
export function overviewSearch(scope: OverviewScope): string {
  const query = new URLSearchParams()
  if (scope.projectId) {
    query.set('projectId', scope.projectId)
    if (scope.environmentId) query.set('environmentId', scope.environmentId)
  }
  const encoded = query.toString()
  return encoded ? `?${encoded}` : ''
}

export function getOperationsOverview(
  organizationId: string,
  scope: OverviewScope,
): Promise<OperationsOverviewResponse> {
  return requestJson<OperationsOverviewResponse>(
    `/api/v1/organizations/${encodeURIComponent(organizationId)}/overview${overviewSearch(scope)}`,
  )
}

export function useOperationsOverview(organizationId: string, scope: OverviewScope) {
  const projectId = scope.projectId ?? null
  const environmentId = projectId ? scope.environmentId ?? null : null
  return useQuery({
    queryKey: [...overviewQueryKey(organizationId), projectId, environmentId],
    queryFn: () => getOperationsOverview(organizationId, { projectId, environmentId }),
    refetchInterval: OverviewRefreshIntervalMs,
  })
}

/**
 * Refreshes the overview after a change it summarizes.
 *
 * Only a refetch: the overview never assumes what a sync or an operation did to the
 * infrastructure until the server has recorded it.
 */
export function invalidateOverview(queryClient: QueryClient, organizationId: string): Promise<void> {
  return queryClient.invalidateQueries({ queryKey: overviewQueryKey(organizationId) })
}
