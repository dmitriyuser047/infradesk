import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect } from 'react'
import { createRequestId } from '../app/requestId'
import { requestJson } from './httpClient'
import type { IntegrationActionCode, IntegrationActionExecution } from '../types/integration'

const base = (org: string, integration: string) =>
  `/api/v1/organizations/${encodeURIComponent(org)}/integrations/${encodeURIComponent(integration)}`
const key = (org: string, integration: string) => ['integration-actions', org, integration] as const

export function useIntegrationActions(org: string, integration: string, enabled: boolean) {
  return useQuery({ queryKey: key(org, integration), enabled,
    queryFn: () => requestJson<{ items: IntegrationActionExecution[] }>(`${base(org, integration)}/actions?limit=50`)
      .then(value => value.items), refetchInterval: query => query.state.data?.some(value =>
      value.status === 'QUEUED' || value.status === 'RUNNING') ? 1500 : false })
}

export function useIntegrationAction(org: string, integration: string, id: string | null) {
  const client = useQueryClient()
  const query = useQuery({ queryKey: [...key(org, integration), id], enabled: Boolean(id),
    queryFn: () => requestJson<IntegrationActionExecution>(`${base(org, integration)}/actions/${encodeURIComponent(id!)}`),
    refetchInterval: query => query.state.data?.status === 'QUEUED' || query.state.data?.status === 'RUNNING' ? 1500 : false })
  const deleted = query.data?.action === 'NODE_DELETE' && query.data.status === 'SUCCEEDED'
  useEffect(() => {
    if (deleted) {
      void client.invalidateQueries({ queryKey: ['integration-inventory', org, integration] })
      void client.invalidateQueries({ queryKey: ['integration', org, integration] })
      void client.invalidateQueries({ queryKey: ['integrations', org] })
    }
  }, [client, org, integration, id, deleted])
  return query
}

export function useRequestIntegrationAction(org: string, integration: string) {
  const client = useQueryClient()
  return useMutation({ mutationFn: ({ objectId, action, requestId }: {
    objectId: string; action: IntegrationActionCode; requestId: string
  }) => requestJson<IntegrationActionExecution>(`${base(org, integration)}/inventory/objects/${encodeURIComponent(objectId)}/actions`,
    { method: 'POST', body: JSON.stringify({ requestId, action, ...(action === 'NODE_DELETE' ? { confirmDelete: true } : {}) }) }),
    onSuccess: value => { client.setQueryData([...key(org, integration), value.id], value)
      void client.invalidateQueries({ queryKey: key(org, integration) })
      if (value.action === 'NODE_DELETE') void client.invalidateQueries({ queryKey: ['integration-inventory', org, integration] }) },
    onError: () => { void client.invalidateQueries({ queryKey: key(org, integration) }) },
  })
}

export { createRequestId }
