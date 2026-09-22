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

function requireId(value: string | undefined): string {
  if (value === undefined || value.length === 0) {
    throw new Error('Missing route identifier')
  }

  return value
}
