import type { ConnectionResponse } from '../../types/connection'

export function filterConnections(connections: readonly ConnectionResponse[], search: string,
  type: string, status: string): ConnectionResponse[] {
  const query = search.trim().toLocaleLowerCase()
  return connections.filter(connection =>
    (!query || `${connection.name} ${connection.code}`.toLocaleLowerCase().includes(query)) &&
    (type === 'ALL' || connection.connectorType === type) &&
    (status === 'ALL' || (status === 'ACTIVE' ? connection.active : !connection.active)))
}
