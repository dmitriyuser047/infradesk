import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { requestJson } from './httpClient'
import type { ProvisioningDetail, ProvisioningPlan, ProvisioningRun } from '../types/provisioning'

const base = (organizationId: string) => `/api/v1/organizations/${encodeURIComponent(organizationId)}`
const active = (state: ProvisioningRun['state']) => state === 'QUEUED' || state === 'RUNNING'
export const ProvisioningPollMillis = 3000
export function provisioningPollInterval(state: ProvisioningRun['state'] | undefined): number | false {
  return state && active(state) ? ProvisioningPollMillis : false
}

export function useProvisioningHistory(organizationId: string, resourceId: string) {
  return useQuery({
    queryKey: ['provisioningHistory', organizationId, resourceId],
    queryFn: () => requestJson<{ items: ProvisioningRun[] }>(
      `${base(organizationId)}/resources/${encodeURIComponent(resourceId)}/provisioning/runs`),
    refetchInterval: query => query.state.data?.items?.some(run => active(run.state)) ? ProvisioningPollMillis : false,
  })
}

export function useProvisioningRun(organizationId: string, id: string | null) {
  return useQuery({
    queryKey: ['provisioningRun', organizationId, id],
    queryFn: () => requestJson<ProvisioningDetail>(`${base(organizationId)}/provisioning/runs/${encodeURIComponent(id!)}`),
    enabled: id !== null,
    refetchInterval: query => provisioningPollInterval(query.state.data?.run.state),
  })
}

export function usePlanProvisioning(organizationId: string) {
  return useMutation({ mutationFn: (resourceId: string) => requestJson<ProvisioningPlan>(
    `${base(organizationId)}/provisioning/plan`, { method: 'POST', body: JSON.stringify({ resourceId }) }),
  })
}

export function useStartProvisioning(organizationId: string, resourceId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { planId: string; requestId: string }) => requestJson<ProvisioningRun>(
      `${base(organizationId)}/provisioning/runs`, { method: 'POST', body: JSON.stringify(input) }),
    onSuccess: run => {
      void client.invalidateQueries({ queryKey: ['provisioningHistory', organizationId, resourceId] })
      void client.invalidateQueries({ queryKey: ['provisioningRun', organizationId, run.id] })
    },
  })
}
