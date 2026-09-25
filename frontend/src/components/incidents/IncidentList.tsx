import { useQueries } from '@tanstack/react-query'

import { getResource } from '../../api/resources'
import { useI18n } from '../../i18n'
import type { IncidentResponse } from '../../types/incident'
import type { ResourceResponse } from '../../types/resource'
import { IncidentRow } from './IncidentRow'

/**
 * Incidents with the name of the resource each one is about.
 *
 * The incident carries only the resource id; its name comes from the resource endpoint the
 * resource page already uses, one cached request per distinct resource. Open incidents come first.
 */
export function IncidentList({
  organizationId,
  incidents,
}: {
  organizationId: string
  incidents: readonly IncidentResponse[]
}) {
  const { t } = useI18n()
  const columns = t.incidents.columns
  const resourceIds = [...new Set(incidents.map(incident => incident.resourceId))]
  const resources = useQueries({ queries: resourceIds.map(resourceId => ({
    queryKey: ['resource', organizationId, resourceId],
    queryFn: () => getResource(organizationId, resourceId),
    staleTime: 60_000,
  })) })
  const byId = new Map<string, ResourceResponse>()
  resources.forEach(query => { if (query.data) byId.set(query.data.id, query.data) })
  const ordered = [...incidents].sort((left, right) =>
    Number(left.resolvedAt !== null) - Number(right.resolvedAt !== null) || right.openedAt.localeCompare(left.openedAt))

  return (
    <div className="table-scroll"><table className="data-grid">
      <thead><tr><th>{columns.status}</th><th>{columns.reason}</th><th>{columns.resource}</th>
        <th>{columns.opened}</th><th>{columns.duration}</th></tr></thead>
      <tbody>
      {ordered.map((incident) => (
        <IncidentRow key={incident.id} organizationId={organizationId} incident={incident}
          resource={byId.get(incident.resourceId)} />
      ))}
      </tbody>
    </table></div>
  )
}
