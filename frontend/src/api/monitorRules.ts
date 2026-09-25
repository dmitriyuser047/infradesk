import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { requestJson } from './httpClient'
import { invalidateOverview } from './overview'
import type { MonitorRuleRequest, MonitorRuleResponse } from '../types/monitorRule'

export function monitorRulesQueryKey(organizationId: string, resourceId: string) {
  return ['monitor-rules', organizationId, resourceId] as const
}

export function getMonitorRules(
  organizationId: string,
  resourceId: string,
): Promise<MonitorRuleResponse[]> {
  return requestJson<MonitorRuleResponse[]>(
    `/api/v1/organizations/${encodeURIComponent(organizationId)}/resources/${encodeURIComponent(resourceId)}/monitor-rules`,
  )
}

export function createMonitorRule(
  organizationId: string,
  resourceId: string,
  request: MonitorRuleRequest,
): Promise<MonitorRuleResponse> {
  return requestJson<MonitorRuleResponse>(
    `/api/v1/organizations/${encodeURIComponent(organizationId)}/resources/${encodeURIComponent(resourceId)}/monitor-rules`,
    {
      method: 'POST',
      body: JSON.stringify(request),
    },
  )
}

export function updateMonitorRule(
  organizationId: string,
  monitorRuleId: string,
  request: MonitorRuleRequest,
): Promise<MonitorRuleResponse> {
  return requestJson<MonitorRuleResponse>(
    `/api/v1/organizations/${encodeURIComponent(organizationId)}/monitor-rules/${encodeURIComponent(monitorRuleId)}`,
    {
      method: 'PUT',
      body: JSON.stringify(request),
    },
  )
}

export function useMonitorRules(
  organizationId: string,
  resourceId: string,
) {
  return useQuery({
    queryKey: monitorRulesQueryKey(organizationId, resourceId),
    queryFn: () => getMonitorRules(organizationId, resourceId),
  })
}

export function useCreateMonitorRule(
  organizationId: string,
  resourceId: string,
) {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (request: MonitorRuleRequest) => createMonitorRule(organizationId, resourceId, request),
    onSuccess: async () => {
      await queryClient.invalidateQueries({
        queryKey: monitorRulesQueryKey(organizationId, resourceId),
      })
    },
  })
}

export function useUpdateMonitorRule(
  organizationId: string,
  resourceId: string,
) {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: ({ monitorRuleId, request }: { monitorRuleId: string; request: MonitorRuleRequest }) =>
      updateMonitorRule(organizationId, monitorRuleId, request),
    onSuccess: async () => {
      // Disabling or changing a rule can resolve its open incident.
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: monitorRulesQueryKey(organizationId, resourceId) }),
        invalidateOverview(queryClient, organizationId),
      ])
    },
  })
}
