import { Link, useLocation, useNavigate } from 'react-router-dom'

import { useI18n } from '../../i18n'
import type { ConnectionResponse } from '../../types/connection'
import type { EnvironmentResponse, ProjectResponse } from '../../types/navigation'
import { StatusIndicator } from '../layout/WorkspacePrimitives'
import { connectionScopeDisplayLabel } from './connectionContextPresentation'
import { getLastSyncSummary, getScheduleSummary } from './connectionPresentation'
import { SyncStatusBadge } from './SyncStatusBadge'

/** One connection: where it points, whether that server is trusted, and how its syncs go. */
export function ConnectionRow({ organizationId, connection, projects, environments }: {
  organizationId: string
  connection: ConnectionResponse
  projects: readonly ProjectResponse[] | undefined
  environments: readonly EnvironmentResponse[]
}) {
  const i18n = useI18n()
  const t = i18n.t.connections
  const navigate = useNavigate()
  const location = useLocation()
  const destination = `/organizations/${encodeURIComponent(organizationId)}/connections/${encodeURIComponent(connection.id)}${location.search}`
  const ssh = connection.ssh
  return <tr className={`clickable-row ${connection.active ? '' : 'row-quiet'}`} onClick={() => navigate(destination)}>
    <td><Link className="grid-link" to={destination}>{connection.name}</Link>
      <small className="cell-secondary">{connectionScopeDisplayLabel(connection.scope, projects, environments, i18n)}
        {connection.active ? null : ` · ${i18n.t.common.inactive}`}</small></td>
    <td>{ssh ? <code className="technical-value">{ssh.username}@{ssh.host}{ssh.port === 22 ? '' : `:${ssh.port}`}</code>
      : <span className="muted-cell">{t.connectorTypes[connection.connectorType] ?? connection.connectorType}</span>}</td>
    <td>{ssh ? <StatusIndicator label={ssh.hostTrusted ? t.trust.trusted : t.trust.untrusted}
      tone={ssh.hostTrusted ? 'success' : 'warning'} /> : <span className="muted-cell">—</span>}</td>
    <td>{connection.lastSync ? <><SyncStatusBadge status={connection.lastSync.status} />
      <small className="cell-secondary">{getLastSyncSummary(connection.lastSync, i18n)}</small></>
      : <span className="muted-cell">{t.neverSynchronized}</span>}</td>
    <td>{connection.schedule?.enabled
      ? <><span>{i18n.format.dateTime(connection.schedule.nextRunAt)}</span><small className="cell-secondary">{getScheduleSummary(connection.schedule, i18n)}</small></>
      : <span className="muted-cell">{i18n.t.common.manual}</span>}</td>
  </tr>
}
