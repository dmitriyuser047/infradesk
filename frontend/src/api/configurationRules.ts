import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { requestJson, requestVoid } from './httpClient'
import type {
  ConfigurationRule, LabelSet, RuleDraft, RulePromotionPreview, RuleSelector, RuleTarget, SelectorPreview,
} from '../types/configurationRule'

const base = (organizationId: string) => `/api/v1/organizations/${encodeURIComponent(organizationId)}`
const rules = (organizationId: string) => `${base(organizationId)}/configuration-assignment-rules`
const rule = (organizationId: string, id: string) => `${rules(organizationId)}/${encodeURIComponent(id)}`
export const RulePageSize = 25

interface Page<T, C> { items: T[]; nextCursor: C | null }

/** Rules manage desired state only; none of these requests reaches a server. */
export function useConfigurationRules(organizationId: string, profileId: string, includeArchived = false) {
  return useInfiniteQuery({
    queryKey: ['configurationRules', organizationId, profileId, includeArchived],
    queryFn: ({ pageParam }) => {
      const params = new URLSearchParams({ limit: String(RulePageSize), profileId })
      if (includeArchived) params.set('includeArchived', 'true')
      if (pageParam) { params.set('afterCreatedAt', pageParam.afterCreatedAt); params.set('afterId', pageParam.afterId) }
      return requestJson<Page<ConfigurationRule, { afterCreatedAt: string; afterId: string }>>(`${rules(organizationId)}?${params}`)
    },
    initialPageParam: undefined as { afterCreatedAt: string; afterId: string } | undefined,
    getNextPageParam: page => page.nextCursor ?? undefined,
  })
}

export function useConfigurationRule(organizationId: string, id: string | null) {
  return useQuery({ queryKey: ['configurationRule', organizationId, id], enabled: id !== null,
    queryFn: () => requestJson<ConfigurationRule>(rule(organizationId, id!)) })
}

function useRuleMutation<T, R>(organizationId: string, fn: (body: T) => Promise<R>) {
  const client = useQueryClient()
  return useMutation({ mutationFn: fn, onSuccess: () => {
    void client.invalidateQueries({ queryKey: ['configurationRules', organizationId] })
    void client.invalidateQueries({ queryKey: ['configurationRule', organizationId] })
    void client.invalidateQueries({ queryKey: ['configurationRuleTargets', organizationId] })
    void client.invalidateQueries({ queryKey: ['configurationAssignments', organizationId] })
  } })
}

export function useCreateRule(organizationId: string) {
  return useRuleMutation(organizationId, (draft: RuleDraft) =>
    requestJson<ConfigurationRule>(rules(organizationId), { method: 'POST', body: JSON.stringify(draft) }))
}

export function useUpdateRule(organizationId: string, id: string) {
  return useRuleMutation(organizationId, (body: { expectedVersion: number; name: string; description: string | null; selector: RuleSelector }) =>
    requestJson<ConfigurationRule>(rule(organizationId, id), { method: 'PATCH', body: JSON.stringify(body) }))
}

/** Enable, disable or archive, each at the version the user saw. */
export function useRuleLifecycle(organizationId: string, id: string) {
  return useRuleMutation(organizationId, ({ action, expectedVersion }: { action: 'enable' | 'disable' | 'archive'; expectedVersion: number }) =>
    action === 'archive'
      ? requestJson<ConfigurationRule>(`${rule(organizationId, id)}?expectedVersion=${expectedVersion}`, { method: 'DELETE' })
      : requestJson<ConfigurationRule>(`${rule(organizationId, id)}/${action}`, { method: 'POST', body: JSON.stringify({ expectedVersion }) }))
}

/** Only schedules a reconciliation; the request never waits for it. */
export function useReconcileRule(organizationId: string, id: string) {
  return useRuleMutation(organizationId, (_: void) =>
    requestJson<{ scheduled: boolean }>(`${rule(organizationId, id)}/reconcile`, { method: 'POST' }))
}

export function useRuleTargets(organizationId: string, id: string) {
  return useInfiniteQuery({
    queryKey: ['configurationRuleTargets', organizationId, id],
    queryFn: ({ pageParam }) => {
      const params = new URLSearchParams({ limit: String(RulePageSize) })
      if (pageParam) { params.set('afterName', pageParam.afterName); params.set('afterId', pageParam.afterId) }
      return requestJson<Page<RuleTarget, { afterName: string; afterId: string }>>(`${rule(organizationId, id)}/targets?${params}`)
    },
    initialPageParam: undefined as { afterName: string; afterId: string } | undefined,
    getNextPageParam: page => page.nextCursor ?? undefined,
  })
}

export function useSelectorPreview(organizationId: string) {
  return useMutation({ mutationFn: (body: { profileId: string; revisionNumber: number; targetPath: string; selector: RuleSelector }) =>
    requestJson<SelectorPreview>(`${rules(organizationId)}/selector-preview`, { method: 'POST', body: JSON.stringify(body) }) })
}

export function useRuleExclusion(organizationId: string, id: string) {
  return useRuleMutation(organizationId, ({ resourceId, exclude }: { resourceId: string; exclude: boolean }) =>
    // 204 No Content: nothing to parse.
    requestVoid(`${rule(organizationId, id)}/exclude/${encodeURIComponent(resourceId)}`, { method: exclude ? 'POST' : 'DELETE' }))
}

export function useManagedAssignmentAction(organizationId: string) {
  return useRuleMutation(organizationId, ({ ruleId, assignmentId, action, expectedVersion }: {
    ruleId: string; assignmentId: string; action: 'detach' | 'adopt' | 'exclude-and-remove'; expectedVersion: number }) =>
    requestVoid(`${rule(organizationId, ruleId)}/assignments/${encodeURIComponent(assignmentId)}/${action}`,
      { method: 'POST', body: JSON.stringify({ expectedVersion }) }))
}

export function useCompleteRuleTarget(organizationId: string, id: string) {
  return useRuleMutation(organizationId, ({ resourceId, values }: { resourceId: string; values: Array<{ name: string; value: string }> }) =>
    requestJson<{ assignmentId: string }>(`${rule(organizationId, id)}/targets/${encodeURIComponent(resourceId)}/assignment`,
      { method: 'POST', body: JSON.stringify({ values }) }))
}

export function useRulePromotionPreview(organizationId: string, id: string) {
  return useMutation({ mutationFn: (body: { targetRevisionNumber: number; expectedRuleVersion: number }) =>
    requestJson<RulePromotionPreview>(`${rule(organizationId, id)}/promotion-preview`, { method: 'POST', body: JSON.stringify(body) }) })
}

/** The rule and every assignment it manages move together, or nothing moves. */
export function usePromoteRule(organizationId: string, id: string) {
  return useRuleMutation(organizationId, (body: { targetRevisionNumber: number; expectedRuleVersion: number;
    assignments: Array<{ assignmentId: string; expectedVersion: number }> }) =>
    requestJson<{ revisionNumber: number; assignments: Array<{ assignmentId: string; version: number }> }>(
      `${rule(organizationId, id)}/promote`, { method: 'POST', body: JSON.stringify(body) }))
}

export function useResourceLabels(organizationId: string, resourceId: string) {
  return useQuery({ queryKey: ['resourceLabels', organizationId, resourceId],
    queryFn: () => requestJson<LabelSet>(`${base(organizationId)}/resources/${encodeURIComponent(resourceId)}/labels`) })
}

/** Replaces the whole set at the version the user edited; a concurrent change is refused, never overwritten. */
export function useReplaceResourceLabels(organizationId: string, resourceId: string) {
  const client = useQueryClient()
  return useMutation({ mutationFn: (body: { expectedVersion: number; labels: LabelSet['labels'] }) =>
    requestJson<LabelSet>(`${base(organizationId)}/resources/${encodeURIComponent(resourceId)}/labels`,
      { method: 'PUT', body: JSON.stringify(body) }),
  onSuccess: value => {
    client.setQueryData(['resourceLabels', organizationId, resourceId], value)
    // Matches may have changed; rules reconcile soon, so their views are read again.
    void client.invalidateQueries({ queryKey: ['configurationRules', organizationId] })
    void client.invalidateQueries({ queryKey: ['configurationRuleTargets', organizationId] })
  } })
}
