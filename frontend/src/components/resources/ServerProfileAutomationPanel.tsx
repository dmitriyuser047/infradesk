import { useEffect, useState } from 'react'
import { useAssignServerProfile, useObserveServerProfile, usePreviewServerProfile, useServerProfile, useServerProfileAutomation, useServerProfiles, useUnassignServerProfile } from '../../api/serverProfiles'
import { useStartProvisioning } from '../../api/provisioning'
import { IntegrationDialog } from '../integrations/IntegrationDialog'
import { InlineAlert, StatusIndicator, WorkspaceSection } from '../layout/WorkspacePrimitives'
import { useOrganizationPermissions } from '../auth/authorization'
import { useI18n } from '../../i18n'
import { ApiError } from '../../api/httpClient'
import type { ServerProfileContent, ServerProfilePlan } from '../../types/serverProfile'
import '../../styles/pages/server-profiles.css'

export function ServerProfileAutomationPanel({ organizationId, resourceId, resourceName, resourceActive = true, onRunQueued }: {
  organizationId: string; resourceId: string; resourceName: string; resourceActive?: boolean; onRunQueued?: (id: string) => void
}) {
  const i18n = useI18n(); const t = i18n.t.serverProfiles
  const permissions = useOrganizationPermissions(organizationId)
  const canManage = permissions.can('manageConfigurations')
  const canExecute = permissions.can('executeOperations')
  const [plan, setPlan] = useState<ServerProfilePlan | null>(null)
  const [selectedProfile, setSelectedProfile] = useState('')
  const [revisionNumber, setRevisionNumber] = useState(1)
  const [request, setRequest] = useState<{ planId: string; requestId: string } | null>(null)
  const automation = useServerProfileAutomation(organizationId, resourceId, permissions.can('readOrganization'))
  const assignedProfile = useServerProfile(organizationId, automation.data?.assignment?.profileId ?? null, canManage)
  const selectedProfileDetail = useServerProfile(organizationId, selectedProfile || null, canManage)
  const profiles = useServerProfiles(organizationId, false, canManage)
  const observe = useObserveServerProfile(organizationId, resourceId)
  const preview = usePreviewServerProfile(organizationId, resourceId)
  const assign = useAssignServerProfile(organizationId, resourceId)
  const unassign = useUnassignServerProfile(organizationId, resourceId)
  const start = useStartProvisioning(organizationId, resourceId)
  useEffect(() => { setRevisionNumber(automation.data?.assignment?.revisionNumber ?? 1) }, [automation.data?.assignment?.id, automation.data?.assignment?.revisionNumber])
  const blocked = !canManage || !canExecute || automation.data?.operationsBlocked === true || !resourceActive
  const status = automation.data?.state ?? 'UNOBSERVED'
  const profileErrors: Record<string, string> = { ...i18n.t.provisioning.errors, ...t.errors }
  const stepNames: Record<string, string> = { ...t.runSteps }
  const changeCodeNames: Record<string, string> = { ...t.changeCodes }
  const changeModuleNames: Record<string, string> = { ...t.changeModules }
  const failText = (error: unknown, fallback: string) => error instanceof ApiError ? profileErrors[error.code] ?? fallback : fallback
  const openPreview = async () => {
    const value = await preview.mutateAsync()
    setPlan(value)
    setRequest({ planId: value.run.id, requestId: crypto.randomUUID() })
  }
  const apply = async () => {
    if (!request) return
    const run = await start.mutateAsync(request)
    onRunQueued?.(run.id)
    setPlan(null)
    void automation.refetch()
  }
  return <WorkspaceSection title={t.assignment} className="server-profile-automation" actions={<StatusIndicator label={t.assessment[status]} tone={status === 'COMPLIANT' ? 'success' : status === 'DRIFTED' || status === 'APPLY_FAILED' || status === 'UNKNOWN' ? 'warning' : status === 'APPLYING' ? 'info' : 'neutral'} />}>
    {automation.isError ? <InlineAlert tone="warning" title={t.detailError} action={<button className="text-button" type="button" onClick={() => void automation.refetch()}>{i18n.t.common.retry}</button>} /> : null}
    {automation.data?.assignment ? <div className="form-grid"><p><strong>{automation.data.profile?.name ?? '—'}</strong></p><p>{t.currentRevision(automation.data.revision?.number ?? automation.data.assignment.revisionNumber)}</p></div> : <p>{t.unassigned}</p>}
    {automation.data?.assessment?.modules.length ? <div className="server-profile-module-statuses">{automation.data.assessment.modules.map(item => {
      const moduleKey = item.module as Exclude<keyof ServerProfileContent, 'schemaVersion'>
      const moduleContent = automation.data?.revision?.content[moduleKey] as { enabled: boolean } | undefined
      const enabled = moduleContent?.enabled
      return <div className="server-profile-module-status" key={item.module}><span>{changeModuleNames[item.module] ?? t.details}</span><StatusIndicator label={enabled === false ? t.notManaged : item.compliant ? t.assessment.COMPLIANT : t.assessment.DRIFTED} tone={enabled === false ? 'neutral' : item.compliant ? 'success' : 'warning'} size="small" /></div>
    })}</div> : null}
    {status === 'UNKNOWN' ? <InlineAlert tone="warning" title={t.assessment.UNKNOWN}>{t.unknown}</InlineAlert> : null}
    {status === 'APPLY_FAILED' ? <InlineAlert tone="danger" title={t.assessment.APPLY_FAILED}>{t.applyFailedDetail}</InlineAlert> : null}
    {canManage && !automation.data?.operationsBlocked ? automation.data?.assignment ? <div className="toolbar-actions">
      {canExecute ? <><button className="secondary-button" type="button" disabled={blocked || observe.isPending} onClick={() => void observe.mutateAsync().catch(() => undefined)}>{observe.isPending ? t.observing : t.observe}</button>
      <button className="secondary-button" type="button" disabled={blocked || preview.isPending} onClick={() => void openPreview().catch(() => undefined)}>{preview.isPending ? t.previewing : t.preview}</button></> : null}
      <label>{t.chooseRevision}<select value={revisionNumber} onChange={e => setRevisionNumber(Number(e.target.value))}>{(assignedProfile.data?.revisions ?? []).map(revision => <option key={revision.id} value={revision.number}>{t.currentRevision(revision.number)}</option>)}</select></label>
      <button className="secondary-button" type="button" disabled={!resourceActive || automation.data.revision?.number === revisionNumber || assign.isPending} onClick={() => void assign.mutateAsync({ profileId: automation.data!.assignment!.profileId, revisionNumber }).then(() => automation.refetch()).catch(() => undefined)}>{t.updatePin}</button>
      <button className="text-button" type="button" disabled={unassign.isPending} onClick={() => void unassign.mutateAsync().then(() => automation.refetch()).catch(() => undefined)}>{t.unassign}</button>
    </div> : resourceActive ? <div className="form-grid"><label>{t.chooseProfile}<select value={selectedProfile} onChange={e => { setSelectedProfile(e.target.value); const item = profiles.data?.items?.find(x => x.profile.id === e.target.value); setRevisionNumber(item?.profile.latestRevision ?? 1) }}><option value="">—</option>{profiles.data?.items?.filter(x => !x.profile.archived).map(x => <option value={x.profile.id} key={x.profile.id}>{x.profile.name}</option>)}</select></label><label>{t.chooseRevision}<select aria-label={t.chooseRevision} disabled={!selectedProfile} value={revisionNumber} onChange={e => setRevisionNumber(Number(e.target.value))}>{(selectedProfileDetail.data?.revisions ?? []).map(revision => <option key={revision.id} value={revision.number}>{t.currentRevision(revision.number)}</option>)}</select></label><button className="primary-button" type="button" disabled={!selectedProfile || !canManage || automation.data?.operationsBlocked || assign.isPending} onClick={() => void assign.mutateAsync({ profileId: selectedProfile, revisionNumber }).then(() => automation.refetch()).catch(() => undefined)}>{t.assign}</button></div> : <InlineAlert tone="info" title={t.inactiveTarget} /> : null}
    {!canManage ? <InlineAlert tone="info" title={t.permission} /> : canManage && !canExecute ? <InlineAlert tone="info" title={t.observePermission} /> : null}
    {!resourceActive ? <InlineAlert tone="info" title={t.inactiveTarget} /> : null}
    {automation.data?.operationsBlocked ? <InlineAlert tone="warning" title={t.runStates.RUNNING}>{t.operationsInProgress}</InlineAlert> : null}
    {observe.isError ? <InlineAlert tone="danger" title={t.observeError}>{failText(observe.error, t.observeError)}</InlineAlert> : null}
    {preview.isError ? <InlineAlert tone="danger" title={t.previewError}>{failText(preview.error, t.previewError)}</InlineAlert> : null}
    {assign.isError || unassign.isError ? <InlineAlert tone="danger" title={t.detailError}>{failText(assign.error ?? unassign.error, t.detailError)}</InlineAlert> : null}
    {plan ? <IntegrationDialog title={t.preview} busy={start.isPending} onClose={() => setPlan(null)} actions={<><button className="secondary-button" type="button" onClick={() => setPlan(null)}>{i18n.t.common.cancel}</button><button className="primary-button" type="button" disabled={start.isPending || plan.blockingProblems.length > 0 || blocked} onClick={() => void apply().catch(() => undefined)}>{t.apply}</button></>}>
      <dl className="property-grid"><div className="property-row"><dt>{t.target}</dt><dd>{resourceName}</dd></div><div className="property-row"><dt>{t.resourceKind}</dt><dd>{plan.resourceKind}</dd></div><div className="property-row"><dt>{t.connection}</dt><dd>{plan.connectionName}</dd></div><div className="property-row"><dt>{t.details}</dt><dd>{plan.profileName} · {t.currentRevision(plan.revisionNumber)}</dd></div>{plan.endpoint ? <div className="property-row"><dt>{t.endpoint}</dt><dd>{plan.endpoint}</dd></div> : null}</dl>
      {plan.dependencyPackages.length ? <p>{t.dependencies}: {plan.dependencyPackages.join(', ')}</p> : null}
      {plan.assessment.changes.some(change => change.module === 'limits') ? <InlineAlert tone="info" title={t.limits}>{t.limitsNote}</InlineAlert> : null}
      <p className="section-description">{t.emptyDescription}</p>
      <ol>{[...plan.steps].sort((a,b)=>a.position-b.position).map(step => <li key={`${step.position}:${step.kind}`}>{stepNames[step.kind] ?? t.runSteps.PREFLIGHT}</li>)}</ol>
      <h3>{t.changes}</h3>{plan.assessment.changes.map((change,i)=><div className="provisioning-step" key={`${change.module}:${change.code}:${i}`}><strong>{changeModuleNames[change.module] ?? t.changes}</strong><p>{changeCodeNames[change.code] ?? t.changeUnclassified}</p>{change.detail ? <p>{change.detail}</p> : null}<p>{change.before === null ? '—' : change.before} → {change.after === null ? '—' : change.after}</p></div>)}
      {plan.warnings.map((warning,i)=><InlineAlert key={i} tone="warning" title={t.warnings}>{profileErrors[warning] ?? t.warnings}</InlineAlert>)}
      {plan.blockingProblems.map((problem,i)=><InlineAlert key={i} tone="danger" title={t.blockers}>{profileErrors[problem] ?? profileErrors.PROVISIONING_PROFILE_PLAN_CHANGED}</InlineAlert>)}
      {start.isError ? <InlineAlert tone="danger" title={t.previewError}>{failText(start.error,t.previewError)}</InlineAlert> : null}
    </IntegrationDialog> : null}
  </WorkspaceSection>
}
