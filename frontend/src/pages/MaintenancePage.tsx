import { useState, type FormEvent } from 'react'
import { useParams } from 'react-router-dom'
import { Wrench } from 'lucide-react'

import { useCancelMaintenanceWindow, useCreateMaintenanceWindow, useMaintenanceWindows } from '../api/maintenance'
import { useEnvironments, useProjects } from '../api/navigation'
import { useEnvironmentResources } from '../api/resources'
import { useOrganizationPermissions } from '../components/auth/authorization'
import { AppShell } from '../components/layout/AppShell'
import { EmptyWorkspaceState, InlineAlert, StatusIndicator, WorkspaceHeader, WorkspaceSection, type StatusTone } from '../components/layout/WorkspacePrimitives'
import { useI18n } from '../i18n'
import { describeError } from '../i18n/errors'
import { MaintenanceWindowState, type MaintenanceWindowResponse } from '../types/maintenance'
import { InvalidRoutePage } from './InvalidRoutePage'
import '../styles/pages/maintenance.css'

export function MaintenancePage() {
  const { organizationId } = useParams()
  if (!organizationId) return <InvalidRoutePage />
  return <MaintenanceContent organizationId={organizationId} />
}

/**
 * Planned maintenance: while a window covers a server and what runs on it, monitoring keeps
 * recording incidents but nobody is notified about them.
 */
function MaintenanceContent({ organizationId }: { organizationId: string }) {
  const i18n = useI18n()
  const t = i18n.t.maintenance
  const permissions = useOrganizationPermissions(organizationId)
  const canManage = permissions.can('manageMonitoring')
  const windows = useMaintenanceWindows(organizationId)
  const cancel = useCancelMaintenanceWindow(organizationId)
  const list = windows.data?.windows ?? []

  return <AppShell><div className="workspace-page">
    <WorkspaceHeader title={t.title} subtitle={t.subtitle} />
    {canManage ? <PlanWindowForm organizationId={organizationId} /> : null}
    {windows.isError ? <InlineAlert tone="danger" title={t.loadError}
      action={<button className="secondary-button" type="button" onClick={() => windows.refetch()}>{i18n.t.common.retry}</button>}>
      {describeError(windows.error, i18n)}</InlineAlert> : null}
    {cancel.isError ? <InlineAlert tone="danger" title={t.cancelFailed}>{describeError(cancel.error, i18n)}</InlineAlert> : null}
    {windows.isPending ? <div className="row-skeleton" aria-label={t.loading}><span /><span /></div> : null}
    {windows.isSuccess && list.length === 0 ? <EmptyWorkspaceState icon={Wrench} title={t.empty} detail={t.emptyDetail} /> : null}
    {list.length > 0 ? <WorkspaceSection title={t.listTitle} actions={<span className="resource-count">{list.length}</span>}>
      <ul className="maintenance-list">{list.map(window => <MaintenanceRow key={window.id} window={window}
        canCancel={canManage && (window.state === MaintenanceWindowState.scheduled || window.state === MaintenanceWindowState.active)}
        cancelling={cancel.isPending && cancel.variables === window.id}
        onCancel={() => { if (globalThis.confirm(t.cancelConfirm(window.resource.name))) cancel.mutate(window.id) }} />)}</ul>
    </WorkspaceSection> : null}
  </div></AppShell>
}

function MaintenanceRow({ window, canCancel, cancelling, onCancel }: {
  window: MaintenanceWindowResponse; canCancel: boolean; cancelling: boolean; onCancel: () => void
}) {
  const i18n = useI18n()
  const t = i18n.t.maintenance
  const tones: Record<string, StatusTone> = { ACTIVE: 'warning', SCHEDULED: 'info', FINISHED: 'neutral', CANCELLED: 'neutral' }
  return <li className={`maintenance-row maintenance-${window.state.toLowerCase()}`}>
    <div className="maintenance-main">
      <div className="maintenance-subject"><strong>{window.resource.name}</strong>
        <span className="maintenance-type">{i18n.t.resources.types[window.resource.resourceTypeCode] ?? window.resource.resourceTypeCode}</span>
        <StatusIndicator label={t.states[window.state] ?? window.state} tone={tones[window.state] ?? 'neutral'} size="small" /></div>
      <p className="maintenance-reason">{window.reason}</p>
      <span className="maintenance-meta">
        <time dateTime={window.startsAt}>{i18n.format.dateTime(window.startsAt)}</time> — <time dateTime={window.effectiveEnd}>
          {i18n.format.dateTime(window.effectiveEnd)}</time>
        {' · '}{window.cancelledByName ? t.cancelledBy(window.cancelledByName) : t.plannedBy(window.createdByName)}
      </span>
    </div>
    {canCancel ? <button className="secondary-button" type="button" disabled={cancelling} aria-busy={cancelling} onClick={onCancel}>
      {window.state === MaintenanceWindowState.active ? t.endNow : t.cancel}</button> : null}
  </li>
}

/** `datetime-local` speaks local time without a zone; the API speaks instants. */
function localInput(date: Date): string {
  const shifted = new Date(date.getTime() - date.getTimezoneOffset() * 60_000)
  return shifted.toISOString().slice(0, 16)
}

function PlanWindowForm({ organizationId }: { organizationId: string }) {
  const i18n = useI18n()
  const t = i18n.t.maintenance
  const [projectId, setProjectId] = useState<string | null>(null)
  const [environmentId, setEnvironmentId] = useState<string>('')
  const [resourceId, setResourceId] = useState('')
  const [startsAt, setStartsAt] = useState(() => localInput(new Date()))
  const [endsAt, setEndsAt] = useState(() => localInput(new Date(Date.now() + 60 * 60_000)))
  const [reason, setReason] = useState('')
  const projects = useProjects(organizationId)
  const environments = useEnvironments(organizationId, projectId)
  const resources = useEnvironmentResources(organizationId, environmentId || undefined)
  const create = useCreateMaintenanceWindow(organizationId)
  // Servers first: a window on a server covers what runs on it.
  const candidates = [...(resources.data ?? [])].filter(resource => resource.active)
    .sort((a, b) => Number(b.resourceTypeCode === 'NODE') - Number(a.resourceTypeCode === 'NODE') || a.name.localeCompare(b.name))

  const submit = (event: FormEvent) => {
    event.preventDefault()
    create.mutate({ resourceId, startsAt: new Date(startsAt).toISOString(), endsAt: new Date(endsAt).toISOString(), reason: reason.trim() },
      { onSuccess: () => { setReason(''); setResourceId('') } })
  }

  return <form className="workspace-form" onSubmit={submit}>
    <WorkspaceSection title={t.planTitle} description={t.planHint}><div className="field-grid">
      <label>{i18n.t.context.project} <select required value={projectId ?? ''} onChange={event => {
        setProjectId(event.target.value || null); setEnvironmentId(''); setResourceId('')
      }}><option value="">{t.chooseProject}</option>
        {(projects.data ?? []).map(project => <option key={project.id} value={project.id}>{project.name}</option>)}</select></label>
      <label>{i18n.t.context.environment} <select required disabled={!projectId} value={environmentId} onChange={event => {
        setEnvironmentId(event.target.value); setResourceId('')
      }}><option value="">{t.chooseEnvironment}</option>
        {(environments.data ?? []).map(environment => <option key={environment.id} value={environment.id}>{environment.name}</option>)}</select></label>
      <label>{t.resource} <select required disabled={!environmentId} value={resourceId} onChange={event => setResourceId(event.target.value)}>
        <option value="">{t.chooseResource}</option>
        {candidates.map(resource => <option key={resource.id} value={resource.id}>
          {resource.name} · {i18n.t.resources.types[resource.resourceTypeCode] ?? resource.resourceTypeCode}</option>)}</select></label>
      <label>{t.startsAt} <input type="datetime-local" required value={startsAt} onChange={event => setStartsAt(event.target.value)} /></label>
      <label>{t.endsAt} <input type="datetime-local" required value={endsAt} onChange={event => setEndsAt(event.target.value)} /></label>
      <label className="maintenance-reason-field">{t.reason} <input required maxLength={500} value={reason}
        placeholder={t.reasonPlaceholder} onChange={event => setReason(event.target.value)} /></label>
    </div></WorkspaceSection>
    {create.isError ? <InlineAlert tone="danger" title={t.createFailed}>{describeError(create.error, i18n)}</InlineAlert> : null}
    {create.isSuccess ? <InlineAlert tone="success" title={t.created} /> : null}
    <div className="form-toolbar"><button className="primary-button" type="submit" disabled={create.isPending || !resourceId || !reason.trim()}
      aria-busy={create.isPending}>{create.isPending ? i18n.t.common.creating : t.submit}</button></div>
  </form>
}
