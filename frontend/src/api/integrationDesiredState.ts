import { useMutation, useQueryClient } from '@tanstack/react-query'
import { requestJson, requestVoid } from './httpClient'
import type { DesiredNodeState, DesiredStateView, IntegrationManagementMode, IntegrationResponse } from '../types/integration'

const base = (org: string, integration: string) =>
  `/api/v1/organizations/${encodeURIComponent(org)}/integrations/${encodeURIComponent(integration)}`

/** Everything a change of intent can alter on screen: the integration, its inventory, overview and actions. */
function useRefresh(org: string, integration: string) {
  const client = useQueryClient()
  return () => Promise.all([
    client.invalidateQueries({ queryKey: ['integration-inventory', org, integration] }),
    client.invalidateQueries({ queryKey: ['integration', org, integration] }),
    client.invalidateQueries({ queryKey: ['integrations', org] }),
    client.invalidateQueries({ queryKey: ['integration-actions', org, integration] }),
    client.invalidateQueries({ queryKey: ['resource-integration-bindings', org] }),
  ])
}

export function useSetManagementMode(org: string, integration: string) {
  const refresh = useRefresh(org, integration)
  return useMutation({ mutationFn: (mode: IntegrationManagementMode) => requestJson<IntegrationResponse>(
    `${base(org, integration)}/management-mode`, { method: 'PUT', body: JSON.stringify({ mode }) }),
  onSettled: refresh })
}

export function useSetDesiredState(org: string, integration: string) {
  const refresh = useRefresh(org, integration)
  return useMutation({ mutationFn: ({ objectId, state }: { objectId: string; state: DesiredNodeState }) =>
    requestJson<DesiredStateView>(`${base(org, integration)}/inventory/objects/${encodeURIComponent(objectId)}/desired-state`,
      { method: 'PUT', body: JSON.stringify({ state }) }),
  onSettled: refresh })
}

export function useRemoveDesiredState(org: string, integration: string) {
  const refresh = useRefresh(org, integration)
  return useMutation({ mutationFn: (objectId: string) => requestVoid(
    `${base(org, integration)}/inventory/objects/${encodeURIComponent(objectId)}/desired-state`, { method: 'DELETE' }),
  onSettled: refresh })
}
