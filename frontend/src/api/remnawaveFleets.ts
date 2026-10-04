import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { requestJson } from './httpClient'
import type { CreateFleetRequest, Fleet, FleetCandidate, FleetDesiredRequest, FleetDetail, FleetRevision,
  FleetRevisionImpact } from '../types/remnawaveFleet'

const path = (org: string, integration: string) =>
  `/api/v1/organizations/${encodeURIComponent(org)}/integrations/${encodeURIComponent(integration)}/remnawave-fleets`

export function useFleets(org: string, integration: string, enabled: boolean) {
  return useQuery({ queryKey: ['remnawaveFleets', org, integration], enabled,
    queryFn: () => requestJson<{ items: Fleet[] }>(path(org, integration)) })
}

export function useFleet(org: string, integration: string, id: string | null) {
  return useQuery({ queryKey: ['remnawaveFleet', org, integration, id], enabled: id !== null,
    queryFn: () => requestJson<FleetDetail>(`${path(org, integration)}/${encodeURIComponent(id!)}`) })
}

export function useFleetCandidates(org: string, integration: string, enabled: boolean) {
  return useQuery({ queryKey: ['remnawaveFleetCandidates', org, integration], enabled,
    queryFn: () => requestJson<{ items: FleetCandidate[] }>(`${path(org, integration)}/candidates`) })
}

/** Invalidates the list and the open fleet, so every change shows its new desired state at once. */
function useFleetRefresh(org: string, integration: string) {
  const client = useQueryClient()
  return () => {
    void client.invalidateQueries({ queryKey: ['remnawaveFleets', org, integration] })
    void client.invalidateQueries({ queryKey: ['remnawaveFleet', org, integration] })
    void client.invalidateQueries({ queryKey: ['remnawaveFleetCandidates', org, integration] })
  }
}

export function useCreateFleet(org: string, integration: string) {
  const refresh = useFleetRefresh(org, integration)
  return useMutation({ onSuccess: refresh, mutationFn: (body: CreateFleetRequest) =>
    requestJson<Fleet>(path(org, integration), { method: 'POST', body: JSON.stringify(body) }) })
}

export function useCreateFleetRevision(org: string, integration: string, fleet: string) {
  const refresh = useFleetRefresh(org, integration)
  return useMutation({ onSuccess: refresh,
    mutationFn: (body: { desiredConfiguration: FleetDesiredRequest; expectedVersion: number }) =>
      requestJson<FleetRevision>(`${path(org, integration)}/${encodeURIComponent(fleet)}/revisions`,
        { method: 'POST', body: JSON.stringify(body) }) })
}

/** A read-only estimate: it moves no pointer and starts nothing. */
export function usePreviewFleetRevision(org: string, integration: string, fleet: string) {
  return useMutation({ mutationFn: (revision: string) =>
    requestJson<FleetRevisionImpact>(
      `${path(org, integration)}/${encodeURIComponent(fleet)}/revisions/${encodeURIComponent(revision)}/preview`,
      { method: 'POST', body: '{}' }) })
}

/** Moves the desired pointer. It applies nothing to any node. */
export function usePromoteFleetRevision(org: string, integration: string, fleet: string) {
  const refresh = useFleetRefresh(org, integration)
  return useMutation({ onSuccess: refresh,
    mutationFn: (body: { revisionId: string; expectedVersion: number }) =>
      requestJson<Fleet>(
        `${path(org, integration)}/${encodeURIComponent(fleet)}/revisions/${encodeURIComponent(body.revisionId)}/promote`,
        { method: 'POST', body: JSON.stringify({ expectedVersion: body.expectedVersion }) }) })
}

export function useAddFleetMember(org: string, integration: string, fleet: string) {
  const refresh = useFleetRefresh(org, integration)
  return useMutation({ onSuccess: refresh,
    mutationFn: (body: { inventoryNodeId: string; expectedVersion: number }) =>
      requestJson<{ membershipId: string }>(`${path(org, integration)}/${encodeURIComponent(fleet)}/members`,
        { method: 'POST', body: JSON.stringify(body) }) })
}

export function useRemoveFleetMember(org: string, integration: string, fleet: string) {
  const refresh = useFleetRefresh(org, integration)
  return useMutation({ onSuccess: refresh,
    mutationFn: (body: { membershipId: string; expectedVersion: number }) =>
      requestJson<void>(
        `${path(org, integration)}/${encodeURIComponent(fleet)}/members/${encodeURIComponent(body.membershipId)}` +
          `?expectedVersion=${body.expectedVersion}`, { method: 'DELETE' }) })
}

/** An observation action: it asks for a synchronization and marks members due. It applies nothing. */
export function useRefreshFleet(org: string, integration: string, fleet: string) {
  const refresh = useFleetRefresh(org, integration)
  return useMutation({ onSuccess: refresh, mutationFn: () =>
    requestJson<{ membersDue: number }>(`${path(org, integration)}/${encodeURIComponent(fleet)}/refresh`,
      { method: 'POST', body: '{}' }) })
}

export function useArchiveFleet(org: string, integration: string, fleet: string) {
  const refresh = useFleetRefresh(org, integration)
  return useMutation({ onSuccess: refresh, mutationFn: (expectedVersion: number) =>
    requestJson<Fleet>(`${path(org, integration)}/${encodeURIComponent(fleet)}/archive`,
      { method: 'POST', body: JSON.stringify({ expectedVersion }) }) })
}
