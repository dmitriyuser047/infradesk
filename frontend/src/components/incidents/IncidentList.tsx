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
    <div className="table-scroll"><table className="data-grid">
      <thead><tr><th>Status</th><th>Resource</th><th>Reason</th><th>Opened</th><th>Resolved</th></tr></thead>
      <tbody>
      {incidents.map((incident) => (
        <IncidentRow key={incident.id} organizationId={organizationId} incident={incident} />
      ))}
      </tbody>
    </table></div>
  )
}
