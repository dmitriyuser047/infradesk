import { useQuery } from '@tanstack/react-query'

import { requestJson } from './httpClient'
import type { IncidentResponse, KnownIncidentStatus } from '../types/incident'

export function getIncidents(
  organizationId: string,
  status?: KnownIncidentStatus,
): Promise<IncidentResponse[]> {
  const query = new URLSearchParams()
  if (status !== undefined) {
    query.set('status', status)
  }

  const suffix = query.toString() === '' ? '' : `?${query.toString()}`
  return requestJson<IncidentResponse[]>(
    `/api/v1/organizations/${encodeURIComponent(organizationId)}/incidents${suffix}`,
  )
}

export function getIncident(
  organizationId: string,
  incidentId: string,
): Promise<IncidentResponse> {
  return requestJson<IncidentResponse>(
    `/api/v1/organizations/${encodeURIComponent(organizationId)}/incidents/${encodeURIComponent(incidentId)}`,
  )
}

export function useIncidents(
  organizationId: string,
  status?: KnownIncidentStatus,
) {
  return useQuery({
    queryKey: ['incidents', organizationId, status ?? 'ALL'],
    queryFn: () => getIncidents(organizationId, status),
  })
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
