import { useI18n } from '../../i18n'
import type { ConnectionResponse } from '../../types/connection'
import type { EnvironmentResponse, ProjectResponse } from '../../types/navigation'
import { ConnectionRow } from './ConnectionRow'

export function ConnectionList({
  organizationId,
  connections,
  projects,
  environments,
}: {
  organizationId: string
  connections: ConnectionResponse[]
  projects: readonly ProjectResponse[] | undefined
  environments: readonly EnvironmentResponse[]
}) {
  const { t } = useI18n()
  const columns = t.connections.columns
  return (
    <div className="table-scroll"><table className="data-grid">
      <thead><tr><th>{columns.name}</th><th>{columns.host}</th><th>{columns.trust}</th>
        <th>{columns.lastSync}</th><th>{columns.nextRun}</th></tr></thead>
      <tbody>
      {connections.map((connection) => (
        <ConnectionRow key={connection.id} organizationId={organizationId} connection={connection}
          projects={projects} environments={environments} />
      ))}
      </tbody>
    </table></div>
  )
}
