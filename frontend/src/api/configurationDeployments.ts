import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { requestJson } from './httpClient'
import {
  ActiveDeploymentStates,
  type Deployment,
  type DeploymentDetail,
  type DeploymentExecution,
  type DeploymentPreview,
  type DeploymentSummary,
  type HistoryCursor,
  type HistoryPage,
} from '../types/configurationDeployment'

const base = (organizationId: string) => `/api/v1/organizations/${encodeURIComponent(organizationId)}`
const assignment = (organizationId: string, id: string) =>
  `${base(organizationId)}/configuration-assignments/${encodeURIComponent(id)}`

/** Status polling is enough for a deployment: no socket exists only for this. */
export const DeploymentPollMillis = 2000
export const HistoryPageSize = 25

export interface DeploymentRequest {
  expectedAssignmentVersion: number
  connectionId: string
  expectedRemoteSha256: string | null
  expectedRemoteMissing: boolean
  requestId: string
  execution: DeploymentExecution
  retryOfDeploymentId?: string | null
}

/** Reads the remote file once; the diff lives only in this response and is never stored. */
export function useDeploymentPreview(organizationId: string, assignmentId: string) {
  return useMutation({
    mutationFn: (body: { expectedAssignmentVersion: number; connectionId: string }) =>
      requestJson<DeploymentPreview>(`${assignment(organizationId, assignmentId)}/deployment-preview`,
        { method: 'POST', body: JSON.stringify(body) }),
  })
}

export function useCreateDeployment(organizationId: string, assignmentId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (body: DeploymentRequest) =>
      requestJson<{ deploymentId: string; state: 'QUEUED' }>(`${assignment(organizationId, assignmentId)}/deployments`,
        { method: 'POST', body: JSON.stringify(body) }),
    onSuccess: () => { void client.invalidateQueries({ queryKey: ['configurationDeploymentSummaries', organizationId] }) },
  })
}

export function useDeployment(organizationId: string, id: string | null) {
  const client = useQueryClient()
  return useQuery({
    queryKey: ['configurationDeployment', organizationId, id],
    queryFn: async () => {
      const value = await requestJson<DeploymentDetail>(`${base(organizationId)}/configuration-deployments/${encodeURIComponent(id!)}`)
      if (!ActiveDeploymentStates.includes(value.state))
        void client.invalidateQueries({ queryKey: ['configurationDeploymentSummaries', organizationId] })
      return value
    },
    enabled: id !== null,
    refetchInterval: query => query.state.data && ActiveDeploymentStates.includes(query.state.data.state)
      ? DeploymentPollMillis : false,
  })
}

export function useCancelDeployment(organizationId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => requestJson<{ cancelRequested: boolean }>(
      `${base(organizationId)}/configuration-deployments/${encodeURIComponent(id)}/cancel`, { method: 'POST' }),
    onSuccess: (_value, id) => { void client.invalidateQueries({ queryKey: ['configurationDeployment', organizationId, id] }) },
  })
}

/** One page per request, newest first; never the whole history at once. */
export function useDeploymentHistory(organizationId: string, filter: { profileId?: string; resourceId?: string }) {
  return useInfiniteQuery({
    queryKey: ['configurationDeploymentHistory', organizationId, filter.profileId ?? null, filter.resourceId ?? null],
    queryFn: ({ pageParam }) => {
      const params = new URLSearchParams({ limit: String(HistoryPageSize) })
      if (filter.profileId) params.set('profileId', filter.profileId)
      if (filter.resourceId) params.set('resourceId', filter.resourceId)
      if (pageParam) { params.set('beforeCreatedAt', pageParam.beforeCreatedAt); params.set('beforeId', pageParam.beforeId) }
      return requestJson<HistoryPage<Deployment>>(`${base(organizationId)}/configuration-deployments?${params}`)
    },
    initialPageParam: undefined as HistoryCursor | undefined,
    getNextPageParam: page => page.nextCursor ?? undefined,
    refetchInterval: query => query.state.data?.pages.some(page => page.items.some(item =>
      ActiveDeploymentStates.includes(item.state))) ? DeploymentPollMillis * 2 : false,
  })
}

/** The last success and the active deployment of a whole page of assignments, in one request. */
export function useDeploymentSummaries(organizationId: string, assignmentIds: string[], enabled = true) {
  const ids = [...new Set(assignmentIds)].sort()
  return useQuery({
    queryKey: ['configurationDeploymentSummaries', organizationId, ids],
    queryFn: () => {
      const params = new URLSearchParams()
      ids.forEach(id => params.append('assignmentId', id))
      return requestJson<DeploymentSummary[]>(`${base(organizationId)}/configuration-deployment-summaries?${params}`)
    },
    enabled: enabled && ids.length > 0 && ids.length <= 100,
    refetchInterval: query => query.state.data?.some(summary => summary.activeDeployment !== null)
      ? DeploymentPollMillis * 2 : false,
  })
}
