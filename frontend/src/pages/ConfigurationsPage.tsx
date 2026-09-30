import { FileCog, Plus } from 'lucide-react'
import { Link, useParams, useSearchParams } from 'react-router-dom'

import { ConfigurationListLimit, useConfigurationProfiles } from '../api/configurations'
import { useOrganizationPermissions } from '../components/auth/authorization'
import { AppShell } from '../components/layout/AppShell'
import {
  EmptyWorkspaceState, InlineAlert, SegmentedControl, StatusIndicator, WorkspaceHeader, WorkspaceSection,
} from '../components/layout/WorkspacePrimitives'
import { useI18n } from '../i18n'
import { describeError } from '../i18n/errors'
import { InvalidRoutePage } from './InvalidRoutePage'
import '../styles/pages/configurations.css'

export const configurationsPath = (organizationId: string) => `/organizations/${encodeURIComponent(organizationId)}/configurations`
export const configurationPath = (organizationId: string, profileId: string) =>
  `${configurationsPath(organizationId)}/${encodeURIComponent(profileId)}`

export function ConfigurationsPage() {
  const { organizationId } = useParams()
  if (!organizationId) return <InvalidRoutePage />
  return <AppShell><ConfigurationsContent organizationId={organizationId} /></AppShell>
}

/** The organization's configuration profiles: metadata only, content opens with a profile. */
function ConfigurationsContent({ organizationId }: { organizationId: string }) {
  const i18n = useI18n()
  const t = i18n.t.configurations
  const permissions = useOrganizationPermissions(organizationId)
  const canManage = permissions.can('manageConfigurations')
  const [searchParams, setSearchParams] = useSearchParams()
  const archived = searchParams.get('state') === 'archived'
  const query = useConfigurationProfiles(organizationId, archived, canManage)
  const createPath = `${configurationsPath(organizationId)}/new`
  const create = <Link className="primary-button" to={createPath}><Plus aria-hidden size={16} />{t.create}</Link>
  const select = (next: 'active' | 'archived') => setSearchParams(previous => {
    const updated = new URLSearchParams(previous)
    if (next === 'archived') updated.set('state', 'archived')
    else updated.delete('state')
    return updated
  }, { replace: true })

  if (!permissions.isPending && !canManage) {
    return <div className="workspace-page"><WorkspaceHeader title={t.title} subtitle={t.subtitle} />
      <InlineAlert tone="danger" title={t.accessDenied} /></div>
  }
  const profiles = query.data?.filter(profile => profile.kind === 'FILE_TEMPLATE')
  return <div className="workspace-page">
    <WorkspaceHeader title={t.title} subtitle={t.subtitle} actions={canManage ? create : null} />
    <WorkspaceSection title={t.section} actions={profiles ? <span className="resource-count">{profiles.length}</span> : null}>
      <div className="filter-bar">
        <SegmentedControl name="configuration-state" label={t.filterLabel} value={archived ? 'archived' : 'active'} onChange={select}
          options={[{ value: 'active', label: t.filters.active }, { value: 'archived', label: t.filters.archived }]} />
      </div>
      {permissions.isPending || query.isPending ? <div className="row-skeleton" aria-label={t.loading}><span /><span /></div> : null}
      {query.isError ? <InlineAlert tone="danger" title={t.loadError}
        action={<button className="secondary-button" type="button" onClick={() => query.refetch()}>{i18n.t.common.retry}</button>}>
        {describeError(query.error, i18n)}</InlineAlert> : null}
      {profiles?.length === 0 ? (archived ? <EmptyWorkspaceState compact title={t.emptyArchived} />
        : <EmptyWorkspaceState icon={FileCog} title={t.empty} detail={t.emptyDetail} action={create} />) : null}
      {profiles && profiles.length >= ConfigurationListLimit ? <InlineAlert tone="info" title={t.truncated(profiles.length)} /> : null}
      {profiles && profiles.length > 0 ? <div className="table-scroll"><table className="data-grid">
        <thead><tr><th scope="col">{t.columns.name}</th><th scope="col">{t.columns.version}</th>
          <th scope="col">{t.columns.updated}</th><th scope="col">{t.columns.state}</th></tr></thead>
        <tbody>{profiles.map(profile => <tr key={profile.id}>
          <td><Link className="grid-link" to={configurationPath(organizationId, profile.id)}>{profile.name}</Link>
            <small className="cell-secondary technical-value">{profile.code}</small></td>
          <td className="numeric-cell">{t.version(profile.latestRevisionNumber)}</td>
          <td>{i18n.format.dateTime(profile.updatedAt)}</td>
          <td>{profile.archived ? <StatusIndicator label={t.archivedState} tone="neutral" />
            : <StatusIndicator label={t.active} tone="success" />}</td>
        </tr>)}</tbody>
      </table></div> : null}
    </WorkspaceSection>
  </div>
}
