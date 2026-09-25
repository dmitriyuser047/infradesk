import { useI18n } from '../../i18n'
import type { IncidentListItemResponse } from '../../types/incident'
import { IncidentRow } from './IncidentRow'

/**
 * Incidents with the resource each one is about. The list response already names every resource,
 * so the list renders from that one response and never loads a resource per row.
 * Open incidents come first.
 */
export function IncidentList({
  organizationId,
  incidents,
}: {
  organizationId: string
  incidents: readonly IncidentListItemResponse[]
}) {
  const { t } = useI18n()
  const columns = t.incidents.columns
  const ordered = [...incidents].sort((left, right) =>
    Number(left.resolvedAt !== null) - Number(right.resolvedAt !== null) || right.openedAt.localeCompare(left.openedAt))

  return (
    <div className="table-scroll"><table className="data-grid">
      <thead><tr><th>{columns.status}</th><th>{columns.reason}</th><th>{columns.resource}</th>
        <th>{columns.opened}</th><th>{columns.duration}</th></tr></thead>
      <tbody>
      {ordered.map((incident) => (
        <IncidentRow key={incident.id} organizationId={organizationId} incident={incident} />
      ))}
      </tbody>
    </table></div>
  )
}
