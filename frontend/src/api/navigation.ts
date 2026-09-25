import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { requestJson } from './httpClient'
import type { CreateEnvironmentRequest, CreateProjectRequest, EnvironmentContextResponse, EnvironmentResponse, OrganizationResponse, ProjectResponse } from '../types/navigation'

const organizationPath = (organizationId: string) =>
  `/api/v1/organizations/${encodeURIComponent(organizationId)}`

export function getOrganization(organizationId: string): Promise<OrganizationResponse> {
  return requestJson<OrganizationResponse>(organizationPath(organizationId))
}

export function getProjects(organizationId: string): Promise<ProjectResponse[]> {
  return requestJson<ProjectResponse[]>(`${organizationPath(organizationId)}/projects`)
}

export function getEnvironments(
  organizationId: string,
  projectId: string,
): Promise<EnvironmentResponse[]> {
  return requestJson<EnvironmentResponse[]>(
    `${organizationPath(organizationId)}/projects/${encodeURIComponent(projectId)}/environments`,
  )
}

export function getEnvironmentContext(
  organizationId: string,
  environmentId: string,
): Promise<EnvironmentContextResponse> {
  return requestJson<EnvironmentContextResponse>(
    `${organizationPath(organizationId)}/environments/${encodeURIComponent(environmentId)}/context`,
  )
}

export function createProject(organizationId: string, body: CreateProjectRequest): Promise<ProjectResponse> {
  return requestJson<ProjectResponse>(`${organizationPath(organizationId)}/projects`, {
    method: 'POST', body: JSON.stringify(body),
  })
}

export function createEnvironment(organizationId: string, projectId: string,
  body: CreateEnvironmentRequest): Promise<EnvironmentResponse> {
  return requestJson<EnvironmentResponse>(
    `${organizationPath(organizationId)}/projects/${encodeURIComponent(projectId)}/environments`,
    { method: 'POST', body: JSON.stringify(body) },
  )
}

export function useCreateProject(organizationId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: CreateProjectRequest) => createProject(organizationId, body),
    onSuccess: () => { void queryClient.invalidateQueries({ queryKey: ['projects', organizationId] }) },
  })
}

export function useCreateEnvironment(organizationId: string, projectId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: CreateEnvironmentRequest) => createEnvironment(organizationId, projectId, body),
    onSuccess: () => { void queryClient.invalidateQueries({ queryKey: ['environments', organizationId, projectId] }) },
  })
}

export function useOrganization(organizationId: string) {
  return useQuery({
    queryKey: ['organization', organizationId],
    queryFn: () => getOrganization(organizationId),
  })
}

export function useProjects(organizationId: string) {
  return useQuery({
    queryKey: ['projects', organizationId],
    queryFn: () => getProjects(organizationId),
    // The shell renders outside any organization too (the organization list); nothing to load there.
    enabled: organizationId !== '',
  })
}

export function useEnvironments(organizationId: string, projectId: string | null) {
  return useQuery({
    queryKey: ['environments', organizationId, projectId],
    queryFn: () => getEnvironments(organizationId, requireProjectId(projectId)),
    enabled: projectId !== null,
  })
}

export function useEnvironmentContext(
  organizationId: string,
  environmentId: string | null,
  enabled: boolean,
) {
  return useQuery({
    queryKey: ['environment-context', organizationId, environmentId],
    queryFn: () => getEnvironmentContext(organizationId, requireEnvironmentId(environmentId)),
    enabled: enabled && environmentId !== null,
  })
}

function requireProjectId(projectId: string | null): string {
  if (projectId === null || projectId.length === 0) {
    throw new Error('Missing project identifier')
  }
  return projectId
}

function requireEnvironmentId(environmentId: string | null): string {
  if (environmentId === null || environmentId.length === 0) {
    throw new Error('Missing environment identifier')
  }
  return environmentId
}
