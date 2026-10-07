import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useRef } from 'react'
import { requestJson, requestVoid } from './httpClient'
import type { CreateIntegrationRequest, IntegrationProviderResponse, IntegrationResponse,
  IntegrationTestResponse, UpdateIntegrationRequest } from '../types/integration'

const path = (org: string) => `/api/v1/organizations/${encodeURIComponent(org)}/integrations`
const listKey = (org: string) => ['integrations', org] as const
const itemKey = (org: string, id: string) => ['integration', org, id] as const

export function useIntegrations(org: string, enabled: boolean) {
  return useQuery({ queryKey: listKey(org), queryFn: () => requestJson<IntegrationResponse[]>(path(org)), enabled })
}
export function useIntegration(org: string, id: string | undefined, enabled: boolean) {
  return useQuery({ queryKey: itemKey(org, id ?? ''), queryFn: () => requestJson<IntegrationResponse>(`${path(org)}/${encodeURIComponent(id!)}`),
    enabled: enabled && Boolean(id) })
}
export function useIntegrationProviders(org: string, enabled: boolean) {
  return useQuery({ queryKey: ['integration-providers', org],
    queryFn: () => requestJson<IntegrationProviderResponse[]>(`/api/v1/organizations/${encodeURIComponent(org)}/integration-providers`), enabled })
}

/** A write response has no overview; the one already known stays until the next read. */
function updateCache(client: ReturnType<typeof useQueryClient>, org: string, value: IntegrationResponse) {
  const merged = (old: IntegrationResponse | undefined) => ({ ...value, overview: value.overview ?? old?.overview })
  client.setQueryData<IntegrationResponse>(itemKey(org, value.id), old => merged(old))
  client.setQueryData<IntegrationResponse[]>(listKey(org), old => old?.some(item => item.id === value.id)
    ? old.map(item => item.id === value.id ? merged(item) : item) : old ? [...old, value] : old)
  // Enabling or disabling moves the next automatic run.
  void client.invalidateQueries({ queryKey: ['integration-inventory', org, value.id] })
}

/** The token stays in a ref only until the request settles, never in mutation variables/cache. */
export function useSaveIntegration(org: string, id?: string) {
  const client = useQueryClient()
  const pending = useRef<CreateIntegrationRequest | UpdateIntegrationRequest | null>(null)
  const mutation = useMutation<IntegrationResponse, Error, void>({
    gcTime: 0,
    mutationFn: async () => {
      const body = pending.current
      if (!body) throw new Error('Missing integration request')
      try { return await requestJson<IntegrationResponse>(id ? `${path(org)}/${encodeURIComponent(id)}` : path(org),
        { method: id ? 'PUT' : 'POST', body: JSON.stringify(body) }) }
      finally { pending.current = null }
    },
    onSuccess: value => updateCache(client, org, value),
  })
  return { ...mutation, submit: (body: CreateIntegrationRequest | UpdateIntegrationRequest,
    onSuccess: (value: IntegrationResponse) => void) => {
    if (pending.current || mutation.isPending) return
    pending.current = body
    mutation.mutate(undefined, { onSuccess })
  } }
}

export function useSetIntegrationEnabled(org: string) {
  const client = useQueryClient()
  return useMutation({ mutationFn: ({ id, enabled }: { id: string; enabled: boolean }) =>
    requestJson<IntegrationResponse>(`${path(org)}/${encodeURIComponent(id)}/${enabled ? 'enable' : 'disable'}`, { method: 'POST' }),
  onSuccess: value => updateCache(client, org, value) })
}
export function useDeleteIntegration(org: string) {
  const client = useQueryClient()
  return useMutation({ mutationFn: (input: string | { id: string; abandonRecovery: true }) => {
    const id = typeof input === 'string' ? input : input.id
    return requestVoid(`${path(org)}/${encodeURIComponent(id)}${typeof input === 'string' ? '' : '/abandon-recovery-and-delete'}`,
      { method: typeof input === 'string' ? 'DELETE' : 'POST' })
  }, onSuccess: (_value, input) => {
      const id = typeof input === 'string' ? input : input.id
      client.removeQueries({ queryKey: itemKey(org, id) })
      client.setQueryData<IntegrationResponse[]>(listKey(org), old => old?.filter(item => item.id !== id))
    } })
}
export function useTestIntegration(org: string) {
  return useMutation({ mutationFn: (id: string) => requestJson<IntegrationTestResponse>(
    `${path(org)}/${encodeURIComponent(id)}/test`, { method: 'POST' }) })
}
