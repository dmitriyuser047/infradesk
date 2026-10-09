import { useOrganizationPermissions } from '../auth/authorization'
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
  const permissions = useOrganizationPermissions(organizationId)
  const canManage = permissions.can('manageConnections')
  const columns = t.connections.columns
  return (
    <div className="table-scroll"><table className="data-grid connection-grid">
      <thead><tr><th scope="col">{columns.name}</th><th scope="col">{columns.host}</th><th scope="col">{columns.trust}</th>
        <th scope="col">{columns.resources}</th><th scope="col">{columns.incidents}</th><th scope="col">{columns.lastSync}</th><th scope="col">{columns.nextRun}{canManage ? <span className="visually-hidden"> · {t.workScreens.actions}</span> : null}</th></tr></thead>
      <tbody>
      {connections.map((connection) => (
        <ConnectionRow key={connection.id} organizationId={organizationId} connection={connection}
          projects={projects} environments={environments} counts={counts?.get(connection.id)} countsKnown={counts !== undefined} canManage={canManage} />
      ))}
      </tbody>
    </table></div>
  )
}
