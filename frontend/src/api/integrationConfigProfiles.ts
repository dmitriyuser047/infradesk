import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError, requestJson } from './httpClient'

export type ConfigStatus = 'UNAVAILABLE' | 'DEPLOYING' | 'WAITING_REFRESH' | 'DEPLOYMENT_FAILED' |
  'IN_SYNC' | 'LOCAL_CHANGES' | 'REMOTE_DRIFT'
export type DeploymentStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'UNKNOWN'
export interface ConfigDeployment {
  id: string; requestId: string; revisionNumber: number; status: DeploymentStatus
  requestedByUserId: string; createdAt: string; startedAt: string | null; finishedAt: string | null
  expectedRemoteSha256: string; desiredSha256: string; errorCode: string | null
}
export interface ManagedConfigProfile {
  bindingId: string
  profile: { id: string; code: string; name: string; description: string | null;
    kind: 'REMNAWAVE_CONFIG'; latestRevisionNumber: number }
  latestSha256: string; remoteSha256: string | null; latestDeployedSha256: string | null
  status: ConfigStatus; nodesUsingProfile: number; latestDeployment: ConfigDeployment | null
}
export interface ConfigRevisionSummary {
  revisionNumber: number; createdBy: string; createdAt: string
}
export interface ConfigRevisionContent {
  revisionNumber: number; sha256: string; config: Record<string, unknown>
}
export interface ConfigPreview {
  revisionNumber: number; localSha256: string; remoteSha256: string; remoteUpdatedAt: string | null
  changed: boolean; diff: { text: string; truncated: boolean; approximate: boolean;
    addedLines: number; removedLines: number }
}

const base = (org: string, integration: string, object: string) =>
  `/api/v1/organizations/${encodeURIComponent(org)}/integrations/${encodeURIComponent(integration)}` +
  `/inventory/objects/${encodeURIComponent(object)}/config-management`
const key = (org: string, integration: string, object: string) =>
  ['integration-config-profile', org, integration, object] as const

export async function getManagedConfigProfile(org: string, integration: string, object: string): Promise<ManagedConfigProfile | null> {
  try { return await requestJson<ManagedConfigProfile>(base(org, integration, object)) }
  catch (error) {
    if (error instanceof ApiError && error.status === 404) return null
    throw error
  }
}

export function useManagedConfigProfile(org: string, integration: string, object: string, enabled: boolean) {
  return useQuery({ queryKey: key(org, integration, object), enabled,
    queryFn: () => getManagedConfigProfile(org, integration, object),
    refetchInterval: query => query.state.data?.latestDeployment?.status === 'QUEUED' ||
      query.state.data?.latestDeployment?.status === 'RUNNING' ? 1500 :
      query.state.data?.status === 'WAITING_REFRESH' ? 3000 : false })
}

function useRefresh(org: string, integration: string, object: string) {
  const client = useQueryClient()
  return () => Promise.all([
    client.invalidateQueries({ queryKey: key(org, integration, object) }),
    client.invalidateQueries({ queryKey: ['integration-inventory', org, integration] }),
  ])
}

export function useAdoptConfigProfile(org: string, integration: string, object: string) {
  const refresh = useRefresh(org, integration, object)
  return useMutation({ mutationFn: (value: { code: string; name: string; description: string | null }) =>
    requestJson<{ profileId: string }>(`${base(org, integration, object)}/adopt`,
      { method: 'POST', body: JSON.stringify(value) }), onSuccess: refresh })
}

export function useConfigRevisions(org: string, integration: string, object: string, enabled: boolean) {
  return useQuery({ queryKey: [...key(org, integration, object), 'revisions'], enabled,
    queryFn: () => requestJson<{ items: ConfigRevisionSummary[] }>(`${base(org, integration, object)}/revisions?limit=100`)
      .then(value => value.items) })
}

/** Deliberately not a React Query: decrypted JSON has only component lifetime. */
export function getConfigRevision(org: string, integration: string, object: string, revision: number) {
  return requestJson<ConfigRevisionContent>(`${base(org, integration, object)}/revisions/${revision}`)
}

export function useCreateConfigRevision(org: string, integration: string, object: string) {
  const refresh = useRefresh(org, integration, object)
  const client = useQueryClient()
  return useMutation({ mutationFn: (config: Record<string, unknown>) =>
    requestJson<{ revisionNumber: number }>(`${base(org, integration, object)}/revisions`,
      { method: 'POST', body: JSON.stringify({ config }) }),
  onSuccess: async () => { await refresh(); await client.invalidateQueries({ queryKey: [...key(org, integration, object), 'revisions'] }) } })
}

export function previewConfigRevision(org: string, integration: string, object: string, revision: number) {
  return requestJson<ConfigPreview>(`${base(org, integration, object)}/revisions/${revision}/preview`, { method: 'POST' })
}

export function useDeployConfigRevision(org: string, integration: string, object: string) {
  const refresh = useRefresh(org, integration, object)
  const client = useQueryClient()
  return useMutation({ mutationFn: ({ revision, requestId }: { revision: number; requestId: string }) =>
    requestJson<ConfigDeployment>(`${base(org, integration, object)}/revisions/${revision}/deploy`,
      { method: 'POST', body: JSON.stringify({ requestId }) }),
  onSuccess: async () => { await refresh(); await client.invalidateQueries({ queryKey: [...key(org, integration, object), 'deployments'] }) } })
}

export function useConfigDeployments(org: string, integration: string, object: string, enabled: boolean) {
  return useQuery({ queryKey: [...key(org, integration, object), 'deployments'], enabled,
    queryFn: () => requestJson<{ items: ConfigDeployment[] }>(`${base(org, integration, object)}/deployments?limit=50`)
      .then(value => value.items),
    refetchInterval: query => query.state.data?.some(value => value.status === 'QUEUED' || value.status === 'RUNNING')
      ? 1500 : false })
}
