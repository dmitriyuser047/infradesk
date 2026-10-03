import { useMutation, useQuery } from '@tanstack/react-query'
import { requestJson } from './httpClient'
import type { NodeOnboardingOptions, NodeOnboardingPreview, NodeOnboardingPreviewRequest, NodeOnboardingRun, NodeOnboardingRunDetail } from '../types/nodeOnboarding'

const path = (org: string, integration: string) => `/api/v1/organizations/${encodeURIComponent(org)}/integrations/${encodeURIComponent(integration)}/remnawave-node-onboarding`
const active = (state: NodeOnboardingRun['state']) => state === 'QUEUED' || state === 'RUNNING'

export function useNodeOnboardingOptions(org: string, integration: string, enabled: boolean) {
  return useQuery({ queryKey: ['nodeOnboardingOptions', org, integration], enabled,
    queryFn: () => requestJson<NodeOnboardingOptions>(`${path(org, integration)}/options`) })
}
export function useNodeOnboardingHistory(org: string, integration: string, enabled: boolean) {
  return useQuery({ queryKey: ['nodeOnboardingRuns', org, integration], enabled,
    queryFn: () => requestJson<{ items: NodeOnboardingRun[] }>(`${path(org, integration)}/runs`) })
}
export function usePreviewNodeOnboarding(org: string, integration: string) {
  return useMutation({ mutationFn: (body: NodeOnboardingPreviewRequest) => requestJson<NodeOnboardingPreview>(
    `${path(org, integration)}/preview`, { method: 'POST', body: JSON.stringify(body) }) })
}
export function useStartNodeOnboarding(org: string, integration: string) {
  return useMutation({ mutationFn: (body: { planId: string; requestId: string }) => requestJson<NodeOnboardingRun>(
    `${path(org, integration)}/runs`, { method: 'POST', body: JSON.stringify(body) }) })
}
export function useNodeOnboardingRun(org: string, integration: string, id: string | null) {
  return useQuery({ queryKey: ['nodeOnboardingRun', org, integration, id], enabled: id !== null,
    queryFn: () => requestJson<NodeOnboardingRunDetail>(`${path(org, integration)}/runs/${encodeURIComponent(id!)}`),
    refetchInterval: query => query.state.data && active(query.state.data.run.state) ? 2000 : false })
}
