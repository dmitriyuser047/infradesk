import { useI18n } from '../../i18n'
import type { IncidentResponse } from '../../types/incident'
import { IncidentRow } from './IncidentRow'

export function IncidentList({
  organizationId,
  incidents,
}: {
  organizationId: string
  incidents: readonly IncidentResponse[]
}) {
  const { t } = useI18n()
  const columns = t.incidents.columns
  return (
    <div className="table-scroll"><table className="data-grid">
      <thead><tr><th>{columns.status}</th><th>{columns.resource}</th><th>{columns.reason}</th>
        <th>{columns.opened}</th><th>{columns.duration}</th></tr></thead>
      <tbody>
      {incidents.map((incident) => (
        <IncidentRow key={incident.id} organizationId={organizationId} incident={incident} />
      ))}
      </tbody>
    </table></div>
  )
}
