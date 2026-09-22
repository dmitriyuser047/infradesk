import { useQuery } from '@tanstack/react-query'

import { requestJson } from './httpClient'
import type { ConnectionResponse } from '../types/connection'

export function getConnections(organizationId: string): Promise<ConnectionResponse[]> {
  return requestJson<ConnectionResponse[]>(
    `/api/v1/organizations/${encodeURIComponent(organizationId)}/connections`,
  )
}

export function getConnection(
  organizationId: string,
  connectionId: string,
): Promise<ConnectionResponse> {
  return requestJson<ConnectionResponse>(
    `/api/v1/organizations/${encodeURIComponent(organizationId)}/connections/${encodeURIComponent(connectionId)}`,
  )
}

export function useConnections(organizationId: string | undefined) {
  return useQuery({
    queryKey: ['connections', organizationId],
    queryFn: () => getConnections(requireId(organizationId)),
    enabled: Boolean(organizationId),
  })
}

export function useConnection(
  organizationId: string | undefined,
  connectionId: string | undefined,
) {
  return useQuery({
    queryKey: ['connection', organizationId, connectionId],
    queryFn: () => getConnection(requireId(organizationId), requireId(connectionId)),
    enabled: Boolean(organizationId && connectionId),
  })
}

function requireId(value: string | undefined): string {
  if (value === undefined || value.length === 0) {
    throw new Error('Missing route identifier')
  }

  return value
}
