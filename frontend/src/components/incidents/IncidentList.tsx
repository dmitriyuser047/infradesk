import { useI18n } from '../../i18n'
import type { IncidentListItemResponse } from '../../types/incident'
import { IncidentRow, type IncidentRowOptions } from './IncidentRow'

/**
 * Incidents in the order the backend returns them, newest first, each with the resource it is
 * about and where that resource lives. Everything a row shows comes from this one list response:
 * nothing is loaded per row.
 */
export function IncidentList({ organizationId, incidents, now, label, options }: {
  organizationId: string
  incidents: readonly IncidentListItemResponse[]
  /** One clock for the list, so an ongoing duration moves without a timer per row. */
  now: number
  label?: string
  options?: IncidentRowOptions
}) {
  const { t } = useI18n()
  return <ol className="incident-list" aria-label={label ?? t.incidents.listLabel}>
    {incidents.map(incident => <IncidentRow key={incident.id} organizationId={organizationId} incident={incident}
      now={now} options={options} />)}
  </ol>
}
