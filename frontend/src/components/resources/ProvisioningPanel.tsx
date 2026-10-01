import { useEffect, useRef, useState } from 'react'

import { useProvisioningHistory, useProvisioningRun, usePlanProvisioning, useStartProvisioning } from '../../api/provisioning'
import { useI18n } from '../../i18n'
import { IntegrationDialog } from '../integrations/IntegrationDialog'
import { InlineAlert, StatusIndicator, WorkspaceSection } from '../layout/WorkspacePrimitives'
import type { ProvisioningPlan, ProvisioningRun } from '../../types/provisioning'
import { ApiError } from '../../api/httpClient'

export function ProvisioningPanel({ organizationId, resourceId, resourceName, canRun }: {
  organizationId: string; resourceId: string; resourceName: string; canRun: boolean
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
  useEffect(() => {
    if (focusedRunId === null && history.data?.items?.[0]) setFocusedRunId(history.data.items[0].id)
  }, [focusedRunId, history.data])

  const stateText = (state: string) => text.states[state] ?? state
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
    setPlan(null)
  }

  return <WorkspaceSection title={text.title} className="provisioning-panel"
    actions={canRun ? <button type="button" className="secondary-button" disabled={planner.isPending}
      onClick={() => { void createPlan().catch(() => undefined) }}>{planner.isPending ? text.pending : text.plan}</button> : undefined}>
    {planner.isError ? <InlineAlert tone="danger" title={text.actionError}>{localizedFailure(planner.error, text.errors, text.actionError)}</InlineAlert> : null}
    {history.isError ? <InlineAlert tone="warning" title={text.loadError} /> : null}
    {history.isPending ? <p>{text.pending}</p> : null}
    {!history.isPending && history.data?.items?.length === 0 ? <p>{text.empty}</p> : null}
    {history.data?.items?.map(run => <div className="provisioning-run" key={run.id}>
      <span>{i18n.format.dateTime(run.createdAt)}</span>
      <StatusIndicator label={stateText(run.state)} tone={tone(run)} />
      <button type="button" className="text-button" onClick={() => setFocusedRunId(run.id)}>{t.common.open}</button>
    </div>)}
    {focusedRunId && detail.isPending ? <p>{text.pending}</p> : null}
    {detail.isError ? <InlineAlert tone="warning" title={text.loadError}
      action={<button type="button" className="text-button" onClick={() => void detail.refetch()}>{text.retry}</button>} /> : null}
    {detail.data ? <div className="provisioning-detail">
      <h3>{stateText(detail.data.run.state)}</h3>
      {detail.data.run.safeMessage || detail.data.run.state === 'UNKNOWN' || detail.data.run.failureCode ? <InlineAlert
        tone={detail.data.run.state === 'UNKNOWN' ? 'warning' : 'danger'}
        title={detail.data.run.state === 'UNKNOWN' ? stateText('UNKNOWN') : text.actionError}>
        {detail.data.run.state === 'UNKNOWN' ? text.unknown
          : detail.data.run.failureCode ? (text.errors[detail.data.run.failureCode] ?? text.actionError)
            : text.actionError}
      </InlineAlert> : null}
      {detail.data.steps.map(step => <div className="provisioning-step" key={step.id}>
        <div><strong>{text.stepKinds[step.kind] ?? step.displayName}</strong>
          <StatusIndicator label={text.stepStates[step.state] ?? step.state} tone={step.state === 'SUCCEEDED' ? 'success'
            : step.state === 'FAILED' ? 'danger' : step.state === 'UNKNOWN' ? 'warning' : 'neutral'} /></div>
        {Object.entries(step.facts).map(([key, value]) => <p key={key}>{text.factNames[key] ?? key}: {text.factValues[value] ?? value}</p>)}
      {step.failureCode ? <p>{errorText(text.errors, step.failureCode)}</p> : null}
        {step.outputTruncated ? <p>{text.truncated}</p> : null}
      </div>)}
    </div> : null}
    {plan ? <IntegrationDialog title={text.plan} busy={starter.isPending} onClose={() => setPlan(null)}
      actions={<><button type="button" className="secondary-button" disabled={starter.isPending} onClick={() => setPlan(null)}>{text.cancel}</button>
        <button type="button" className="primary-button" disabled={starter.isPending || plan.blockingProblems.length > 0}
          onClick={() => { void start().catch(() => undefined) }}>{starter.isPending ? text.starting : text.queue}</button></>}>
      <h3>{text.details}</h3>
      <p>{text.target}: {resourceName} · {plan.approvalInput.resourceType}</p>
      <p>{text.connection}: {plan.approvalInput.connectionId}</p>
      <p>{text.platform}: {plan.approvalInput.resourceKind || '—'}</p>
      <ul>{plan.steps.map(step => <li key={step.id}>{text.stepKinds[step.kind] ?? step.displayName}</li>)}</ul>
      {plan.warnings.map(warning => <InlineAlert tone="warning" title={text.warnings} key={warning}>{warning}</InlineAlert>)}
      {plan.blockingProblems.map(problem => <InlineAlert tone="danger" title={text.blocked} key={problem}>{problem}</InlineAlert>)}
      {starter.isError ? <InlineAlert tone="danger" title={text.actionError}>{localizedFailure(starter.error, text.errors, text.actionError)}</InlineAlert> : null}
    </IntegrationDialog> : null}
  </WorkspaceSection>
}

function errorText(errors: Record<string, string>, code: string | null) {
  if (!code) return ''
  return errors[code] ?? code
}

function localizedFailure(error: unknown, errors: Record<string, string>, fallback: string) {
  if (error instanceof ApiError) return errors[error.code] ?? fallback
  return fallback
}
