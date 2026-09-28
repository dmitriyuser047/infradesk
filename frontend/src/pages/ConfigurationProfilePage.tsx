import { useId, useState, type FormEvent } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'

import {
  useArchiveConfigurationProfile, useConfigurationProfile, useConfigurationRevision, useConfigurationRevisions,
  useUpdateConfigurationProfile,
} from '../api/configurations'
import { ApiError } from '../api/httpClient'
import { useOrganizationPermissions } from '../components/auth/authorization'
import { ConfigurationAssignmentList } from '../components/configuration/ConfigurationAssignmentList'
import { AppShell } from '../components/layout/AppShell'
import {
  EmptyWorkspaceState, InlineAlert, PageLoading, PageUnavailable, StatusIndicator, WorkspaceHeader, WorkspaceSection, WorkspaceTabs,
} from '../components/layout/WorkspacePrimitives'
import { useI18n } from '../i18n'
import { describeError } from '../i18n/errors'
import type { ConfigurationProfile, ConfigurationRevision } from '../types/configuration'
import { configurationPath, configurationsPath } from './ConfigurationsPage'
import { InvalidRoutePage } from './InvalidRoutePage'
import '../styles/pages/configurations.css'

const Tabs = ['template', 'variables', 'versions', 'targets'] as const
type Tab = typeof Tabs[number]

export function ConfigurationProfilePage() {
  const { organizationId, profileId } = useParams()
  if (!organizationId || !profileId) return <InvalidRoutePage />
  return <AppShell><ProfileContent organizationId={organizationId} profileId={profileId} /></AppShell>
}

/**
 * One profile: its metadata, the content of a version — the latest unless another is asked for —
 * and its history. A version shown here is immutable; changing content means a new version,
 * changing details never does.
 */
function ProfileContent({ organizationId, profileId }: { organizationId: string; profileId: string }) {
  const i18n = useI18n()
  const t = i18n.t.configurations
  const permissions = useOrganizationPermissions(organizationId)
  const canManage = permissions.can('manageConfigurations')
  const [searchParams, setSearchParams] = useSearchParams()
  const tab = Tabs.find(value => value === searchParams.get('tab')) ?? 'template'
  const requested = Number(searchParams.get('revision'))
  const query = useConfigurationProfile(organizationId, profileId, canManage)
  const latest = query.data?.profile.latestRevisionNumber
  const revisionNumber = Number.isInteger(requested) && requested >= 1 && requested !== latest ? requested : null
  const older = useConfigurationRevision(organizationId, profileId, canManage ? revisionNumber : null)
  const archive = useArchiveConfigurationProfile(organizationId, profileId)
  const [editing, setEditing] = useState(false)
  const back = { label: t.back, to: configurationsPath(organizationId) }
  const setParams = (change: (params: URLSearchParams) => void) => setSearchParams(previous => {
    const updated = new URLSearchParams(previous)
    change(updated)
    return updated
  }, { replace: true })
  const selectTab = (next: Tab) => setParams(params => { if (next === 'template') params.delete('tab'); else params.set('tab', next) })

  if (!permissions.isPending && !canManage) {
    return <div className="workspace-page"><WorkspaceHeader title={t.title} back={back} /><InlineAlert tone="danger" title={t.accessDenied} /></div>
  }
  if (permissions.isPending || query.isPending) return <PageLoading title={t.title} back={back} label={t.loading} />
  if (query.isError || !query.data) {
    return <PageUnavailable back={back} onRetry={() => query.refetch()} error={query.error}
      notFound={query.error instanceof ApiError && query.error.code === 'CONFIGURATION_PROFILE_NOT_FOUND'}
      notFoundTitle={t.notFound} errorTitle={t.detailLoadError} />
  }
  const { profile, latestRevision } = query.data
  const shown = revisionNumber === null ? latestRevision : older.data
  const tabs = Tabs.map(id => ({ id, label: t.tabs[id] }))
  const newVersionPath = `${configurationPath(organizationId, profileId)}/versions/new`

  return <div className="workspace-page">
    <WorkspaceHeader title={profile.name} back={back}
      subtitle={<><span className="technical-value">{profile.code}</span> · {t.version(profile.latestRevisionNumber)}</>}
      status={profile.archived ? <StatusIndicator label={t.archivedState} tone="neutral" /> : null}
      actions={profile.archived ? null : <>
        <Link className="primary-button" to={newVersionPath}>{t.newVersion}</Link>
        <button type="button" className="secondary-button" aria-expanded={editing} onClick={() => setEditing(value => !value)}>{t.editDetails}</button>
        <details className="toolbar-overflow"><summary aria-label={t.moreActions} title={t.moreActions}>⋯</summary>
          <button type="button" className="danger-action" disabled={archive.isPending} onClick={() => {
            if (window.confirm(t.archiveConfirm(profile.name))) archive.mutate()
          }}>{archive.isPending ? t.archiving : t.archive}</button>
        </details>
      </>} />
    {profile.description ? <p className="configuration-description">{profile.description}</p> : null}
    {profile.archived ? <InlineAlert tone="info" title={t.archivedNotice} /> : null}
    {archive.isError ? <InlineAlert tone="danger" title={describeError(archive.error, i18n)} /> : null}
    {editing && !profile.archived ? <DetailsForm organizationId={organizationId} profile={profile} onDone={() => setEditing(false)} /> : null}
    <WorkspaceTabs tabs={tabs} active={tab} onChange={selectTab} />
    <div role="tabpanel" id={`panel-${tab}`} aria-labelledby={`tab-${tab}`}>
      {revisionNumber !== null && tab !== 'versions' && tab !== 'targets' ? <InlineAlert tone="info" title={t.viewingOld(revisionNumber, profile.latestRevisionNumber)}
        action={<button type="button" className="secondary-button" onClick={() => setParams(params => params.delete('revision'))}>{t.showLatest}</button>} /> : null}
      {tab !== 'versions' && tab !== 'targets' && revisionNumber !== null && older.isPending ? <div className="row-skeleton" aria-label={t.loading}><span /><span /></div> : null}
      {tab !== 'versions' && tab !== 'targets' && revisionNumber !== null && older.isError ? <InlineAlert tone="danger" title={t.revisionLoadError}>
        {describeError(older.error, i18n)}</InlineAlert> : null}
      {tab === 'template' && shown ? <TemplateView revision={shown} /> : null}
      {tab === 'variables' && shown ? <VariablesView revision={shown} /> : null}
      {tab === 'versions' ? <VersionsView organizationId={organizationId} profileId={profileId} latest={profile.latestRevisionNumber}
        open={number => setParams(params => {
          params.delete('tab')
          if (number === profile.latestRevisionNumber) params.delete('revision'); else params.set('revision', String(number))
        })} /> : null}
      {/* Assignments pin their own version; an archived profile keeps them but takes no new one. */}
      {tab === 'targets' ? <ConfigurationAssignmentList organizationId={organizationId} filter={{ profileId }} view="profile"
        canAssign={!profile.archived} /> : null}
    </div>
  </div>
}

function TemplateView({ revision }: { revision: ConfigurationRevision }) {
  const i18n = useI18n()
  const t = i18n.t.configurations
  return <WorkspaceSection title={t.versionTitle(revision.revisionNumber)}
    description={t.createdBy(i18n.format.dateTime(revision.createdAt), revision.createdBy.displayName)}>
    <pre className="configuration-code" tabIndex={0} aria-label={`${t.template} ${t.version(revision.revisionNumber)}`}>{revision.template}</pre>
  </WorkspaceSection>
}

function VariablesView({ revision }: { revision: ConfigurationRevision }) {
  const t = useI18n().t.configurations
  return <WorkspaceSection title={t.variables} description={t.versionTitle(revision.revisionNumber)}>
    {revision.variables.length === 0 ? <EmptyWorkspaceState compact title={t.noVariables} /> : <div className="table-scroll"><table className="data-grid">
      <thead><tr><th scope="col">{t.variableColumns.name}</th><th scope="col">{t.variableColumns.type}</th>
        <th scope="col">{t.variableColumns.required}</th><th scope="col">{t.variableColumns.default}</th>
        <th scope="col">{t.variableColumns.description}</th></tr></thead>
      <tbody>{revision.variables.map(variable => <tr key={variable.name}>
        <td><code className="technical-value">{variable.name}</code></td>
        <td>{t.types[variable.type] ?? variable.type}</td>
        <td>{variable.required ? t.yes : t.no}</td>
        <td>{variable.defaultValue === null ? <span className="muted-cell">{t.noDefault}</span>
          : <code className="technical-value">{variable.defaultValue}</code>}</td>
        <td>{variable.description ?? <span className="muted-cell">—</span>}</td>
      </tr>)}</tbody>
    </table></div>}
  </WorkspaceSection>
}

function VersionsView({ organizationId, profileId, latest, open }: {
  organizationId: string; profileId: string; latest: number; open: (revisionNumber: number) => void
}) {
  const i18n = useI18n()
  const t = i18n.t.configurations
  const query = useConfigurationRevisions(organizationId, profileId, true)
  return <WorkspaceSection title={t.tabs.versions}>
    {query.isPending ? <div className="row-skeleton" aria-label={t.loading}><span /><span /></div> : null}
    {query.isError ? <InlineAlert tone="danger" title={t.versionsLoadError}
      action={<button className="secondary-button" type="button" onClick={() => query.refetch()}>{i18n.t.common.retry}</button>} /> : null}
    {query.data ? <div className="table-scroll"><table className="data-grid">
      <thead><tr><th scope="col">{t.versionsColumns.version}</th><th scope="col">{t.versionsColumns.created}</th>
        <th scope="col">{t.versionsColumns.author}</th><th scope="col">{t.versionsColumns.variables}</th></tr></thead>
      <tbody>{query.data.map(revision => <tr key={revision.revisionNumber}>
        <td><button type="button" className="text-button grid-link" aria-label={t.open(revision.revisionNumber)}
          onClick={() => open(revision.revisionNumber)}>{t.version(revision.revisionNumber)}</button>
          {revision.revisionNumber === latest ? <small className="cell-secondary">{t.latest}</small> : null}</td>
        <td>{i18n.format.dateTime(revision.createdAt)}</td>
        <td>{revision.createdBy.displayName}</td>
        <td className="numeric-cell">{revision.variableCount}</td>
      </tr>)}</tbody>
    </table></div> : null}
  </WorkspaceSection>
}

/** Name and description. Saving them never creates a version, and says so. */
function DetailsForm({ organizationId, profile, onDone }: { organizationId: string; profile: ConfigurationProfile; onDone: () => void }) {
  const i18n = useI18n()
  const t = i18n.t.configurations
  const update = useUpdateConfigurationProfile(organizationId, profile.id)
  const ids = { name: useId(), description: useId(), note: useId() }
  const [name, setName] = useState(profile.name)
  const [description, setDescription] = useState(profile.description ?? '')
  const [error, setError] = useState('')
  const submit = (event: FormEvent) => {
    event.preventDefault()
    setError('')
    if (!name.trim() || name.trim().length > 255) return setError(t.invalidName)
    update.mutate({ name: name.trim(), description: description.trim() || null }, { onSuccess: onDone })
  }
  return <form className="workspace-section configuration-details-form" onSubmit={submit} noValidate aria-describedby={ids.note}>
    <div className="workspace-section-heading"><h2>{t.detailsTitle}</h2></div>
    <p id={ids.note} className="section-description">{t.detailsNote}</p>
    <div className="configuration-details-grid">
      <label htmlFor={ids.name}>{t.name}<input id={ids.name} value={name} maxLength={255} onChange={event => setName(event.target.value)} /></label>
      <label htmlFor={ids.description} className="configuration-details-wide">{t.description}
        <textarea id={ids.description} rows={2} value={description} maxLength={4000} onChange={event => setDescription(event.target.value)} /></label>
    </div>
    {error ? <InlineAlert tone="danger" title={error} /> : null}
    {update.isError ? <InlineAlert tone="danger" title={describeError(update.error, i18n)} /> : null}
    <div className="configuration-editor-actions">
      <button type="button" className="secondary-button" onClick={onDone}>{t.cancel}</button>
      <button type="submit" className="primary-button" disabled={update.isPending}>{update.isPending ? t.savingDetails : t.saveDetails}</button>
    </div>
  </form>
}
