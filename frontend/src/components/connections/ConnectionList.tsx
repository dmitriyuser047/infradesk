import type { ConnectionResponse } from '../../types/connection'
import { ConnectionRow } from './ConnectionRow'

export function ConnectionList({
  organizationId,
  connections,
}: {
  organizationId: string
  connections: ConnectionResponse[]
}) {
  return (
    <div className="connection-list">
      {connections.map((connection) => (
        <ConnectionRow key={connection.id} organizationId={organizationId} connection={connection} />
      ))}
    </div>
  )
}
