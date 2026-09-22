import { useQuery } from '@tanstack/react-query'

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
