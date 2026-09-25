import { useI18n } from '../../i18n'
import type { IncidentListItemResponse } from '../../types/incident'
import { IncidentRow } from './IncidentRow'

/**
 * Incidents in the order the backend returns them, newest first, each with the resource it is
 * about. Everything a row shows comes from this one list response: no resource is loaded per row.
 */
export function IncidentList({ organizationId, incidents, now }: {
  organizationId: string
  incidents: readonly IncidentListItemResponse[]
  /** One clock for the list, so an ongoing duration moves without a timer per row. */
  now: number
}) {
  const { t } = useI18n()
  return <ol className="incident-list" aria-label={t.incidents.listLabel}>
    {incidents.map(incident => <IncidentRow key={incident.id} organizationId={organizationId} incident={incident} now={now} />)}
  </ol>
}
