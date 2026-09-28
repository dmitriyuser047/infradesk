import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { DeploymentPollMillis, HistoryPageSize } from './configurationDeployments'
import { requestJson } from './httpClient'
import type { HistoryCursor, HistoryPage } from '../types/configurationDeployment'
import {
  ActiveRolloutStates,
  type ApprovedRolloutTarget,
  type PromotionPreview,
  type PromotionResult,
  type PromotionSelection,
  type Rollout,
  type RolloutDetail,
  type RolloutPreflight,
  type RolloutStrategy,
  type RolloutTarget,
} from '../types/configurationRollout'

const base = (organizationId: string) => `/api/v1/organizations/${encodeURIComponent(organizationId)}`
const promotions = (organizationId: string, profileId: string) =>
  `${base(organizationId)}/configuration-profiles/${encodeURIComponent(profileId)}/assignment-promotions`
const rollouts = (organizationId: string) => `${base(organizationId)}/configuration-rollouts`

export function usePromotionPreview(organizationId: string, profileId: string) {
  return useMutation({ mutationFn: (body: { revisionNumber: number; assignments: PromotionSelection[] }) =>
    requestJson<PromotionPreview>(`${promotions(organizationId, profileId)}/preview`,
      { method: 'POST', body: JSON.stringify(body) }) })
}

/** All selected assignments move to the revision in one request, or none does. */
export function usePromoteAssignments(organizationId: string, profileId: string) {
  const client = useQueryClient()
  return useMutation({ mutationFn: (body: { revisionNumber: number; assignments: PromotionSelection[] }) =>
    requestJson<PromotionResult>(promotions(organizationId, profileId),
      { method: 'POST', body: JSON.stringify(body) }),
  onSuccess: () => { void client.invalidateQueries({ queryKey: ['configurationAssignments', organizationId] }) } })
}

/** Read-only checks of every selected node; nothing on a server changes. */
export function useRolloutPreflight(organizationId: string) {
  return useMutation({ mutationFn: (body: { profileId: string; revisionNumber: number; targets: RolloutTarget[] }) =>
    requestJson<RolloutPreflight>(`${rollouts(organizationId)}/preflight`, { method: 'POST', body: JSON.stringify(body) }) })
}

export function useCreateRollout(organizationId: string) {
  const client = useQueryClient()
  return useMutation({ mutationFn: (body: { profileId: string; revisionNumber: number; requestId: string;
    strategy: RolloutStrategy; targets: ApprovedRolloutTarget[] }) =>
    requestJson<{ rolloutId: string; state: 'QUEUED' }>(rollouts(organizationId),
      { method: 'POST', body: JSON.stringify(body) }),
  onSuccess: () => { void client.invalidateQueries({ queryKey: ['configurationRollouts', organizationId] }) } })
}

export function useRollout(organizationId: string, id: string | null) {
  return useQuery({ queryKey: ['configurationRollout', organizationId, id], enabled: id !== null,
    queryFn: () => requestJson<RolloutDetail>(`${rollouts(organizationId)}/${encodeURIComponent(id!)}`),
    refetchInterval: query => query.state.data && ActiveRolloutStates.includes(query.state.data.state)
      ? DeploymentPollMillis : false })
}

export function useRolloutHistory(organizationId: string, profileId: string) {
  return useInfiniteQuery({ queryKey: ['configurationRollouts', organizationId, profileId],
    queryFn: ({ pageParam }) => {
      const params = new URLSearchParams({ limit: String(HistoryPageSize), profileId })
      if (pageParam) { params.set('beforeCreatedAt', pageParam.beforeCreatedAt); params.set('beforeId', pageParam.beforeId) }
      return requestJson<HistoryPage<Rollout>>(`${rollouts(organizationId)}?${params}`)
    },
    initialPageParam: undefined as HistoryCursor | undefined,
    getNextPageParam: page => page.nextCursor ?? undefined,
    refetchInterval: query => query.state.data?.pages.some(page => page.items.some(item =>
      ActiveRolloutStates.includes(item.state))) ? DeploymentPollMillis * 2 : false })
}

/** Stops future nodes; with `rollbackApplied`, every node the rollout applied is restored. */
export function useCancelRollout(organizationId: string) {
  const client = useQueryClient()
  return useMutation({ mutationFn: ({ id, rollbackApplied }: { id: string; rollbackApplied: boolean }) =>
    requestJson<{ cancelRequested: boolean; rollbackRequested: boolean }>(
      `${rollouts(organizationId)}/${encodeURIComponent(id)}/cancel`,
      { method: 'POST', body: JSON.stringify({ rollbackApplied }) }),
  onSuccess: (_value, { id }) => {
    void client.invalidateQueries({ queryKey: ['configurationRollout', organizationId, id] })
    void client.invalidateQueries({ queryKey: ['configurationRollouts', organizationId] })
  } })
}
