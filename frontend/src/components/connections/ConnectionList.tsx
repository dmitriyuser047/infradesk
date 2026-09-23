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
  return (
    <div className="table-scroll"><table className="data-grid">
      <thead><tr><th>Name</th><th>Type</th><th>Scope</th><th>Status</th><th>Last sync</th><th>Next run</th></tr></thead>
      <tbody>
      {connections.map((connection) => (
        <ConnectionRow key={connection.id} organizationId={organizationId} connection={connection}
          projects={projects} environments={environments} />
      ))}
      </tbody>
    </table></div>
  )
}
