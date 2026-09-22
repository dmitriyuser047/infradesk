import { Link } from 'react-router-dom'

import { useMyOrganizations } from '../api/auth'
import { getOrganizationRoleLabel } from '../components/auth/authPresentation'
import { AppShell } from '../components/layout/AppShell'

export function OrganizationsPage() {
  const organizations = useMyOrganizations()

  return (
    <AppShell>
      <div className="organization-page">
        <header className="page-header organization-header">
          <div>
            <p className="eyebrow">Workspace access</p>
            <h1>Organizations</h1>
          </div>
        </header>
        <section className="content-panel organizations-panel" aria-label="Available organizations">
          {organizations.isPending ? <div className="context-skeleton" aria-label="Loading organizations"><span /><span /></div> : null}
          {organizations.isError ? (
            <div className="context-area-error" role="alert">
              <p>Unable to load organizations</p>
              <button className="retry-button" type="button" onClick={() => organizations.refetch()}>Retry</button>
            </div>
          ) : null}
          {organizations.isSuccess && organizations.data.length === 0 ? (
            <div className="context-empty"><h2>No organizations available</h2></div>
          ) : null}
          {organizations.isSuccess ? organizations.data.map((organization) => (
            <Link className="organization-row" key={organization.id} to={`/organizations/${encodeURIComponent(organization.id)}`}>
              <span><strong>{organization.name}</strong><small>{organization.code}</small></span>
              <span className="organization-role">{getOrganizationRoleLabel(organization.role)}</span>
            </Link>
          )) : null}
        </section>
      </div>
    </AppShell>
  )
}
