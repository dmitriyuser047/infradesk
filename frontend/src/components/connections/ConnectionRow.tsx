import { Boxes, Plug, Server } from 'lucide-react'
import { Link } from 'react-router-dom'

import type { ConnectionResponse } from '../../types/connection'
import { ConnectionStatusBadge } from './ConnectionStatusBadge'
import {
  getConnectionScopeLabel,
  getConnectorTypeLabel,
  getLastSyncSummary,
  getScheduleSummary,
} from './connectionPresentation'
import { SyncStatusBadge } from './SyncStatusBadge'

export function ConnectionRow({
  organizationId,
  connection,
}: {
  organizationId: string
  connection: ConnectionResponse
}) {
  const destination = `/organizations/${organizationId}/connections/${connection.id}`

  return (
    <Link className="connection-row" to={destination}>
      <div className="connection-type">
        <ConnectionIcon connectorType={connection.connectorType} />
        <span>{getConnectorTypeLabel(connection.connectorType)}</span>
      </div>
      <div className="connection-row-main">
        <strong>{connection.name}</strong>
        <span>{connection.code}</span>
      </div>
      <div className="connection-row-scope">
        <span>{getConnectionScopeLabel(connection.scope)}</span>
        <ConnectionStatusBadge active={connection.active} />
      </div>
      <div className="connection-row-sync">
        {connection.lastSync === null ? <span className="connection-muted">Never synchronized</span> : <SyncStatusBadge status={connection.lastSync.status} />}
        <span>{getLastSyncSummary(connection.lastSync)}</span>
      </div>
      <div className="connection-row-schedule">
        <span>Schedule</span>
        <strong>{getScheduleSummary(connection.schedule)}</strong>
      </div>
    </Link>
  )
}

function ConnectionIcon({ connectorType }: { connectorType: string }) {
  if (connectorType === 'SSH') {
    return <Server aria-hidden size={18} />
  }
  if (connectorType === 'DOCKER') {
    return <Boxes aria-hidden size={18} />
  }
  return <Plug aria-hidden size={18} />
}
