import { useEffect, useRef, useState } from 'react'

import { useProvisioningHistory, useProvisioningRun, usePlanProvisioning, useStartProvisioning } from '../../api/provisioning'
import { useI18n } from '../../i18n'
import { IntegrationDialog } from '../integrations/IntegrationDialog'
import { InlineAlert, OperationProblem, PendingButton, StatusIndicator, WorkspaceSection } from '../layout/WorkspacePrimitives'
import type { ProvisioningPlan, ProvisioningRun } from '../../types/provisioning'
import { ApiError } from '../../api/httpClient'

export function ProvisioningPanel({ organizationId, resourceId, resourceName, canRun, focusRunId, onRunQueued }: {
  organizationId: string; resourceId: string; resourceName: string; canRun: boolean; focusRunId?: string | null; onRunQueued?: (id: string) => void
}) {
  const i18n = useI18n()
  const { t } = i18n
  const text = t.provisioning
  const history = useProvisioningHistory(organizationId, resourceId)
  const planner = usePlanProvisioning(organizationId)
  const starter = useStartProvisioning(organizationId, resourceId)
  const [plan, setPlan] = useState<ProvisioningPlan | null>(null)
  const [focusedRunId, setFocusedRunId] = useState<string | null>(null)
  const requestId = useRef<{ planId: string; requestId: string } | null>(null)
  const detail = useProvisioningRun(organizationId, focusedRunId)
  useEffect(() => { if (focusRunId) setFocusedRunId(focusRunId) }, [focusRunId])
  useEffect(() => {
    if (focusedRunId === null && history.data?.items?.[0]) setFocusedRunId(history.data.items[0].id)
  }, [focusedRunId, history.data])

  const stateText = (state: string) => text.states[state] ?? state
  const profileStepNames: Record<string, string> = { ...t.serverProfiles.runSteps }
  const profileFactNames: Record<string, string> = { ...t.serverProfiles.factNames }
  const profileFactValues: Record<string, string> = { ...t.serverProfiles.factValues }
  const knownErrors: Record<string, string> = { ...text.errors, ...t.serverProfiles.errors }
  const stepName = (kind: string, safeName: string) => text.stepKinds[kind] ?? profileStepNames[kind] ?? safeName
  const factLines = (facts: Record<string, string>) => Object.entries(facts).flatMap(([key, value]) => {
    const name = text.factNames[key] ?? profileFactNames[key]
    if (!name) return []
    const translated = text.factValues[value] ?? profileFactValues[value]
    const numeric = ['memoryMiB', 'diskFreeMiB'].includes(key) && /^(0|[1-9][0-9]{0,7})$/.test(value)
    return translated || numeric ? [{ key, name, value: translated ?? value }] : []
  })
  const tone = (run: ProvisioningRun) => run.state === 'SUCCEEDED' ? 'success'
    : run.state === 'FAILED' ? 'danger' : run.state === 'UNKNOWN' ? 'warning'
      : run.state === 'RUNNING' ? 'info' : 'neutral'

  const createPlan = async () => {
    const value = await planner.mutateAsync(resourceId)
    setPlan(value)
    requestId.current = { planId: value.run.id, requestId: crypto.randomUUID() }
  }
  const start = async () => {
    if (!requestId.current) return
    const queued = await starter.mutateAsync(requestId.current)
    setFocusedRunId(queued.id)
    onRunQueued?.(queued.id)
    setPlan(null)
  }

  return <WorkspaceSection title={text.title} className="provisioning-panel"
    actions={canRun ? <PendingButton type="button" pending={planner.isPending} pendingLabel={text.pending}
      onClick={() => { void createPlan().catch(() => undefined) }}>{text.plan}</PendingButton> : undefined}>
    {planner.isError ? <InlineAlert tone="danger" title={text.actionError}>{localizedFailure(planner.error, text.errors, text.actionError)}</InlineAlert> : null}
    {history.isError ? <InlineAlert tone="warning" title={text.loadError} /> : null}
    {history.isPending ? <p>{text.pending}</p> : null}
    {!history.isPending && history.data?.items?.length === 0 ? <p>{text.empty}</p> : null}
    {focusedRunId && detail.isPending ? <p>{text.pending}</p> : null}
    {detail.isError ? <InlineAlert tone="warning" title={text.loadError}
      action={<button type="button" className="text-button" onClick={() => void detail.refetch()}>{text.retry}</button>} /> : null}
    {detail.data ? <section className="provisioning-detail workflow-group" aria-label={t.common.selectedRun}>
      <div className="operation-heading"><h3>{t.common.selectedRun}</h3><StatusIndicator label={stateText(detail.data.run.state)} tone={tone(detail.data.run)} /></div>
      <p className="muted-copy">{i18n.format.dateTime(detail.data.run.createdAt)}</p>
      {detail.data.run.safeMessage || detail.data.run.state === 'UNKNOWN' || detail.data.run.failureCode ? <InlineAlert
        tone={detail.data.run.state === 'UNKNOWN' ? 'warning' : detail.data.run.state === 'FAILED' ? 'danger' : 'info'}
        title={stateText(detail.data.run.state)}>
        {detail.data.run.state === 'UNKNOWN' ? text.unknown
          : detail.data.run.failureCode ? (knownErrors[detail.data.run.failureCode] ?? t.common.operationBlocked)
            : stateText(detail.data.run.state)}
      </InlineAlert> : null}
      <ol className="operation-timeline">{detail.data.steps.map(step => <li className="provisioning-step" data-state={step.state} key={step.id}>
        <div><strong>{stepName(step.kind, step.displayName)}</strong>
          <StatusIndicator label={text.stepStates[step.state] ?? step.state} tone={step.state === 'SUCCEEDED' ? 'success'
            : step.state === 'FAILED' ? 'danger' : step.state === 'UNKNOWN' ? 'warning' : step.state === 'RUNNING' ? 'info' : 'neutral'} /></div>
        {factLines(step.facts).map(({ key, name, value }) => <p key={key}>{name}: {value}</p>)}
      {step.failureCode ? <OperationProblem code={step.failureCode} messages={knownErrors} title={text.stepStates[step.state] ?? text.actionError} tone={step.state === 'UNKNOWN' ? 'warning' : 'danger'} /> : null}
        {step.outputTruncated ? <p>{text.truncated}</p> : null}
      </li>)}</ol>
    </section> : null}
    {history.data?.items?.length ? <details className="operation-disclosure provisioning-history"><summary>{t.common.operationHistory} ({history.data.items.length})</summary>{history.data.items.map(run => <div className="provisioning-run" key={run.id}>
      <span>{t.serverProfiles.runKind[run.inputSnapshot.runKind]}</span>
      <time dateTime={run.createdAt}>{i18n.format.dateTime(run.createdAt)}</time>
      <StatusIndicator label={stateText(run.state)} tone={tone(run)} />
      <button type="button" className="text-button" aria-current={focusedRunId === run.id ? 'true' : undefined} onClick={() => setFocusedRunId(run.id)}>{t.common.open}</button>
    </div>)}</details> : null}
    {plan ? <IntegrationDialog title={text.plan} size="large" description={resourceName} busy={starter.isPending} onClose={() => setPlan(null)}
      actionNote={plan.blockingProblems.length ? t.common.blockedAction(plan.blockingProblems.length) : undefined}
      actions={<><button type="button" className="secondary-button" disabled={starter.isPending} onClick={() => setPlan(null)}>{text.cancel}</button>
        <PendingButton type="button" className="primary-button" pending={starter.isPending} pendingLabel={text.starting} disabled={plan.blockingProblems.length > 0}
          onClick={() => { void start().catch(() => undefined) }}>{text.queue}</PendingButton></>}>
      {plan.blockingProblems.map((problem,i) => <OperationProblem code={problem} messages={knownErrors} title={text.blocked} key={i} />)}
      {plan.warnings.map((warning,i) => <OperationProblem code={warning} messages={knownErrors} title={text.warnings} tone="warning" key={i} />)}
      <details className="operation-disclosure" open><summary>{t.common.executionSteps(plan.steps.length)}</summary><ol>{plan.steps.map(step => <li key={step.id}>{stepName(step.kind, step.displayName)}</li>)}</ol></details>
      <details className="operation-disclosure"><summary>{t.common.technicalDetails}</summary>
      <p>{text.target}: {resourceName}</p>
      <p>{text.connection}: {plan.connectionName}</p>
      <p>{text.resourceKind}: {plan.approvalInput.resourceKind || '—'}</p>
      </details>
      {starter.isError ? <InlineAlert tone="danger" title={text.actionError}>{localizedFailure(starter.error, text.errors, text.actionError)}</InlineAlert> : null}
    </IntegrationDialog> : null}
  </WorkspaceSection>
}

function localizedFailure(error: unknown, errors: Record<string, string>, fallback: string) {
  if (error instanceof ApiError) return errors[error.code] ?? fallback
  return fallback
}
