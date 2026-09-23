import { Link, useLocation, useNavigate } from 'react-router-dom'

import type { ConnectionResponse } from '../../types/connection'
import type { EnvironmentResponse, ProjectResponse } from '../../types/navigation'
import { connectionScopeDisplayLabel } from './connectionContextPresentation'
import { ConnectionStatusBadge } from './ConnectionStatusBadge'
import { getConnectorTypeLabel, getLastSyncSummary,
  formatConnectionDateTime } from './connectionPresentation'
import { SyncStatusBadge } from './SyncStatusBadge'

export function ConnectionRow({ organizationId, connection, projects, environments }: {
  organizationId: string
  connection: ConnectionResponse
  projects: readonly ProjectResponse[] | undefined
  environments: readonly EnvironmentResponse[]
}) {
  const navigate = useNavigate()
  const location = useLocation()
  const destination = `/organizations/${encodeURIComponent(organizationId)}/connections/${encodeURIComponent(connection.id)}${location.search}`
  return <tr className="clickable-row" onClick={() => navigate(destination)}>
    <td><Link className="grid-link" to={destination}>{connection.name}</Link><small className="cell-secondary">{connection.code}</small></td>
    <td>{getConnectorTypeLabel(connection.connectorType)}</td>
    <td>{connectionScopeDisplayLabel(connection.scope, projects, environments)}</td>
    <td><ConnectionStatusBadge active={connection.active} /></td>
    <td>{connection.lastSync ? <><SyncStatusBadge status={connection.lastSync.status} />
      <small className="cell-secondary">{getLastSyncSummary(connection.lastSync)}</small></> : <span className="muted-cell">Never</span>}</td>
    <td>{connection.schedule?.enabled ? formatConnectionDateTime(connection.schedule.nextRunAt) : <span className="muted-cell">Manual</span>}</td>
  </tr>
}
