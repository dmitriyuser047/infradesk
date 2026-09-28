import { useId, useState, type FormEvent, type ReactNode } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'

import {
  useConfigurationAssignment, useCreateConfigurationAssignment, usePreviewConfigurationAssignment,
  useRemoveConfigurationAssignment, useUpdateConfigurationAssignment,
} from '../api/configurationAssignments'
import { useConfigurationProfile, useConfigurationProfiles, useConfigurationRevision, useConfigurationRevisions } from '../api/configurations'
import { ApiError } from '../api/httpClient'
import { useEnvironments, useProjects } from '../api/navigation'
import { useEnvironmentResources, useResource } from '../api/resources'
import { useOrganizationPermissions } from '../components/auth/authorization'
import { AssignmentStatus, ManagedRemovePanel, resourcePagePath } from '../components/configuration/ConfigurationAssignmentList'
import { ConfigurationDeploymentPanel } from '../components/configuration/ConfigurationDeploymentPanel'
import { isTargetPath, supportsConfigurationAssignment } from '../components/configuration/configurationTargetSupport'
import { AppShell } from '../components/layout/AppShell'
import { InlineAlert, PageLoading, PageUnavailable, WorkspaceHeader, WorkspaceSection } from '../components/layout/WorkspacePrimitives'
import { useI18n } from '../i18n'
import { describeError } from '../i18n/errors'
import type { ConfigurationVariable } from '../types/configuration'
import type { ConfigurationAssignmentDetail, ConfigurationAssignmentPreview, ConfigurationValue } from '../types/configurationAssignment'
import { configurationPath, configurationsPath } from './ConfigurationsPage'
import { InvalidRoutePage } from './InvalidRoutePage'
import '../styles/pages/configurations.css'

export { isTargetPath }

const profileTargetsPath = (organizationId: string, profileId: string) => `${configurationPath(organizationId, profileId)}?tab=targets`

interface Draft {
  profileRevisionNumber: number
  targetPath: string
  values: ConfigurationValue[]
}

// ---------------------------------------------------------------------------------------------
// Create
// ---------------------------------------------------------------------------------------------

/** A new assignment, opened from a profile (profile chosen) or from a node (node chosen). */
export function ConfigurationAssignmentCreatePage() {
  const { organizationId } = useParams()
  const [searchParams] = useSearchParams()
  if (!organizationId) return <InvalidRoutePage />
  return <AppShell><CreateContent organizationId={organizationId}
    presetProfileId={searchParams.get('profileId')} presetResourceId={searchParams.get('resourceId')} /></AppShell>
}

function CreateContent({ organizationId, presetProfileId, presetResourceId }: {
  organizationId: string; presetProfileId: string | null; presetResourceId: string | null
}) {
  const i18n = useI18n()
  const t = i18n.t.assignments
  const navigate = useNavigate()
  const permissions = useOrganizationPermissions(organizationId)
  const canManage = permissions.can('manageConfigurations')
  const [profileId, setProfileId] = useState(presetProfileId ?? '')
  const [resourceId, setResourceId] = useState(presetResourceId ?? '')
  const presetResource = useResource(organizationId, presetResourceId ?? undefined)
  const presetProfile = useConfigurationProfile(organizationId, presetProfileId ?? '', canManage && presetProfileId !== null)
  const create = useCreateConfigurationAssignment(organizationId)
  const back = presetProfileId
    ? { label: presetProfile.data?.profile.name ?? i18n.t.configurations.back, to: profileTargetsPath(organizationId, presetProfileId) }
    : presetResourceId && presetResource.data
      ? { label: presetResource.data.name, to: `/organizations/${encodeURIComponent(organizationId)}/environments/${
        encodeURIComponent(presetResource.data.environmentId)}/resources/${encodeURIComponent(presetResourceId)}?tab=configurations` }
      : { label: i18n.t.configurations.back, to: configurationsPath(organizationId) }

  if (!permissions.isPending && !canManage) {
    return <div className="workspace-page"><WorkspaceHeader title={t.createTitle} back={back} /><InlineAlert tone="danger" title={t.accessDenied} /></div>
  }
  if (permissions.isPending) return <PageLoading title={t.createTitle} back={back} label={t.loading} />

  const presetUnsupported = presetResource.data !== undefined
    && (!supportsConfigurationAssignment(presetResource.data.resourceTypeCode) || !presetResource.data.active)
  const archived = presetProfile.data?.profile.archived === true

  const target = presetResourceId
    ? <p className="assignment-fixed">{presetResource.data ? <strong>{presetResource.data.name}</strong>
      : presetResource.isError ? describeError(presetResource.error, i18n) : t.loading}</p>
    : <TargetChooser organizationId={organizationId} value={resourceId} onChange={setResourceId} />
  const profileField = presetProfileId
    ? <p className="assignment-fixed">{presetProfile.data ? <><strong>{presetProfile.data.profile.name}</strong>{' '}
      <span className="technical-value">{presetProfile.data.profile.code}</span></> : t.loading}</p>
    : <ProfileChooser organizationId={organizationId} value={profileId} onChange={setProfileId} />

  return <div className="workspace-page">
    <WorkspaceHeader title={t.createTitle} back={back} />
    <InlineAlert tone="info" title={t.notApplied} />
    {presetUnsupported ? <InlineAlert tone="warning" title={t.onlyNodes} /> : null}
    {archived ? <InlineAlert tone="warning" title={i18n.t.errors.codes.CONFIGURATION_PROFILE_ARCHIVED} /> : null}
    <AssignmentForm organizationId={organizationId} profileId={profileId || null} assignedRevision={null}
      initial={{ targetPath: '', values: {} }} target={target} profileField={profileField}
      pending={create.isPending} error={create.error} disabled={presetUnsupported || archived}
      submitLabel={t.save} pendingLabel={t.saving}
      validate={() => (resourceId ? null : t.chooseTarget)}
      onSubmit={draft => create.mutate({ resourceId, profileId, ...draft }, {
        onSuccess: detail => navigate(presetProfileId ? profileTargetsPath(organizationId, presetProfileId)
          : `${resourcePagePath(organizationId, detail)}?tab=configurations`, { replace: true }),
      })} />
  </div>
}

/** Environment first, then its active nodes: nothing else can take a configuration yet. */
function TargetChooser({ organizationId, value, onChange }: { organizationId: string; value: string; onChange: (id: string) => void }) {
  const t = useI18n().t.assignments
  const ids = { project: useId(), environment: useId(), resource: useId() }
  const [projectId, setProjectId] = useState('')
  const [environmentId, setEnvironmentId] = useState('')
  const projects = useProjects(organizationId)
  const environments = useEnvironments(organizationId, projectId || null)
  const resources = useEnvironmentResources(organizationId, environmentId || undefined)
  const nodes = (resources.data ?? []).filter(resource => supportsConfigurationAssignment(resource.resourceTypeCode) && resource.active)
  return <div className="assignment-target-fields">
    <div className="configuration-field"><label htmlFor={ids.project}>{t.project}</label>
      <select id={ids.project} value={projectId} onChange={event => { setProjectId(event.target.value); setEnvironmentId(''); onChange('') }}>
        <option value="">{t.chooseProject}</option>
        {(projects.data ?? []).map(project => <option key={project.id} value={project.id}>{project.name}</option>)}
      </select></div>
    <div className="configuration-field"><label htmlFor={ids.environment}>{t.environment}</label>
      <select id={ids.environment} value={environmentId} disabled={!projectId}
        onChange={event => { setEnvironmentId(event.target.value); onChange('') }}>
        <option value="">{t.chooseEnvironment}</option>
        {(environments.data ?? []).map(environment => <option key={environment.id} value={environment.id}>{environment.name}</option>)}
      </select></div>
    <div className="configuration-field"><label htmlFor={ids.resource}>{t.resource}</label>
      <select id={ids.resource} value={value} disabled={!environmentId} onChange={event => onChange(event.target.value)}>
        <option value="">{environmentId && resources.isSuccess && nodes.length === 0 ? t.noNodes : t.chooseResource}</option>
        {nodes.map(node => <option key={node.id} value={node.id}>{node.name}</option>)}
      </select></div>
  </div>
}

function ProfileChooser({ organizationId, value, onChange }: { organizationId: string; value: string; onChange: (id: string) => void }) {
  const t = useI18n().t.assignments
  const id = useId()
  const profiles = useConfigurationProfiles(organizationId, false, true)
  return <>
    <label htmlFor={id} className="visually-hidden">{t.profile}</label>
    <select id={id} value={value} onChange={event => onChange(event.target.value)}>
      <option value="">{t.chooseProfile}</option>
      {(profiles.data ?? []).map(profile => <option key={profile.id} value={profile.id}>{profile.name}</option>)}
    </select>
  </>
}

// ---------------------------------------------------------------------------------------------
// Edit
// ---------------------------------------------------------------------------------------------

export function ConfigurationAssignmentEditPage() {
  const { organizationId, assignmentId } = useParams()
  if (!organizationId || !assignmentId) return <InvalidRoutePage />
  return <AppShell><EditContent organizationId={organizationId} assignmentId={assignmentId} /></AppShell>
}

function EditContent({ organizationId, assignmentId }: { organizationId: string; assignmentId: string }) {
  const i18n = useI18n()
  const t = i18n.t.assignments
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const permissions = useOrganizationPermissions(organizationId)
  const canManage = permissions.can('manageConfigurations')
  const query = useConfigurationAssignment(organizationId, assignmentId, canManage)
  const update = useUpdateConfigurationAssignment(organizationId, assignmentId)
  const remove = useRemoveConfigurationAssignment(organizationId)
  const [removingManaged, setRemovingManaged] = useState(false)
  const detail = query.data
  const fromProfile = searchParams.get('from') === 'profile'
  const back = !detail ? { label: i18n.t.configurations.back, to: configurationsPath(organizationId) }
    : fromProfile ? { label: detail.profile.name, to: profileTargetsPath(organizationId, detail.profile.id) }
      : { label: detail.resource.name, to: `${resourcePagePath(organizationId, detail)}?tab=configurations` }

  if (!permissions.isPending && !canManage) {
    return <div className="workspace-page"><WorkspaceHeader title={t.editTitle} back={back} /><InlineAlert tone="danger" title={t.accessDenied} /></div>
  }
  if (permissions.isPending || query.isPending) return <PageLoading title={t.editTitle} back={back} label={t.loading} />
  if (query.isError || !detail) {
    return <PageUnavailable back={back} onRetry={() => query.refetch()} error={query.error}
      notFound={query.error instanceof ApiError && query.error.code === 'CONFIGURATION_ASSIGNMENT_NOT_FOUND'}
      notFoundTitle={t.notFound} errorTitle={t.detailLoadError} />
  }
  const changed = update.error instanceof ApiError && update.error.code === 'CONFIGURATION_ASSIGNMENT_CHANGED'
  const removed = detail.removedAt !== null
  // A rule owns the version and the path; only the per-node values are edited here.
  const rule = removed ? null : detail.rule ?? null

  return <div className="workspace-page">
    <WorkspaceHeader title={`${detail.profile.name} → ${detail.resource.name}`} back={back}
      subtitle={<span className="technical-value">{detail.targetPath}</span>}
      status={removed ? null : <AssignmentStatus assignment={detail} />}
      actions={removed ? null : <button type="button" className="secondary-button danger-action" disabled={remove.isPending} onClick={() => {
        if (rule) setRemovingManaged(true)
        else if (window.confirm(t.removeConfirm)) remove.mutate(detail, { onSuccess: () => navigate(back.to, { replace: true }) })
      }}>{remove.isPending ? t.removing : t.remove}</button>} />
    {rule ? <InlineAlert tone="info" title={i18n.t.rules.managedNotice(rule.name)}
      action={<Link className="secondary-button" to={`/organizations/${encodeURIComponent(organizationId)}/configuration-rules/${
        encodeURIComponent(rule.id)}`}>{i18n.t.rules.openRule}</Link>} /> : null}
    {rule && removingManaged ? <ManagedRemovePanel organizationId={organizationId} assignment={{ ...detail, rule }}
      onCancel={() => setRemovingManaged(false)} onDone={() => navigate(back.to, { replace: true })} /> : null}
    {removed ? <InlineAlert tone="info" title={t.removedNotice} /> : <InlineAlert tone="info" title={t.notApplied} />}
    {!removed && !detail.resource.active ? <InlineAlert tone="warning" title={t.inactiveNotice} /> : null}
    {detail.profile.archived ? <InlineAlert tone="info" title={t.archivedNotice} /> : null}
    {remove.isError ? <InlineAlert tone="danger" title={describeError(remove.error, i18n)} /> : null}
    {changed ? <InlineAlert tone="warning" title={t.changed}
      action={<button type="button" className="secondary-button" onClick={() => { update.reset(); void query.refetch() }}>{t.reload}</button>} /> : null}
    {removed ? null : <AssignmentForm key={detail.version} organizationId={organizationId} profileId={detail.profile.id}
      assignedRevision={detail} initial={{ targetPath: detail.targetPath,
        values: Object.fromEntries(detail.values.map(value => [value.name, value.value])) }}
      target={<p className="assignment-fixed"><strong>{detail.resource.name}</strong> · {detail.resource.environment.name}</p>}
      profileField={<p className="assignment-fixed"><strong>{detail.profile.name}</strong>{' '}
        <span className="technical-value">{detail.profile.code}</span></p>}
      pending={update.isPending} error={changed ? null : update.error} disabled={false} locked={rule !== null}
      submitLabel={t.save} pendingLabel={t.saving} validate={() => null}
      onSubmit={draft => update.mutate({ expectedVersion: detail.version, ...draft }, { onSuccess: () => navigate(back.to, { replace: true }) })} />}
    {!removed && detail.resource.active && permissions.can('deployConfigurations')
      ? <ConfigurationDeploymentPanel key={`deployment-${detail.version}`} organizationId={organizationId} assignment={detail} /> : null}
  </div>
}

// ---------------------------------------------------------------------------------------------
// The form both share
// ---------------------------------------------------------------------------------------------

/**
 * Target, configuration, version, path and values, then a preview. The values are the explicit ones
 * only: an empty field inherits the version's default and is never sent, so a default shown here
 * never turns into an override. Switching versions keeps the values whose names still exist; the
 * others are left out of what is saved, and the backend decides what is valid.
 */
function AssignmentForm({ organizationId, profileId, assignedRevision, initial, target, profileField, pending, error, disabled,
  locked = false, submitLabel, pendingLabel, validate, onSubmit }: {
  organizationId: string
  profileId: string | null
  assignedRevision: ConfigurationAssignmentDetail | null
  initial: { targetPath: string; values: Record<string, string> }
  target: ReactNode
  profileField: ReactNode
  pending: boolean
  error: unknown
  disabled: boolean
  /** The version and the path belong to an automation rule and cannot be changed here. */
  locked?: boolean
  submitLabel: string
  pendingLabel: string
  validate: () => string | null
  onSubmit: (draft: Draft) => void
}) {
  const i18n = useI18n()
  const t = i18n.t.assignments
  const ids = { version: useId(), path: useId(), pathHelp: useId(), secrets: useId() }
  const [chosenRevision, setChosenRevision] = useState<number | null>(assignedRevision?.profileRevisionNumber ?? null)
  const [targetPath, setTargetPath] = useState(initial.targetPath)
  const [values, setValues] = useState<Record<string, string>>(initial.values)
  const [problem, setProblem] = useState('')
  const preview = usePreviewConfigurationAssignment(organizationId)
  const revisions = useConfigurationRevisions(organizationId, profileId ?? '', profileId !== null)
  const latest = revisions.data?.[0]?.revisionNumber ?? assignedRevision?.profile.latestRevisionNumber ?? null
  // A new assignment starts from the latest version, but what is saved is always a number.
  const revisionNumber = chosenRevision ?? latest
  // The chosen version is always offered, even before the history loads or beyond its first page.
  const listed = (revisions.data ?? []).map(revision => revision.revisionNumber)
  const versionOptions = revisionNumber === null || listed.includes(revisionNumber)
    ? listed : [...listed, revisionNumber].sort((a, b) => b - a)
  const pinned = assignedRevision && assignedRevision.revision.revisionNumber === revisionNumber ? assignedRevision.revision : null
  const fetched = useConfigurationRevision(organizationId, profileId ?? '', profileId !== null && pinned === null ? revisionNumber : null)
  const variables: ConfigurationVariable[] | undefined = pinned?.variables ?? fetched.data?.variables
  const names = new Set((variables ?? []).map(variable => variable.name))
  const dropped = variables ? Object.keys(values).filter(name => !names.has(name)) : []

  const changed = () => { preview.reset(); setProblem('') }
  const draft = (): Draft | null => {
    if (revisionNumber === null || !variables) return null
    return { profileRevisionNumber: revisionNumber, targetPath: targetPath.trim(),
      values: variables.filter(variable => values[variable.name] !== undefined).map(variable => ({ name: variable.name, value: values[variable.name] })) }
  }
  const setValue = (name: string, value: string) => {
    changed()
    setValues(current => {
      const next = { ...current }
      if (value === '') delete next[name]
      else next[name] = value
      return next
    })
  }
  const submit = (event: FormEvent) => {
    event.preventDefault()
    setProblem('')
    const refusal = validate() ?? (!profileId ? t.chooseProfileFirst : null)
    if (refusal) return setProblem(refusal)
    const current = draft()
    if (!current) return
    if (!isTargetPath(current.targetPath)) return setProblem(t.invalidPath)
    onSubmit(current)
  }
  const runPreview = () => {
    const current = draft()
    if (profileId && current) preview.mutate({ profileId, profileRevisionNumber: current.profileRevisionNumber, values: current.values })
  }

  return <form className="configuration-editor" onSubmit={submit} noValidate>
    <WorkspaceSection title={t.targetSection}>
      <div className="assignment-grid-fields">
        <div className="configuration-field"><span className="field-label">{t.target}</span>{target}</div>
        <div className="configuration-field"><span className="field-label">{t.profile}</span>{profileField}</div>
        <div className="configuration-field">
          <label htmlFor={ids.version}>{t.version}</label>
          <select id={ids.version} value={revisionNumber ?? ''} disabled={!revisions.data || locked}
            onChange={event => { changed(); setChosenRevision(Number(event.target.value)) }}>
            {revisionNumber === null ? <option value="">{t.chooseVersion}</option> : null}
            {versionOptions.map(number => <option key={number} value={number}>
              {number === latest ? t.versionLatest(number) : i18n.t.configurations.version(number)}
            </option>)}
          </select>
          {assignedRevision ? <p className="field-hint">
            {t.assignedVersion(assignedRevision.profileRevisionNumber)} · {t.latestAvailable(assignedRevision.profile.latestRevisionNumber)}
            {assignedRevision.profile.latestRevisionNumber !== revisionNumber && !locked
              ? <> <button type="button" className="text-button" onClick={() => { changed(); setChosenRevision(assignedRevision.profile.latestRevisionNumber) }}>
                {t.useVersion(assignedRevision.profile.latestRevisionNumber)}</button></> : null}
          </p> : null}
        </div>
        <div className="configuration-field">
          <label htmlFor={ids.path}>{t.targetPath}<input id={ids.path} className="technical-input" value={targetPath} maxLength={4096}
            readOnly={locked}
            autoComplete="off" spellCheck={false} placeholder="/etc/nginx/nginx.conf" aria-describedby={ids.pathHelp}
            onChange={event => { changed(); setTargetPath(event.target.value) }} /></label>
          <span id={ids.pathHelp} className="field-hint">{t.targetPathHelp}</span>
        </div>
      </div>
    </WorkspaceSection>

    <WorkspaceSection title={t.values} description={t.valuesNote}>
      <p id={ids.secrets} role="note" className="configuration-secret-hint field-hint">{t.secretsWarning}</p>
      {!profileId ? <p className="field-hint">{t.chooseProfileFirst}</p> : null}
      {profileId && !variables && (fetched.isPending || revisions.isPending) ? <div className="row-skeleton" aria-label={t.loading}><span /><span /></div> : null}
      {fetched.isError ? <InlineAlert tone="danger" title={describeError(fetched.error, i18n)} /> : null}
      {variables && variables.length === 0 ? <p className="field-hint">{t.noVariables}</p> : null}
      {variables && variables.length > 0 ? <div className="table-scroll"><table className="data-grid configuration-variables assignment-values">
        <thead><tr><th scope="col">{i18n.t.configurations.variableColumns.name}</th><th scope="col">{i18n.t.configurations.variableColumns.type}</th>
          <th scope="col">{t.valueColumn}</th><th scope="col">{i18n.t.configurations.variableColumns.description}</th></tr></thead>
        <tbody>{variables.map(variable => <ValueRow key={variable.name} variable={variable} value={values[variable.name]}
          describedBy={ids.secrets} onChange={value => setValue(variable.name, value)} />)}</tbody>
      </table></div> : null}
      {dropped.length > 0 ? <InlineAlert tone="info" title={t.droppedValues(dropped.join(', '))} /> : null}
    </WorkspaceSection>

    <WorkspaceSection title={t.previewTitle} description={t.previewNote}>
      <div className="configuration-editor-actions assignment-preview-actions">
        <button type="button" className="secondary-button" disabled={!profileId || !variables || preview.isPending} onClick={runPreview}>
          {preview.isPending ? t.previewing : t.previewAction}</button>
      </div>
      {preview.isError ? <InlineAlert tone="danger" title={describeError(preview.error, i18n)} /> : null}
      {preview.data ? <PreviewResult preview={preview.data} /> : null}
    </WorkspaceSection>

    {problem ? <InlineAlert tone="danger" title={problem} /> : null}
    {error ? <InlineAlert tone="danger" title={errorText(error, i18n)} /> : null}
    <div className="configuration-editor-actions">
      <button type="submit" className="primary-button" disabled={pending || disabled || !variables}>{pending ? pendingLabel : submitLabel}</button>
    </div>
  </form>
}

function ValueRow({ variable, value, describedBy, onChange }: {
  variable: ConfigurationVariable; value: string | undefined; describedBy: string; onChange: (value: string) => void
}) {
  const i18n = useI18n()
  const t = i18n.t.assignments
  const id = useId()
  const hint = value !== undefined ? null
    : variable.defaultValue !== null ? t.usingDefault(variable.defaultValue)
      : variable.required ? t.requiredNoDefault : t.notSet
  return <tr>
    <td><label htmlFor={id}><code className="technical-value">{variable.name}</code></label></td>
    <td>{i18n.t.configurations.types[variable.type] ?? variable.type}</td>
    <td>
      {variable.type === 'BOOLEAN'
        ? <select id={id} aria-label={t.valueLabel(variable.name)} value={value ?? ''} onChange={event => onChange(event.target.value)}>
          <option value="">{variable.defaultValue !== null ? t.usingDefault(variable.defaultValue) : t.notSet}</option>
          <option value="true">true</option>
          <option value="false">false</option>
        </select>
        : <input id={id} aria-label={t.valueLabel(variable.name)} className="technical-input" value={value ?? ''} maxLength={4096}
          inputMode={variable.type === 'INTEGER' ? 'numeric' : undefined} autoComplete="off" spellCheck={false}
          placeholder={variable.defaultValue ?? ''} aria-describedby={describedBy} onChange={event => onChange(event.target.value)} />}
      {hint && variable.type !== 'BOOLEAN' ? <small className="cell-secondary">{hint}</small> : null}
    </td>
    <td>{variable.description ?? <span className="muted-cell">—</span>}</td>
  </tr>
}

function PreviewResult({ preview }: { preview: ConfigurationAssignmentPreview }) {
  const i18n = useI18n()
  const t = i18n.t.assignments
  if (!preview.valid || preview.renderedPreview === null) {
    return <InlineAlert tone="warning" title={valueErrorText(preview.error?.code, preview.error?.variableName, i18n)} />
  }
  // Text, shown as text: never interpreted, never inserted as markup.
  return <section className="configuration-preview" aria-label={t.previewTitle}>
    <h3>{t.previewOf(i18n.t.configurations.version(preview.resolvedRevisionNumber))}</h3>
    <pre className="configuration-code" tabIndex={0}>{preview.renderedPreview}</pre>
  </section>
}

function valueErrorText(code: string | undefined, variableName: string | undefined, i18n: ReturnType<typeof useI18n>): string {
  const describe = code ? i18n.t.assignments.valueErrors[code] : undefined
  return describe ? describe(variableName ?? '') : i18n.t.errors.generic
}

function errorText(error: unknown, i18n: ReturnType<typeof useI18n>): string {
  if (error instanceof ApiError) {
    const body = error.body as { variableName?: unknown } | undefined
    const describe = i18n.t.assignments.valueErrors[error.code]
    if (describe) return describe(typeof body?.variableName === 'string' ? body.variableName : '')
  }
  return describeError(error, i18n)
}
