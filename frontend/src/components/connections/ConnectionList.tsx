import { useI18n } from '../../i18n'
import type { ConnectionResponse } from '../../types/connection'
import type { ConnectionInfrastructureCounts } from '../../types/infrastructure'
import type { EnvironmentResponse, ProjectResponse } from '../../types/navigation'
import { ConnectionRow } from './ConnectionRow'

export function ConnectionList({
  organizationId,
  connections,
  projects,
  environments,
  counts,
}: {
  organizationId: string
  connections: ConnectionResponse[]
  projects: readonly ProjectResponse[] | undefined
  environments: readonly EnvironmentResponse[]
  counts?: ReadonlyMap<string, ConnectionInfrastructureCounts>
}) {
  const { t } = useI18n()
  const columns = t.connections.columns
  return (
    <div className="table-scroll"><table className="data-grid">
      <thead><tr><th>{columns.name}</th><th>{columns.host}</th><th>{columns.trust}</th>
        <th>{columns.resources}</th><th>{columns.incidents}</th><th>{columns.lastSync}</th><th>{columns.nextRun}</th></tr></thead>
      <tbody>
      {connections.map((connection) => (
        <ConnectionRow key={connection.id} organizationId={organizationId} connection={connection}
          projects={projects} environments={environments} counts={counts?.get(connection.id)} countsKnown={counts !== undefined} />
      ))}
      </tbody>
    </table></div>
  )
}
