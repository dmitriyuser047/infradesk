import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { requestJson, requestVoid } from './httpClient'
import type { ServerProfileAssignment, ServerProfileContent, ServerProfileDetail, ServerProfileList, ServerProfilePlan, ServerProfileAutomation, ServerProfileObservation } from '../types/serverProfile'

const base = (org: string) => `/api/v1/organizations/${encodeURIComponent(org)}`
const profiles = (org: string) => `${base(org)}/server-profiles`
const resourceProfile = (org: string, resource: string) => `${base(org)}/resources/${encodeURIComponent(resource)}/server-profile`
export const serverProfileKeys = {
  list: (org: string, archived: boolean) => ['serverProfiles', org, archived] as const,
  detail: (org: string, id: string) => ['serverProfile', org, id] as const,
  automation: (org: string, resource: string) => ['serverProfileAutomation', org, resource] as const,
}

export function useServerProfiles(org: string, archived: boolean, enabled: boolean) {
  return useQuery({ queryKey: serverProfileKeys.list(org, archived), enabled,
    queryFn: () => requestJson<ServerProfileList>(`${profiles(org)}?archived=${archived}&limit=200`) })
}
export function useServerProfile(org: string, id: string | null, enabled: boolean) {
  return useQuery({ queryKey: serverProfileKeys.detail(org, id ?? ''), enabled: enabled && !!id,
    queryFn: () => requestJson<ServerProfileDetail>(`${profiles(org)}/${encodeURIComponent(id!)}`) })
}
export function useCreateServerProfile(org: string) {
  const client = useQueryClient()
  return useMutation({ mutationFn: (body: { code: string; name: string; description: string | null; content: ServerProfileContent }) =>
    requestJson<{ profile: ServerProfileDetail['profile']; revision: ServerProfileDetail['revisions'][number] }>(profiles(org), { method: 'POST', body: JSON.stringify(body) }),
    onSuccess: () => void client.invalidateQueries({ queryKey: ['serverProfiles', org] }) })
}
export function useAppendServerProfileRevision(org: string, id: string) {
  const client = useQueryClient()
  return useMutation({ mutationFn: (content: ServerProfileContent) => requestJson<ServerProfileDetail['revisions'][number]>(
    `${profiles(org)}/${encodeURIComponent(id)}/revisions`, { method: 'POST', body: JSON.stringify({ content }) }),
    onSuccess: () => { void client.invalidateQueries({ queryKey: serverProfileKeys.detail(org, id) }); void client.invalidateQueries({ queryKey: ['serverProfiles', org] }) } })
}
export function useArchiveServerProfile(org: string, id: string) {
  const client = useQueryClient()
  return useMutation({ mutationFn: () => requestJson<ServerProfileDetail['profile']>(`${profiles(org)}/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    onSuccess: () => { void client.invalidateQueries({ queryKey: serverProfileKeys.detail(org, id) }); void client.invalidateQueries({ queryKey: ['serverProfiles', org] }) } })
}
export function useAssignServerProfile(org: string, resource: string) {
  const client = useQueryClient()
  return useMutation({ mutationFn: (body: { profileId: string; revisionNumber: number }) => requestJson<ServerProfileAssignment>(
    resourceProfile(org, resource), { method: 'PUT', body: JSON.stringify(body) }),
    onSuccess: () => { void client.invalidateQueries({ queryKey: serverProfileKeys.automation(org, resource) }); void client.invalidateQueries({ queryKey: ['serverProfile', org] }) } })
}
export function useUnassignServerProfile(org: string, resource: string) {
  const client = useQueryClient()
  return useMutation({ mutationFn: () => requestVoid(resourceProfile(org, resource), { method: 'DELETE' }),
    onSuccess: () => void client.invalidateQueries({ queryKey: serverProfileKeys.automation(org, resource) }) })
}
export function useServerProfileAutomation(org: string, resource: string, enabled: boolean) {
  return useQuery({ queryKey: serverProfileKeys.automation(org, resource), enabled,
    queryFn: () => requestJson<ServerProfileAutomation>(`${resourceProfile(org, resource)}/automation`),
    refetchInterval: query => query.state.data && (query.state.data.operationsBlocked || query.state.data.activeRun) ? 3000 : false })
}
export function useObserveServerProfile(org: string, resource: string) {
  const client = useQueryClient()
  return useMutation({ mutationFn: () => requestJson<ServerProfileObservation>(`${resourceProfile(org, resource)}/observe`, { method: 'POST' }),
    onSuccess: () => void client.invalidateQueries({ queryKey: serverProfileKeys.automation(org, resource) }) })
}
export function usePreviewServerProfile(org: string, resource: string) {
  const client = useQueryClient()
  return useMutation({ mutationFn: () => requestJson<ServerProfilePlan>(`${resourceProfile(org, resource)}/preview`, { method: 'POST' }),
    onSuccess: () => void client.invalidateQueries({ queryKey: serverProfileKeys.automation(org, resource) }) })
}
