import { useQuery, useQueryClient } from '@tanstack/react-query'

import { requestJson } from './httpClient'
import type { ResourceResponse } from '../types/resource'

export function useEnvironmentResources(
  organizationId: string | undefined,
  environmentId: string | undefined,
) {
  return useQuery({
    queryKey: ['environment-resources', organizationId, environmentId],
    queryFn: () => getEnvironmentResources(requireId(organizationId), requireId(environmentId)),
    enabled: Boolean(organizationId && environmentId),
  })
}

/**
 * A resource the browser already holds — its own page's entry, or its row in the environment
 * list — or undefined. It reads the cache only and never sends a request.
 */
export function useCachedResource(organizationId: string, environmentId: string, resourceId: string | null): ResourceResponse | undefined {
  const queryClient = useQueryClient()
  if (resourceId === null) return undefined
  return queryClient.getQueryData<ResourceResponse>(['resource', organizationId, resourceId])
    ?? queryClient.getQueryData<ResourceResponse[]>(['environment-resources', organizationId, environmentId])
      ?.find(resource => resource.id === resourceId)
}

export function getEnvironmentResources(
  organizationId: string,
  environmentId: string,
): Promise<ResourceResponse[]> {
  return requestJson<ResourceResponse[]>(
    `/api/v1/organizations/${encodeURIComponent(organizationId)}/environments/${encodeURIComponent(environmentId)}/resources`,
  )
}

export function useResource(
  organizationId: string | undefined,
  resourceId: string | undefined,
) {
  return useQuery({
    queryKey: ['resource', organizationId, resourceId],
    queryFn: () => getResource(requireId(organizationId), requireId(resourceId)),
    enabled: Boolean(organizationId && resourceId),
  })
}

export function getResource(
  organizationId: string,
  resourceId: string,
): Promise<ResourceResponse> {
  return requestJson<ResourceResponse>(
    `/api/v1/organizations/${encodeURIComponent(organizationId)}/resources/${encodeURIComponent(resourceId)}`,
  )
}

function requireId(value: string | undefined): string {
  if (value === undefined || value.length === 0) {
    throw new Error('Missing route identifier')
  }

  return value
}
