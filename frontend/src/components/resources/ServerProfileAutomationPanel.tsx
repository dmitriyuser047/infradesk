import { PackageProbeFindings } from './PackageProbeFindings'
import type { PackageProbeFinding } from '../../types/serverProfile'
﻿import { useEffect, useRef, useState } from 'react'
import { useAssignServerProfile, useObserveServerProfile, usePreviewServerProfile, useServerProfile, useServerProfileAutomation, useServerProfiles, useUnassignServerProfile } from '../../api/serverProfiles'
import { useProvisioningRun, useStartProvisioning } from '../../api/provisioning'
import { createRequestId } from '../../app/requestId'
import { readPendingSubmission, storePendingSubmission, type PendingSubmission } from '../../app/pendingSubmission'
import { IntegrationDialog } from '../integrations/IntegrationDialog'
import { InlineAlert, OperationProblem, PendingButton, PropertyGrid, StatusIndicator, WorkspaceSection } from '../layout/WorkspacePrimitives'
import { useOrganizationPermissions } from '../auth/authorization'
import { useI18n } from '../../i18n'
import { ApiError } from '../../api/httpClient'
import type { ServerProfileContent, ServerProfilePlan } from '../../types/serverProfile'
import '../../styles/pages/server-profiles.css'

type PreviewState = {
  plan: ServerProfilePlan
  requestId: string
}

export function ServerProfileAutomationPanel({ organizationId, resourceId, resourceName, resourceActive = true, onRunQueued }: {
  organizationId: string; resourceId: string; resourceName: string; resourceActive?: boolean; onRunQueued?: (id: string) => void
}) {
  const i18n = useI18n(); const t = i18n.t.serverProfiles
  const permissions = useOrganizationPermissions(organizationId)
  const canManage = permissions.can('manageConfigurations')
  const canExecute = permissions.can('executeOperations')
  const [previewState, setPreviewState] = useState<PreviewState | null>(null)
  const [workflowError, setWorkflowError] = useState(false)
  const submitting = useRef(false)
  const plan = previewState?.plan
  const [selectedProfile, setSelectedProfile] = useState('')
  const [revisionNumber, setRevisionNumber] = useState(1)
  const automation = useServerProfileAutomation(organizationId, resourceId, permissions.can('readOrganization'))
  const assignedProfile = useServerProfile(organizationId, automation.data?.assignment?.profileId ?? null, canManage)
  const selectedProfileDetail = useServerProfile(organizationId, selectedProfile || null, canManage)
  const profiles = useServerProfiles(organizationId, false, canManage)
  const observe = useObserveServerProfile(organizationId, resourceId)
  const preview = usePreviewServerProfile(organizationId, resourceId)
  const assign = useAssignServerProfile(organizationId, resourceId)
  const unassign = useUnassignServerProfile(organizationId, resourceId)
  const start = useStartProvisioning(organizationId, resourceId)
  const submissionKey = `server-profile-apply:${organizationId}:${resourceId}`
  const [unresolved, setUnresolved] = useState<PendingSubmission | null>(() => readPendingSubmission(submissionKey))
  const recovery = useProvisioningRun(organizationId, permissions.can('readOrganization') ? unresolved?.planId ?? null : null)
  useEffect(() => {
    if (!unresolved || !recovery.data || recovery.data.run.state === 'PLANNED') return
    storePendingSubmission(submissionKey, null); setUnresolved(null); setPreviewState(null)
    onRunQueued?.(recovery.data.run.id)
    void automation.refetch()
  }, [unresolved, recovery.data, submissionKey])
  useEffect(() => { setRevisionNumber(automation.data?.assignment?.revisionNumber ?? 1) }, [automation.data?.assignment?.id, automation.data?.assignment?.revisionNumber])
  const blocked = !canManage || !canExecute || automation.data?.operationsBlocked === true || !resourceActive
  const status = automation.data?.state ?? 'UNOBSERVED'
  const profileErrors: Record<string, string> = { ...i18n.t.provisioning.errors, ...t.errors }
  const stepNames: Record<string, string> = { ...t.runSteps }
  const changeCodeNames: Record<string, string> = { ...t.changeCodes }
  const changeModuleNames: Record<string, string> = { ...t.changeModules }
  const applyDisabledReason = plan?.blockingProblems.length ? i18n.t.common.blockedAction(plan.blockingProblems.length)
    : automation.data?.operationsBlocked ? t.operationsInProgress
      : !resourceActive ? t.inactiveTarget : !canManage ? t.permission : !canExecute ? t.observePermission : undefined
  const failText = (error: unknown, fallback: string) => error instanceof ApiError ? profileErrors[error.code] ?? fallback : fallback
  const openPreview = async () => {
    setWorkflowError(false)
    const value = await preview.mutateAsync()
    start.reset()
    setPreviewState({ plan: value, requestId: createRequestId() })
  }
  const submit = async (identity: PendingSubmission) => {
    if (submitting.current || start.isPending || blocked || (plan?.blockingProblems.length ?? 0) > 0) {
      setWorkflowError(true)
      return
    }
    submitting.current = true
    setWorkflowError(false)
    storePendingSubmission(submissionKey, identity)
    try {
      const run = await start.mutateAsync(identity)
      storePendingSubmission(submissionKey, null); setUnresolved(null)
      setPreviewState(null)
      onRunQueued?.(run.id)
      void automation.refetch()
    } catch (error) {
      if (!(error instanceof ApiError) || error.status >= 500 || error.status === 408) setUnresolved(identity)
      else { storePendingSubmission(submissionKey, null); setUnresolved(null) }
      // React Query owns the error shown at the operation actions.
    } finally { submitting.current = false }
  }
  return <WorkspaceSection title={t.assignment} className="server-profile-automation" actions={<StatusIndicator label={t.assessment[status]} tone={status === 'COMPLIANT' ? 'success' : status === 'APPLY_FAILED' ? 'danger' : status === 'DRIFTED' || status === 'UNKNOWN' ? 'warning' : status === 'APPLYING' ? 'info' : 'neutral'} />}>
    {automation.isError ? <InlineAlert tone="warning" title={t.detailError} action={<button className="text-button" type="button" onClick={() => void automation.refetch()}>{i18n.t.common.retry}</button>} /> : null}
    {automation.data?.assignment ? <div className="server-profile-current"><h3>{automation.data.profile?.name ?? '—'}</h3><p className="muted-copy">{t.currentRevision(automation.data.revision?.number ?? automation.data.assignment.revisionNumber)}</p><p>{t.stateSummary[status]}</p></div> : <p>{t.unassigned}</p>}
    <PackageProbeFindings findings={(automation.data?.observation?.content.packages as { findings?: PackageProbeFinding[] } | undefined)?.findings} />
    {status === 'UNKNOWN' ? <InlineAlert tone="warning" title={t.assessment.UNKNOWN}>{t.unknown}</InlineAlert> : null}
    {status === 'APPLY_FAILED' ? <InlineAlert tone="danger" title={t.assessment.APPLY_FAILED}>{t.applyFailedDetail}</InlineAlert> : null}
    {automation.data?.assessment?.modules.length ? <section className="workflow-group" aria-label={t.moduleSummary}><h3>{t.moduleSummary}</h3><div className="server-profile-module-statuses">{automation.data.assessment.modules.map(item => {
      const moduleKey = item.module as Exclude<keyof ServerProfileContent, 'schemaVersion'>
      const moduleContent = automation.data?.revision?.content[moduleKey] as { enabled: boolean } | undefined
      const enabled = moduleContent?.enabled
      return <div className="server-profile-module-status" key={item.module}><span>{changeModuleNames[item.module] ?? t.details}</span><StatusIndicator label={enabled === false ? t.notManaged : item.compliant ? t.assessment.COMPLIANT : t.assessment.DRIFTED} tone={enabled === false ? 'neutral' : item.compliant ? 'success' : 'warning'} size="small" /></div>
    })}</div></section> : null}
    {canManage && !automation.data?.operationsBlocked ? automation.data?.assignment ? <>
      {canExecute ? <section className="workflow-group" aria-label={t.workflowActions}><h3>{t.workflowActions}</h3><div className="action-group">
        <PendingButton type="button" pending={observe.isPending} pendingLabel={t.observing} disabled={blocked} onClick={() => void observe.mutateAsync().catch(() => undefined)}>{t.observe}</PendingButton>
        <PendingButton className="primary-button" type="button" pending={preview.isPending} pendingLabel={t.previewing} disabled={blocked || !!unresolved} onClick={() => void openPreview().catch(() => setWorkflowError(true))}>{t.preview}</PendingButton>
      </div></section> : null}
      <section className="workflow-group assignment-management" aria-label={t.assignmentManagement}><h3>{t.assignmentManagement}</h3><div className="action-group">
      <label>{t.chooseRevision}<select value={revisionNumber} onChange={e => setRevisionNumber(Number(e.target.value))}>{(assignedProfile.data?.revisions ?? []).map(revision => <option key={revision.id} value={revision.number}>{t.currentRevision(revision.number)}</option>)}</select></label>
      <button className="secondary-button" type="button" disabled={!resourceActive || automation.data.revision?.number === revisionNumber || assign.isPending} onClick={() => void assign.mutateAsync({ profileId: automation.data!.assignment!.profileId, revisionNumber }).then(() => automation.refetch()).catch(() => undefined)}>{t.updatePin}</button>
      </div><div className="destructive-actions"><button className="text-button danger-text" type="button" disabled={unassign.isPending} onClick={() => void unassign.mutateAsync().then(() => automation.refetch()).catch(() => undefined)}>{t.unassign}</button></div></section>
    </> : resourceActive ? <div className="form-grid"><label>{t.chooseProfile}<select value={selectedProfile} onChange={e => { setSelectedProfile(e.target.value); const item = profiles.data?.items?.find(x => x.profile.id === e.target.value); setRevisionNumber(item?.profile.latestRevision ?? 1) }}><option value="">—</option>{profiles.data?.items?.filter(x => !x.profile.archived).map(x => <option value={x.profile.id} key={x.profile.id}>{x.profile.name}</option>)}</select></label><label>{t.chooseRevision}<select aria-label={t.chooseRevision} disabled={!selectedProfile} value={revisionNumber} onChange={e => setRevisionNumber(Number(e.target.value))}>{(selectedProfileDetail.data?.revisions ?? []).map(revision => <option key={revision.id} value={revision.number}>{t.currentRevision(revision.number)}</option>)}</select></label><button className="primary-button" type="button" disabled={!selectedProfile || !canManage || automation.data?.operationsBlocked || assign.isPending} onClick={() => void assign.mutateAsync({ profileId: selectedProfile, revisionNumber }).then(() => automation.refetch()).catch(() => undefined)}>{t.assign}</button></div> : <InlineAlert tone="info" title={t.inactiveTarget} /> : null}
    {!canManage ? <InlineAlert tone="info" title={t.permission} /> : canManage && !canExecute ? <InlineAlert tone="info" title={t.observePermission} /> : null}
    {!resourceActive ? <InlineAlert tone="info" title={t.inactiveTarget} /> : null}
    {automation.data?.operationsBlocked ? <InlineAlert tone="warning" title={t.runStates.RUNNING}>{t.operationsInProgress}</InlineAlert> : null}
    {observe.isError ? <InlineAlert tone="danger" title={t.observeError}>{failText(observe.error, t.observeError)}</InlineAlert> : null}
    {preview.isError ? <InlineAlert tone="danger" title={t.previewError}>{failText(preview.error, t.previewError)}</InlineAlert> : null}
    {workflowError && !preview.isError ? <InlineAlert tone="danger" title={i18n.t.common.operationBlocked} /> : null}
    {assign.isError || unassign.isError ? <InlineAlert tone="danger" title={t.detailError}>{failText(assign.error ?? unassign.error, t.detailError)}</InlineAlert> : null}
    {!previewState && unresolved ? <InlineAlert tone="warning" title={i18n.t.common.unresolvedSubmission} action={<PendingButton pending={start.isPending} pendingLabel={t.assessment.APPLYING} disabled={blocked || !recovery.isSuccess} onClick={() => void submit(unresolved)}>{i18n.t.common.recoverSubmission}</PendingButton>} /> : null}
    {!previewState && start.isError ? <InlineAlert tone="danger" title={i18n.t.operations.startFailed}>{failText(start.error,i18n.t.common.operationBlocked)}</InlineAlert> : null}
    {recovery.isError ? <InlineAlert tone="danger" title={t.detailError} /> : null}
    {previewState && plan ? <IntegrationDialog title={t.preview} size="large" description={<>{resourceName} · {plan.profileName} · {t.currentRevision(plan.revisionNumber)}</>} busy={start.isPending} onClose={() => setPreviewState(null)}
      actionNote={applyDisabledReason}
      actionFeedback={start.isError ? <InlineAlert tone="danger" title={i18n.t.operations.startFailed}>{failText(start.error,i18n.t.common.operationBlocked)}</InlineAlert> : workflowError ? <InlineAlert tone="danger" title={i18n.t.common.operationBlocked} /> : undefined}
      actions={<><button className="secondary-button" type="button" disabled={start.isPending} onClick={() => setPreviewState(null)}>{i18n.t.common.cancel}</button><PendingButton className="primary-button" type="button" pending={start.isPending} pendingLabel={t.assessment.APPLYING} disabled={plan.blockingProblems.length > 0 || blocked} onClick={() => void submit({planId:previewState.plan.run.id,requestId:previewState.requestId})}>{t.apply}</PendingButton></>}>
      <PackageProbeFindings findings={plan.packageFindings} />
      {plan.blockingProblems.map((problem,i)=><OperationProblem key={i} code={problem} messages={profileErrors} title={t.blockers} />)}
      {plan.warnings.map((warning,i)=><OperationProblem key={i} code={warning} messages={profileErrors} title={t.warnings} tone="warning" />)}
      {plan.assessment.changes.some(change => change.module === 'limits') ? <InlineAlert tone="info" title={t.limits}>{t.limitsNote}</InlineAlert> : null}
      <section className="workflow-group" aria-label={t.changes}><h3>{t.changes}</h3>{plan.assessment.changes.length ? plan.assessment.changes.map((change,i)=><div className="operation-change" key={`${change.module}:${change.code}:${i}`}><strong>{changeModuleNames[change.module] ?? t.changes}</strong><p>{changeCodeNames[change.code] ?? t.changeUnclassified}</p>{change.detail ? <p>{change.detail}</p> : null}<dl className="operation-change-values"><div><dt>{t.before}</dt><dd className="technical-value">{change.before === null ? '—' : change.before}</dd></div><div><dt>{t.after}</dt><dd className="technical-value">{change.after === null ? '—' : change.after}</dd></div></dl></div>) : <p>{t.noPlannedChanges}</p>}</section>
      <details className="operation-disclosure"><summary>{i18n.t.common.executionSteps(plan.steps.length)}</summary><ol>{[...plan.steps].sort((a,b)=>a.position-b.position).map(step => <li key={`${step.position}:${step.kind}`}>{stepNames[step.kind] ?? i18n.t.common.technicalDetails}</li>)}</ol></details>
      <details className="operation-disclosure"><summary>{i18n.t.common.technicalDetails}</summary><PropertyGrid columns={2} items={[
        {label:t.target,value:resourceName}, {label:t.connection,value:plan.connectionName},
        {label:t.resourceKind,value:plan.resourceKind,technical:true},
        {label:t.details,value:`${plan.profileName} · ${t.currentRevision(plan.revisionNumber)}`},
        ...(plan.endpoint ? [{label:t.endpoint,value:plan.endpoint,technical:true}] : []),
      ]} />{plan.dependencyPackages.length ? <p>{t.dependencies}: <span className="technical-value">{plan.dependencyPackages.join(', ')}</span></p> : null}<p className="muted-copy">{t.emptyDescription}</p></details>
    </IntegrationDialog> : null}
  </WorkspaceSection>
}
