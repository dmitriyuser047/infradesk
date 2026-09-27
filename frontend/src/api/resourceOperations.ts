import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { requestJson } from './httpClient'
import { invalidateOverview } from './overview'
import type { AvailableResourceOperationsResponse, OperationExecutionResponse, ResourceOperationCode } from '../types/resourceOperation'

const root = (organizationId: string, resourceId: string) =>
  `/api/v1/organizations/${encodeURIComponent(organizationId)}/resources/${encodeURIComponent(resourceId)}`

export function useAvailableResourceOperations(organizationId: string, resourceId: string, enabled = true) {
  return useQuery({
    enabled,
    queryKey: ['resource-operations', organizationId, resourceId],
    queryFn: () => requestJson<AvailableResourceOperationsResponse>(`${root(organizationId, resourceId)}/operations`),
  })
}

export function useResourceOperationExecutions(organizationId: string, resourceId: string, enabled = true) {
  return useQuery({
    enabled,
    queryKey: ['resource-operation-executions', organizationId, resourceId],
    queryFn: () => requestJson<OperationExecutionResponse[]>(`${root(organizationId, resourceId)}/operation-executions?limit=20`),
  })
}

export function useExecuteResourceOperation(organizationId: string, resourceId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (operation: ResourceOperationCode) => requestJson<OperationExecutionResponse>(
      `${root(organizationId, resourceId)}/operations/${operation}/executions`, { method: 'POST' }),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['resource-operation-executions', organizationId, resourceId] }),
        queryClient.invalidateQueries({ queryKey: ['resource-operations', organizationId, resourceId] }),
        queryClient.invalidateQueries({ queryKey: ['resource', organizationId, resourceId] }),
        // The resource's status also shows in its parent's children preview; only mounted
        // contexts refetch, so this costs one request at most.
        queryClient.invalidateQueries({ queryKey: ['resource-context', organizationId] }),
        invalidateOverview(queryClient, organizationId),
      ])
    },
  })
}

