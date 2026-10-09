import { Link } from 'react-router-dom'
import { useState } from 'react'
import { ArrowRight, Plus, Search, ShieldCheck } from 'lucide-react'

import { useMyOrganizations } from '../api/auth'
import { getOrganizationRoleLabel } from '../components/auth/authPresentation'
import { AppShell } from '../components/layout/AppShell'
import { EmptyWorkspaceState, InlineAlert, WorkspaceHeader, WorkspaceSection } from '../components/layout/WorkspacePrimitives'
import { useI18n } from '../i18n'
import { getDisplayName } from '../components/navigation/navigationPresentation'

export function OrganizationsPage() {
  const i18n = useI18n()
  const t = i18n.t.organizations
  const organizations = useMyOrganizations()
  const [search, setSearch] = useState('')
  const items = organizations.data?.filter(item => `${getDisplayName(item, t.columns.organization)} ${item.code}`.toLocaleLowerCase().includes(search.trim().toLocaleLowerCase())) ?? []

  return (
    <AppShell>
      <div className="workspace-page organizations-page">
        <WorkspaceHeader title={t.title} actions={<Link className="primary-button" to="/organizations/new"><Plus size={16} aria-hidden />{i18n.t.administration.createOrganization}</Link>} />
        <WorkspaceSection title={t.available}>
          {organizations.isPending ? <div className="context-skeleton" aria-label={t.loading}><span /><span /></div> : null}
          {organizations.isError ? (
            <InlineAlert tone="danger" title={t.loadError}
              action={<button className="secondary-button" type="button" onClick={() => organizations.refetch()}>{i18n.t.common.retry}</button>} />
          ) : null}
          {organizations.isSuccess && organizations.data.length === 0 ? (
            <EmptyWorkspaceState title={t.empty} />
          ) : null}
          {organizations.isSuccess && organizations.data.length > 0 ? <>
            <div className="filter-bar list-filter-bar">
              <div className="search-field"><Search aria-hidden size={16} className="search-field-icon" /><input aria-label={t.search} placeholder={t.search}
                value={search} onChange={event => setSearch(event.target.value)} /></div>
              <span className="muted-cell">{items.length} / {organizations.data.length}</span>
            </div>
            {items.length === 0 ? <EmptyWorkspaceState title={t.noMatches} /> : <div className="organization-card-grid">
              {items.map(organization => <Link className="organization-card" key={organization.id}
                to={`/organizations/${encodeURIComponent(organization.id)}/overview`}>
                <div className="organization-card-heading"><span className="organization-card-avatar" aria-hidden>
                  {getDisplayName(organization, t.columns.organization).slice(0, 1).toLocaleUpperCase()}</span>
                  <div><h2>{getDisplayName(organization, t.columns.organization)}</h2><span>{organization.code}</span></div>
                </div>
                <div className="organization-card-access"><ShieldCheck aria-hidden size={16} /><span>{t.access}</span>
                  <strong>{getOrganizationRoleLabel(organization.role, i18n)}</strong></div>
                <div className="organization-card-action"><span>{t.open}</span><ArrowRight aria-hidden size={18} /></div>
              </Link>)}
            </div>}
          </> : null}
        </WorkspaceSection>
      </div>
    </AppShell>
  )
}
