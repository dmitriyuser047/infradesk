import { useMutation, useQuery } from '@tanstack/react-query'
import { requestJson } from './httpClient'
import type { NodeOnboardingOptions, NodeOnboardingPreview, NodeOnboardingPreviewRequest, NodeOnboardingRecoveryAction, NodeOnboardingRun, NodeOnboardingRunDetail } from '../types/nodeOnboarding'

const path = (org: string, integration: string) => `/api/v1/organizations/${encodeURIComponent(org)}/integrations/${encodeURIComponent(integration)}/remnawave-node-onboarding`
const active = (state: NodeOnboardingRun['state']) => state === 'QUEUED' || state === 'RUNNING'

/** Secret material is never a React Query mutation variable or cache entry. */
export function importNodeCertificate(org: string, integration: string, body: {
  resourceId: string; domain: string; certificatePem: string; privateKeyPem: string
}) {
  return requestJson<{ id: string; domain: string; fingerprint: string; expiresAt: string }>(
    `${path(org, integration)}/certificates`, { method: 'POST', body: JSON.stringify(body) })
}

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
  return useMutation({ mutationFn: (body: { planId: string; requestId: string; confirmRecreate?: boolean }) => requestJson<NodeOnboardingRun>(
    `${path(org, integration)}/runs`, { method: 'POST', body: JSON.stringify(body) }) })
}
export function useReconcileNodeOnboarding(org: string, integration: string) {
  return useMutation({ mutationFn: ({ runId, action }: { runId: string; action: NodeOnboardingRecoveryAction }) => requestJson<NodeOnboardingPreview>(
    `${path(org, integration)}/runs/${encodeURIComponent(runId)}/reconcile`, { method: 'POST', body: JSON.stringify({ action }) }) })
}
export function useNodeOnboardingRun(org: string, integration: string, id: string | null) {
  return useQuery({ queryKey: ['nodeOnboardingRun', org, integration, id], enabled: id !== null,
    queryFn: () => requestJson<NodeOnboardingRunDetail>(`${path(org, integration)}/runs/${encodeURIComponent(id!)}`),
    refetchInterval: query => query.state.data && active(query.state.data.run.state) ? 2000 : false })
}
