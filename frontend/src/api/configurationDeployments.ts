import { useMutation, useQuery } from '@tanstack/react-query'

import { requestJson } from './httpClient'
import type { DeploymentDetail, DeploymentExecution, DeploymentPreview } from '../types/configurationDeployment'

const base = (organizationId: string) => `/api/v1/organizations/${encodeURIComponent(organizationId)}`
const assignment = (organizationId: string, id: string) =>
  `${base(organizationId)}/configuration-assignments/${encodeURIComponent(id)}`

export function useDeploymentPreview(organizationId: string, assignmentId: string) {
  return useMutation({
    mutationFn: (body: { expectedAssignmentVersion: number; connectionId: string }) =>
      requestJson<DeploymentPreview>(`${assignment(organizationId, assignmentId)}/deployment-preview`,
        { method: 'POST', body: JSON.stringify(body) }),
  })
}

export function useCreateDeployment(organizationId: string, assignmentId: string) {
  return useMutation({
    mutationFn: (body: { expectedAssignmentVersion: number; connectionId: string; expectedRemoteSha256: string | null;
      expectedRemoteMissing: boolean; requestId: string; execution: DeploymentExecution }) =>
      requestJson<{ deploymentId: string; state: 'QUEUED' }>(`${assignment(organizationId, assignmentId)}/deployments`,
        { method: 'POST', body: JSON.stringify(body) }),
  })
}

export function useDeployment(organizationId: string, id: string | null) {
  return useQuery({
    queryKey: ['configurationDeployment', organizationId, id],
    queryFn: () => requestJson<DeploymentDetail>(`${base(organizationId)}/configuration-deployments/${encodeURIComponent(id!)}`),
    enabled: id !== null,
    refetchInterval: query => query.state.data && ['QUEUED', 'RUNNING'].includes(query.state.data.state) ? 2000 : false,
  })
}

export function useDeploymentHistory(organizationId: string, profileId: string | null) {
  return useQuery({
    queryKey: ['configurationDeploymentHistory', organizationId, profileId],
    queryFn: () => {
      const params = new URLSearchParams({ limit: '50' })
      if (profileId) params.set('profileId', profileId)
      return requestJson<DeploymentDetail[]>(`${base(organizationId)}/configuration-deployments?${params}`)
    },
  })
}
