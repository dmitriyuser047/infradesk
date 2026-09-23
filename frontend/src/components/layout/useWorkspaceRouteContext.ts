import { useParams, useSearchParams } from 'react-router-dom'

import { useConnection } from '../../api/connections'
import { useIncident } from '../../api/incidents'
import { useResource } from '../../api/resources'

export function useWorkspaceRouteContext() {
  const { organizationId, projectId: routeProjectId, environmentId: routeEnvironmentId,
    connectionId, incidentId } = useParams()
  const [searchParams] = useSearchParams()
  const connection = useConnection(organizationId, connectionId)
  const incident = useIncident(organizationId, incidentId)
  const incidentResource = useResource(organizationId, incident.data?.resourceId)
  const scope = connection.data?.scope
  const scopeProjectId = scope && scope.type !== 'ORGANIZATION' ? scope.projectId : undefined
  const scopeEnvironmentId = scope?.type === 'ENVIRONMENT' ? scope.environmentId : undefined

  return {
    organizationId,
    projectId: routeProjectId ?? searchParams.get('project') ?? scopeProjectId,
    environmentId: routeEnvironmentId ?? searchParams.get('environment') ??
      scopeEnvironmentId ?? incidentResource.data?.environmentId,
  }
}
