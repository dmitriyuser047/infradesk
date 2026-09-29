import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { requestJson, requestVoid } from './httpClient'
import type { BindingCandidate, IntegrationOverview, IntegrationSyncSession, InventoryPage,
  RemnawaveConfigProfileSummary, RemnawaveHostSummary, RemnawaveNodeSummary,
  ResourceIntegrationBinding } from '../types/integration'

export type InventoryKind = 'nodes' | 'hosts' | 'config-profiles'
export interface InventorySummaries {
  nodes: RemnawaveNodeSummary
  hosts: RemnawaveHostSummary
  'config-profiles': RemnawaveConfigProfileSummary
}
export interface InventoryParams { search: string; active: '' | 'true' | 'false'; state: string; offset: number; limit: number }

const org = (value: string) => `/api/v1/organizations/${encodeURIComponent(value)}`
const base = (organizationId: string, id: string) => `${org(organizationId)}/integrations/${encodeURIComponent(id)}`
/** Everything that depends on one integration's observed state, invalidated together after a sync. */
const scope = (organizationId: string, id: string) => ['integration-inventory', organizationId, id] as const

export function useIntegrationSummary(organizationId: string, id: string, enabled: boolean) {
  return useQuery({ queryKey: [...scope(organizationId, id), 'summary'], enabled,
    queryFn: () => requestJson<IntegrationOverview>(`${base(organizationId, id)}/inventory/summary`) })
}

export function useIntegrationInventory<K extends InventoryKind>(organizationId: string, id: string, kind: K,
  params: InventoryParams, enabled: boolean) {
  const query = new URLSearchParams({ limit: String(params.limit), offset: String(params.offset) })
  if (params.search.trim()) query.set('search', params.search.trim())
  if (params.active) query.set('active', params.active)
  if (params.state && kind === 'nodes') query.set('state', params.state)
  return useQuery({ queryKey: [...scope(organizationId, id), kind, query.toString()], enabled,
    placeholderData: keepPreviousData,
    queryFn: () => requestJson<InventoryPage<InventorySummaries[K]>>(`${base(organizationId, id)}/inventory/${kind}?${query}`) })
}

export function useIntegrationSyncSessions(organizationId: string, id: string, enabled: boolean) {
  return useQuery({ queryKey: [...scope(organizationId, id), 'sessions'], enabled,
    queryFn: () => requestJson<{ items: IntegrationSyncSession[] }>(`${base(organizationId, id)}/sync-sessions?limit=20`)
      .then(value => value.items) })
}

/** Answers with the finished session: a remote failure is a FAILED session, not an error. */
export function useSyncIntegration(organizationId: string, id: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: () => requestJson<IntegrationSyncSession>(`${base(organizationId, id)}/sync`, { method: 'POST' }),
    onSettled: () => Promise.all([
      client.invalidateQueries({ queryKey: scope(organizationId, id) }),
      client.invalidateQueries({ queryKey: ['integration', organizationId, id] }),
      client.invalidateQueries({ queryKey: ['integrations', organizationId] }),
    ]),
  })
}

export function useBindingCandidates(organizationId: string, id: string, search: string, enabled: boolean) {
  const query = new URLSearchParams({ limit: '50' })
  if (search.trim()) query.set('search', search.trim())
  return useQuery({ queryKey: [...scope(organizationId, id), 'candidates', query.toString()], enabled,
    placeholderData: keepPreviousData,
    queryFn: () => requestJson<{ items: BindingCandidate[] }>(`${base(organizationId, id)}/binding-candidates?${query}`)
      .then(value => value.items) })
}

function useBindingRefresh(organizationId: string, id: string) {
  const client = useQueryClient()
  return () => Promise.all([
    client.invalidateQueries({ queryKey: [...scope(organizationId, id), 'nodes'] }),
    client.invalidateQueries({ queryKey: ['resource-integration-bindings', organizationId] }),
  ])
}

export function useBindNode(organizationId: string, id: string) {
  const refresh = useBindingRefresh(organizationId, id)
  return useMutation({
    mutationFn: ({ objectId, resourceId }: { objectId: string; resourceId: string }) => requestJson<unknown>(
      `${base(organizationId, id)}/inventory/objects/${encodeURIComponent(objectId)}/binding`,
      { method: 'PUT', body: JSON.stringify({ resourceId }) }),
    onSettled: refresh,
  })
}

export function useUnbindNode(organizationId: string, id: string) {
  const refresh = useBindingRefresh(organizationId, id)
  return useMutation({
    mutationFn: (objectId: string) => requestVoid(
      `${base(organizationId, id)}/inventory/objects/${encodeURIComponent(objectId)}/binding`, { method: 'DELETE' }),
    onSettled: refresh,
  })
}

export function useResourceIntegrationBindings(organizationId: string, resourceId: string, enabled: boolean) {
  return useQuery({ queryKey: ['resource-integration-bindings', organizationId, resourceId], enabled,
    queryFn: () => requestJson<{ items: ResourceIntegrationBinding[] }>(
      `${org(organizationId)}/resources/${encodeURIComponent(resourceId)}/integration-bindings`).then(value => value.items) })
}
