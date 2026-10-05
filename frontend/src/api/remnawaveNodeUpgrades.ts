import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { requestJson } from './httpClient'

export interface NodeRelease {
  releaseId: string; nodeVersion: string; imageRepository: string; manifestDigest: string; releasedAt: string; status: string
  minimumPanelVersion: string; maximumPanelVersionExclusive: string
  platforms: { platform: string; manifestDigest: string; configDigest: string }[]
  compatibility: { state: string; reasons: string[] }
}
export interface NodeImageStatus {
  panel: { serverVersion: string | null; apiGeneration: string | null; sourceCommit: string | null }
  releases: NodeRelease[]
  target: { id: string; number: number; release: NodeRelease } | null
  active: NodeUpgradeRun | null
  members: { membershipId: string; nodeName: string; status: string; releaseId: string | null; reportedVersion: string | null
    observation: { platform: string | null; actualImageId: string | null; configuredImage: string | null; managedFiles: boolean } | null }[]
}
export interface NodeUpgradeRun {
  id: string; state: string; phase: string; targetVersion: string; currentWave: number; waveCount: number
  failureCode: string | null; pauseReason: string | null; pauseRequested: boolean; rollbackRequested: boolean
  rollbackIncomplete: boolean; createdAt: string; expiresAt: string; updatedAt: string
}
export interface NodeUpgradePlan {
  target: NodeRelease; waveCount: number; automaticRollback: boolean
  members: { membershipId: string; nodeName: string; wave: number; skipped: boolean
    previousImageReference: string | null; baseline: { actualImageId: string | null; platform: string | null } }[]
}
export interface NodeUpgradePreview { status: string; issues: string[]; planId: string | null; plan: NodeUpgradePlan | null; expiresAt: string | null }
export interface NodeUpgradeDetail extends NodeUpgradeRun {
  snapshot: NodeUpgradePlan
  members: { id: string; membershipId: string; wave: number; position: number; state: string; failureCode: string | null
    localVerifiedAt: string | null; panelVerifiedAt: string | null }[]
  actions: { id: string; memberId: string; kind: string; rollback: boolean; state: string; targetReference: string; failureCode: string | null }[]
}
export const activeNodeUpgrade = (state: string) => ['QUEUED', 'RUNNING', 'PAUSED', 'ROLLING_BACK'].includes(state)
const base = (org: string, integration: string, fleet: string) =>
  `/api/v1/organizations/${encodeURIComponent(org)}/integrations/${encodeURIComponent(integration)}/remnawave-fleets/${encodeURIComponent(fleet)}`
export function useNodeImageStatus(org: string, integration: string, fleet: string) {
  return useQuery({ queryKey: ['nodeImageStatus', org, integration, fleet],
    queryFn: () => requestJson<NodeImageStatus>(`${base(org, integration, fleet)}/node-releases`), refetchInterval: 10000 })
}
export function useNodeUpgrades(org: string, integration: string, fleet: string) {
  return useQuery({ queryKey: ['nodeUpgrades', org, integration, fleet],
    queryFn: () => requestJson<{ items: NodeUpgradeRun[] }>(`${base(org, integration, fleet)}/node-upgrades`), refetchInterval: 5000 })
}
export function useNodeUpgrade(org: string, integration: string, fleet: string, id: string | null) {
  return useQuery({ queryKey: ['nodeUpgrade', org, integration, fleet, id], enabled: id !== null,
    queryFn: () => requestJson<NodeUpgradeDetail>(`${base(org, integration, fleet)}/node-upgrades/${encodeURIComponent(id!)}`),
    refetchInterval: query => query.state.data && activeNodeUpgrade(query.state.data.state) ? 3000 : false })
}
export function useNodeUpgradeActions(org: string, integration: string, fleet: string) {
  const client = useQueryClient()
  const refresh = () => { for (const key of ['nodeImageStatus', 'nodeUpgrades', 'nodeUpgrade', 'remnawaveFleet'])
    void client.invalidateQueries({ queryKey: [key, org, integration] }) }
  const post = <T,>(suffix: string, body: unknown) => requestJson<T>(`${base(org, integration, fleet)}/${suffix}`,
    { method: 'POST', body: JSON.stringify(body) })
  return {
    select: useMutation({ mutationFn: (releaseId: string) => post('node-release-target', { releaseId }), onSuccess: refresh }),
    preview: useMutation({ mutationFn: (input: { releaseRevisionId: string; canaryMemberIds: string[]; waveSize: number
      automaticRollback: boolean; pauseAfterCanary: boolean }) => post<NodeUpgradePreview>('node-upgrades/preview', input) }),
    start: useMutation({ mutationFn: (input: { planId: string; requestId: string }) => post<NodeUpgradeRun>('node-upgrades', input), onSuccess: refresh }),
    control: useMutation({ mutationFn: (input: { id: string; command: 'pause' | 'resume' | 'rollback'; scope?: string }) =>
      post<NodeUpgradeRun>(`node-upgrades/${encodeURIComponent(input.id)}/${input.command}`, input.command === 'rollback'
        ? { scope: input.scope ?? 'CURRENT_WAVE' } : {}), onSuccess: refresh }),
  }
}
