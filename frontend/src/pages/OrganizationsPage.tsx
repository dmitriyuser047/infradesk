import { Link } from 'react-router-dom'

import { useMyOrganizations } from '../api/auth'
import { getOrganizationRoleLabel } from '../components/auth/authPresentation'
import { AppShell } from '../components/layout/AppShell'
import { EmptyWorkspaceState, WorkspaceHeader, WorkspaceSection } from '../components/layout/WorkspacePrimitives'

export function OrganizationsPage() {
  const organizations = useMyOrganizations()

  return (
    <AppShell>
      <div className="workspace-page">
        <WorkspaceHeader title="Organizations" subtitle="Choose an organization to open its workspace" />
        <WorkspaceSection title="Available organizations">
          {organizations.isPending ? <div className="context-skeleton" aria-label="Loading organizations"><span /><span /></div> : null}
          {organizations.isError ? (
            <div className="context-area-error" role="alert">
              <p>Unable to load organizations</p>
              <button className="retry-button" type="button" onClick={() => organizations.refetch()}>Retry</button>
            </div>
          ) : null}
          {organizations.isSuccess && organizations.data.length === 0 ? (
            <EmptyWorkspaceState title="No organizations available" />
          ) : null}
          {organizations.isSuccess && organizations.data.length > 0 ? <div className="table-scroll"><table className="data-grid">
            <thead><tr><th>Organization</th><th>Code</th><th>Role</th></tr></thead>
            <tbody>{organizations.data.map(organization => <tr key={organization.id}>
              <td><Link className="grid-link" to={`/organizations/${encodeURIComponent(organization.id)}`}>{organization.name}</Link></td>
              <td className="muted-cell">{organization.code}</td><td>{getOrganizationRoleLabel(organization.role)}</td>
            </tr>)}</tbody>
          </table></div> : null}
        </WorkspaceSection>
      </div>
    </AppShell>
  )
}
