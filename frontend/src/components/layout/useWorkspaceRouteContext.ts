import { useParams, useSearchParams } from 'react-router-dom'

import { useConnection } from '../../api/connections'
import { useIncident } from '../../api/incidents'

export function useWorkspaceRouteContext() {
  const { organizationId, projectId: routeProjectId, environmentId: routeEnvironmentId,
    connectionId, incidentId } = useParams()
  const [searchParams] = useSearchParams()
  const connection = useConnection(organizationId, connectionId)
  const incident = useIncident(organizationId, incidentId)
  const scope = connection.data?.scope
  const scopeProjectId = scope && scope.type !== 'ORGANIZATION' ? scope.projectId : undefined
  const scopeEnvironmentId = scope?.type === 'ENVIRONMENT' ? scope.environmentId : undefined

  return {
    organizationId,
    // An incident carries the project and environment of its resource: no further read.
    projectId: routeProjectId ?? searchParams.get('project') ?? scopeProjectId ?? incident.data?.project.id,
    environmentId: routeEnvironmentId ?? searchParams.get('environment') ??
      scopeEnvironmentId ?? incident.data?.environment.id,
  }
}
