import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { requestJson } from './httpClient'
import type { IncidentListItemResponse } from '../types/incident'
import type { CreateMaintenanceWindowRequest, MaintenanceWindowListResponse, MaintenanceWindowResponse } from '../types/maintenance'

const windowsPath = (organizationId: string) =>
  `/api/v1/organizations/${encodeURIComponent(organizationId)}/maintenance-windows`

/** Current and upcoming windows first, then the most recent finished ones. */
export function useMaintenanceWindows(organizationId: string) {
  return useQuery({
    queryKey: ['maintenance-windows', organizationId],
    queryFn: () => requestJson<MaintenanceWindowListResponse>(windowsPath(organizationId)),
    // A window starts and ends on its own; the list follows without a reload.
    refetchInterval: 60_000,
  })
}

export function useCreateMaintenanceWindow(organizationId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: CreateMaintenanceWindowRequest) => requestJson<MaintenanceWindowResponse>(windowsPath(organizationId),
      { method: 'POST', body: JSON.stringify(body) }),
    onSuccess: () => { void queryClient.invalidateQueries({ queryKey: ['maintenance-windows', organizationId] }) },
  })
}

export function useCancelMaintenanceWindow(organizationId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (windowId: string) => requestJson<MaintenanceWindowResponse>(
      `${windowsPath(organizationId)}/${encodeURIComponent(windowId)}/cancel`, { method: 'POST' }),
    onSuccess: () => { void queryClient.invalidateQueries({ queryKey: ['maintenance-windows', organizationId] }) },
  })
}

/** Acknowledges an open incident; the answer is the incident as stored, whoever acknowledged first. */
export function useAcknowledgeIncident(organizationId: string, incidentId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => requestJson<IncidentListItemResponse>(
      `/api/v1/organizations/${encodeURIComponent(organizationId)}/incidents/${encodeURIComponent(incidentId)}/acknowledge`,
      { method: 'POST' }),
    onSuccess: incident => {
      queryClient.setQueryData(['incident', organizationId, incidentId], incident)
      void queryClient.invalidateQueries({ queryKey: ['incidents', organizationId] })
    },
  })
}
