import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { requestJson } from './httpClient'
import type {
  ApprovedRolloutTarget, PromotionPreview, PromotionResult, PromotionSelection,
  RolloutDetail, RolloutPreflightItem, RolloutStrategy, RolloutSummary, RolloutTarget,
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
export function usePromoteAssignments(organizationId: string, profileId: string) {
  const client = useQueryClient()
  return useMutation({ mutationFn: (body: { revisionNumber: number; assignments: PromotionSelection[] }) =>
    requestJson<PromotionResult>(promotions(organizationId, profileId),
      { method: 'POST', body: JSON.stringify(body) }),
  onSuccess: () => { void client.invalidateQueries({ queryKey: ['configurationAssignments', organizationId] }) } })
}
export function useRolloutPreflight(organizationId: string) {
  return useMutation({ mutationFn: (targets: RolloutTarget[]) =>
    requestJson<RolloutPreflightItem[]>(`${rollouts(organizationId)}/preflight`,
      { method: 'POST', body: JSON.stringify({ targets }) }) })
}
export function useCreateRollout(organizationId: string) {
  const client = useQueryClient()
  return useMutation({ mutationFn: (body: { profileId: string; revisionNumber: number; requestId: string;
    targets: ApprovedRolloutTarget[] } & RolloutStrategy) =>
    requestJson<{ rolloutId: string; state: 'QUEUED' }>(rollouts(organizationId),
      { method: 'POST', body: JSON.stringify(body) }),
  onSuccess: () => { void client.invalidateQueries({ queryKey: ['configurationRollouts', organizationId] }) } })
}
export function useRollout(organizationId: string, id: string | null) {
  return useQuery({ queryKey: ['configurationRollout', organizationId, id], enabled: id !== null,
    queryFn: () => requestJson<RolloutDetail>(`${rollouts(organizationId)}/${encodeURIComponent(id!)}`),
    refetchInterval: query => query.state.data && ['QUEUED', 'RUNNING', 'PAUSED', 'ROLLING_BACK']
      .includes(query.state.data.rollout.state) ? 2000 : false })
}
export function useRolloutHistory(organizationId: string, profileId: string) {
  return useInfiniteQuery({ queryKey: ['configurationRollouts', organizationId, profileId],
    queryFn: ({ pageParam }) => {
      const params = new URLSearchParams({ limit: '50' })
      params.set('profileId', profileId)
      if (pageParam) { params.set('beforeCreatedAt', pageParam.createdAt); params.set('beforeId', pageParam.id) }
      return requestJson<RolloutSummary[]>(`${rollouts(organizationId)}?${params}`)
    },
    initialPageParam: undefined as { createdAt: string; id: string } | undefined,
    getNextPageParam: page => page.length < 50 ? undefined : page[page.length - 1] })
}
export function useCancelRollout(organizationId: string) {
  const client = useQueryClient()
  return useMutation({ mutationFn: (id: string) => requestJson<{ cancelRequested: boolean }>(
    `${rollouts(organizationId)}/${encodeURIComponent(id)}/cancel`, { method: 'POST' }),
  onSuccess: (_value, id) => { void client.invalidateQueries({ queryKey: ['configurationRollout', organizationId, id] }) } })
}
