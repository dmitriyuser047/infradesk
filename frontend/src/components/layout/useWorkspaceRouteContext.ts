import { useEffect } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom'

import { useConnection } from '../../api/connections'
import { useIncident } from '../../api/incidents'
import { ApiError, requestJson } from '../../api/httpClient'
import { useEnvironmentContext, useEnvironments, useProjects } from '../../api/navigation'
import { activeWorkspaceModule, modulePath, type WorkspaceScope } from './workspaceNavigation'
import type { ResourceContextResponse } from '../../types/infrastructure'

export function useWorkspaceRouteContext(fallback?: WorkspaceScope) {
  const { organizationId: routeOrganizationId, projectId: routeProjectId, environmentId: routeEnvironmentId,
    connectionId, incidentId, resourceId } = useParams()
  const organizationId = routeOrganizationId ?? fallback?.organizationId
  const [searchParams] = useSearchParams()
  const location = useLocation()
  const navigate = useNavigate()
  const connection = useConnection(organizationId, connectionId)
  const incident = useIncident(organizationId, incidentId)
  // Observe the detail page's existing context read; do not add another request.
  const resourceContext = useQuery<ResourceContextResponse>({
    queryKey: ['resource-context', organizationId, resourceId], enabled: false,
    queryFn: () => requestJson<ResourceContextResponse>(
      `/api/v1/organizations/${encodeURIComponent(organizationId ?? '')}/resources/${encodeURIComponent(resourceId ?? '')}/context`),
  })
  const selectedProjectId = routeProjectId ?? (routeOrganizationId ? searchParams.get('project') || undefined : fallback?.projectId ?? undefined)
  const selectedEnvironmentId = routeEnvironmentId ?? (routeOrganizationId ? searchParams.get('environment') || undefined : fallback?.environmentId ?? undefined)
  const explicitScope = Boolean(selectedProjectId || selectedEnvironmentId)
  const environmentContext = useEnvironmentContext(organizationId ?? '', selectedEnvironmentId ?? null,
    Boolean(organizationId && selectedEnvironmentId && !selectedProjectId && !resourceId))
  const scope = connection.data?.scope
  const scopeProjectId = scope && scope.type !== 'ORGANIZATION' ? scope.projectId : undefined
  const scopeEnvironmentId = scope?.type === 'ENVIRONMENT' ? scope.environmentId : undefined

  // An explicit project-wide selection must not inherit the object's environment.
  const projectId = explicitScope
    ? selectedProjectId ?? resourceContext.data?.project.id ?? environmentContext.data?.project.id
    : scopeProjectId ?? incident.data?.project.id
  const environmentId = projectId ? (explicitScope ? selectedEnvironmentId
    : scopeEnvironmentId ?? incident.data?.environment.id) : undefined
  const projects = useProjects(organizationId ?? '')
  const environments = useEnvironments(organizationId ?? '', projectId ?? null)
  const denied = (error: unknown) => error instanceof ApiError && [403, 404].includes(error.status)
  // Only authoritative absence or access denial resets scope; a network failure does not.
  const missingProject = explicitScope && Boolean(projectId) &&
    ((projects.isSuccess && !projects.isFetching && Array.isArray(projects.data) && !projects.data.some(item => item.id === projectId)) || denied(projects.error))
  const missingEnvironment = explicitScope && Boolean(selectedEnvironmentId) && (
    denied(environmentContext.error) || denied(environments.error) ||
    (Boolean(projectId) && environments.isSuccess && Array.isArray(environments.data) &&
      !environments.data.some(item => item.id === selectedEnvironmentId)))
  const safeProjectId = missingProject ? undefined : projectId
  const safeEnvironmentId = missingProject || missingEnvironment ? undefined : environmentId

  useEffect(() => {
    if (!routeOrganizationId || (!missingProject && !missingEnvironment)) return
    if (routeProjectId || routeEnvironmentId) {
      const target = modulePath(activeWorkspaceModule(location.pathname) ?? 'overview', {
        organizationId, projectId: safeProjectId, environmentId: safeEnvironmentId,
      })
      if (target) navigate(target, { replace: true })
    } else {
      const params = new URLSearchParams(location.search)
      if (missingProject) params.delete('project')
      params.delete('environment')
      navigate({ pathname: location.pathname, search: params.toString() }, { replace: true })
    }
  }, [routeOrganizationId, organizationId, missingProject, missingEnvironment, routeProjectId, routeEnvironmentId,
    safeProjectId, safeEnvironmentId, location.pathname, location.search, navigate])

  return { organizationId, projectId: safeProjectId, environmentId: safeEnvironmentId }
}
