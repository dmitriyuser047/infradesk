import { Link } from 'react-router-dom'

import { useMyOrganizations } from '../api/auth'
import { getOrganizationRoleLabel } from '../components/auth/authPresentation'
import { AppShell } from '../components/layout/AppShell'
import { EmptyWorkspaceState, WorkspaceHeader, WorkspaceSection } from '../components/layout/WorkspacePrimitives'
import { useI18n } from '../i18n'

export function OrganizationsPage() {
  const i18n = useI18n()
  const t = i18n.t.organizations
  const organizations = useMyOrganizations()

  return (
    <AppShell>
      <div className="workspace-page">
        <WorkspaceHeader title={t.title} subtitle={t.subtitle} />
        <WorkspaceSection title={t.available}>
          {organizations.isPending ? <div className="context-skeleton" aria-label={t.loading}><span /><span /></div> : null}
          {organizations.isError ? (
            <div className="context-area-error" role="alert">
              <p>{t.loadError}</p>
              <button className="retry-button" type="button" onClick={() => organizations.refetch()}>{i18n.t.common.retry}</button>
            </div>
          ) : null}
          {organizations.isSuccess && organizations.data.length === 0 ? (
            <EmptyWorkspaceState title={t.empty} />
          ) : null}
          {organizations.isSuccess && organizations.data.length > 0 ? <div className="table-scroll"><table className="data-grid">
            <thead><tr><th>{t.columns.organization}</th><th>{t.columns.code}</th><th>{t.columns.role}</th></tr></thead>
            <tbody>{organizations.data.map(organization => <tr key={organization.id}>
              <td><Link className="grid-link" to={`/organizations/${encodeURIComponent(organization.id)}/overview`}>{organization.name}</Link></td>
              <td className="muted-cell">{organization.code}</td><td>{getOrganizationRoleLabel(organization.role, i18n)}</td>
            </tr>)}</tbody>
          </table></div> : null}
        </WorkspaceSection>
      </div>
    </AppShell>
  )
}
