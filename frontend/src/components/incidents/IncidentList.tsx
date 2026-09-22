import type { IncidentResponse } from '../../types/incident'
import { IncidentRow } from './IncidentRow'

export function IncidentList({
  organizationId,
  incidents,
}: {
  organizationId: string
  incidents: readonly IncidentResponse[]
}) {
  return (
    <div className="incident-list" role="list">
      {incidents.map((incident) => (
        <IncidentRow key={incident.id} organizationId={organizationId} incident={incident} />
      ))}
    </div>
  )
}
