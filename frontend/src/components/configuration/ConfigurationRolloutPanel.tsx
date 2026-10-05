import { useEffect, useId, useRef, useState } from 'react'

import { useConfigurationAssignmentPages } from '../../api/configurationAssignments'
import { createRequestId } from '../../app/requestId'
import { ApiError } from '../../api/httpClient'
import {
  useCancelRollout,
  useCreateRollout,
  usePromoteAssignments,
  usePromotionPreview,
  useRollout,
  useRolloutPreflight,
} from '../../api/configurationRollouts'
import { useResourceContext } from '../../api/infrastructure'
import { useI18n } from '../../i18n'
import { describeError } from '../../i18n/errors'
import type { ConfigurationAssignment } from '../../types/configurationAssignment'
import {
  ActiveRolloutStates,
  type RollbackMode,
  type RolloutDetail,
  type RolloutItem,
  type RolloutStrategy,
  type RolloutTarget,
  type ApprovedRolloutTarget,
  type RolloutPreflight,
} from '../../types/configurationRollout'
import { InlineAlert, StatusIndicator, WorkspaceSection } from '../layout/WorkspacePrimitives'
import { EmptyExecution, ExecutionFields, executionOf } from './ConfigurationDeploymentPanel'
import { failureText, rolloutItemTone, rolloutTone, validExecution } from './deploymentPresentation'

function SourcePicker({ organizationId, assignment, value, onChange }: {
  organizationId: string; assignment: ConfigurationAssignment; value: string; onChange: (value: string) => void
}) {
  const t = useI18n().t.rollouts
  const id = useId()
  const context = useResourceContext(organizationId, assignment.resource.id)
  const sources = (context.data?.sourceConnections ?? []).filter(source => source.active && source.connectorType === 'SSH')
  // One source is chosen for the user; with several, the user must choose explicitly.
  useEffect(() => { if (!value && sources.length === 1) onChange(sources[0].id) }, [value, sources, onChange])
  return <span className="rollout-source">
    <label className="visually-hidden" htmlFor={id}>{`${t.connection} ${assignment.resource.name}`}</label>
    <select id={id} value={value} onChange={event => onChange(event.target.value)}>
      {sources.length !== 1 ? <option value="">{t.chooseConnection}</option> : null}
      {sources.map(source => <option key={source.id} value={source.id}>{source.name}</option>)}
    </select>
  </span>
}

const ItemMarks: Record<RolloutItem['state'], string> = {
  PENDING: '○', DEPLOYING: '●', SUCCEEDED: '✓', FAILED: '✕', ROLLED_BACK: '↺', SKIPPED: '–',
}

/** Groups items the way the orchestrator launches them: the canary alone, then fixed-size batches. */
function groups(detail: RolloutDetail): Array<{ key: string; label: (t: ReturnType<typeof useI18n>['t']['rollouts']) => string; items: RolloutItem[] }> {
  const { canaryCount, batchSize } = detail.strategy
  const ordered = [...detail.items].sort((a, b) => a.position - b.position)
  const result: Array<{ key: string; label: (t: ReturnType<typeof useI18n>['t']['rollouts']) => string; items: RolloutItem[] }> = []
  const canary = ordered.filter(item => item.position < canaryCount)
  if (canary.length > 0) result.push({ key: 'canary', label: t => t.canaryGroup, items: canary })
  const rest = ordered.filter(item => item.position >= canaryCount)
  for (let index = 0; index < rest.length; index += batchSize) {
    const items = rest.slice(index, index + batchSize)
    const number = index / batchSize + (canary.length > 0 ? 2 : 1)
    const pending = items.every(item => item.state === 'PENDING')
    result.push({ key: `batch-${number}`, label: t => pending ? t.pendingGroup : t.batchGroup(number), items })
  }
  return result
}

/** A rollout as it runs: every node's state, why a node failed, and whether it was rolled back. */
export function RolloutStatusView({ organizationId, rolloutId, onRetryFailed }: {
  organizationId: string
  rolloutId: string
  onRetryFailed?: (assignmentIds: string[]) => void
}) {
  const i18n = useI18n()
  const t = i18n.t.rollouts
  const rollout = useRollout(organizationId, rolloutId)
  const cancel = useCancelRollout(organizationId)
  if (rollout.isPending) return <div className="row-skeleton" aria-label={i18n.t.common.loading}><span /><span /></div>
  if (rollout.isError) return <InlineAlert tone="danger" title={describeError(rollout.error, i18n)} />
  const detail = rollout.data
  const active = ActiveRolloutStates.includes(detail.state)
  const failed = detail.items.filter(item => item.state === 'FAILED' || item.state === 'ROLLED_BACK')
  const rollbackFailed = detail.items.some(item => item.deployment?.state === 'ROLLBACK_FAILED')
  return <div className="rollout-status" role="status" aria-live="polite">
    <div className="deployment-status-line">
      <strong>{i18n.t.configurations.version(detail.profileRevisionNumber)}</strong>
      <StatusIndicator label={t.state[detail.state]} tone={rolloutTone(detail.state)} />
      <span>{t.progress(detail.counts.succeeded, detail.counts.items)}</span>
      {detail.state === 'PAUSED' && detail.nextActionAt ? <span>{t.pausedUntil(i18n.format.time(detail.nextActionAt))}</span> : null}
    </div>
    {rollbackFailed ? <InlineAlert tone="danger" title={i18n.t.deployments.states.ROLLBACK_FAILED}>
      {i18n.t.deployments.rollbackFailedDetail}</InlineAlert> : null}
    {groups(detail).map(group => <section key={group.key} className="rollout-group" aria-label={group.label(t)}>
      <h4>{group.label(t)}</h4>
      <ul className="rollout-items">{group.items.map(item => {
        const failure = failureText(item.deployment?.failureCode, i18n)
        const deploymentState = item.deployment?.state
        return <li key={item.id} className="rollout-item">
          <span aria-hidden className={`rollout-mark status-${rolloutItemTone(item.state)}`}>{ItemMarks[item.state]}</span>
          <span className="rollout-node">{item.resource.name}</span>
          <StatusIndicator label={t.itemState[item.state]} tone={rolloutItemTone(item.state)} />
          {item.state === 'DEPLOYING' && item.deployment?.phase
            ? <small>{i18n.t.deployments.progress[item.deployment.phase]}</small> : null}
          {failure && item.state !== 'SUCCEEDED' ? <small className="rollout-failure">{failure}</small> : null}
          {deploymentState === 'ROLLED_BACK' ? <small>{i18n.t.deployments.rolledBackDetail}</small> : null}
          {deploymentState === 'ROLLBACK_FAILED'
            ? <StatusIndicator label={i18n.t.deployments.states.ROLLBACK_FAILED} tone="danger" /> : null}
        </li>
      })}</ul>
    </section>)}
    {!active && detail.state !== 'SUCCEEDED' ? <p>{t.stopped}</p> : null}
    <div className="configuration-editor-actions">
      {active && !detail.cancelRequested ? <>
        <button type="button" className="secondary-button" disabled={cancel.isPending}
          onClick={() => cancel.mutate({ id: rolloutId, rollbackApplied: false })}>{cancel.isPending ? t.cancelling : t.cancel}</button>
        <button type="button" className="danger-button" disabled={cancel.isPending}
          onClick={() => { if (window.confirm(t.rollbackAllConfirm)) cancel.mutate({ id: rolloutId, rollbackApplied: true }) }}>
          {t.rollbackAll}</button>
      </> : null}
      {!active && failed.length > 0 && onRetryFailed ? <button type="button" className="secondary-button"
        onClick={() => onRetryFailed(failed.map(item => item.assignmentId))}>{t.retryFailed}</button> : null}
    </div>
    {cancel.isError ? <InlineAlert tone="danger" title={describeError(cancel.error, i18n)} /> : null}
  </div>
}

type Step = 1 | 2 | 3 | 4 | 5
type RolloutSubmission = { profileId: string; revisionNumber: number; requestId: string
  strategy: RolloutStrategy; targets: ApprovedRolloutTarget[] }

function savedRolloutRequest(key: string, profileId: string): RolloutSubmission | null {
  try {
    const raw = sessionStorage.getItem(key)
    if (!raw) return null
    const request = JSON.parse(raw) as RolloutSubmission
    if (request.profileId === profileId && typeof request.requestId === 'string' && Number.isInteger(request.revisionNumber) &&
      request.strategy && Array.isArray(request.targets) && request.targets.length > 0 && request.targets.every(target =>
        typeof target.assignmentId === 'string' && Number.isInteger(target.expectedVersion) && typeof target.connectionId === 'string' &&
        typeof target.desiredSha256 === 'string' && typeof target.connectionUpdatedAt === 'string' &&
        typeof target.expectedRemoteMissing === 'boolean' &&
        (target.expectedRemoteSha256 === null || typeof target.expectedRemoteSha256 === 'string') && validExecution(target.execution))) return request
  } catch { /* Session storage can be unavailable; retain the mounted panel's identity. */ }
  return null
}

function storeRolloutRequest(key: string, request: RolloutSubmission | null) {
  try {
    if (request) sessionStorage.setItem(key, JSON.stringify(request))
    else sessionStorage.removeItem(key)
  } catch { /* A mounted wizard still keeps the approved request in memory. */ }
}

/**
 * Explicit nodes, a compatibility check, one all-or-nothing promotion of the desired state, a
 * read-only check of every server, then a rollout that applies exactly what was checked.
 */
function RolloutWizard({ organizationId, profileId, latestRevisionNumber, initialRevisionNumber, initialSelection, preselectRuleId,
  onStarted }: {
  organizationId: string; profileId: string; latestRevisionNumber: number; initialRevisionNumber?: number
  initialSelection: string[]; preselectRuleId?: string | null; onStarted: (rolloutId: string) => void
}) {
  const i18n = useI18n()
  const t = i18n.t.rollouts
  const assignments = useConfigurationAssignmentPages(organizationId, { profileId }, true)
  const rows = assignments.data?.pages.flat() ?? []
  const [step, setStep] = useState<Step>(1)
  const [revisionNumber, setRevisionNumber] = useState(Math.min(initialRevisionNumber ?? latestRevisionNumber, latestRevisionNumber))
  const [selectedIds, setSelectedIds] = useState<string[]>(initialSelection)
  const [sources, setSources] = useState<Record<string, string>>({})
  const [promotedVersions, setPromotedVersions] = useState<Record<string, number>>({})
  const [execution, setExecution] = useState(EmptyExecution)
  const [strategy, setStrategy] = useState<RolloutStrategy>({ canaryCount: 1, batchSize: 1, pauseSeconds: 0,
    stopOnFailure: true, rollbackMode: 'FAILED_TARGET_ONLY' })
  // A rule's nodes, preselected once they load: the rule was promoted, now it is rolled out.
  const [preselected, setPreselected] = useState(false)
  useEffect(() => {
    if (preselected || !preselectRuleId || !assignments.isSuccess) return
    setPreselected(true)
    setSelectedIds(old => old.length > 0 ? old : rows.filter(row => row.rule?.id === preselectRuleId).map(row => row.id))
  }, [preselected, preselectRuleId, assignments.isSuccess, rows])
  const promotionPreview = usePromotionPreview(organizationId, profileId)
  const promote = usePromoteAssignments(organizationId, profileId)
  const preflight = useRolloutPreflight(organizationId)
  const create = useCreateRollout(organizationId)
  const storageKey = `configuration-rollout-request:${organizationId}:${profileId}`
  const [unresolved, setUnresolved] = useState<RolloutSubmission | null>(() => savedRolloutRequest(storageKey, profileId))
  const [blocked, setBlocked] = useState(false)
  const submitting = useRef(false)
  const identity = useRef<{ key: string; request: RolloutSubmission } | null>(null)
  const preflightEpoch = useRef(0)
  const [approval, setApproval] = useState<{ key: string; data: RolloutPreflight; epoch: number } | null>(null)

  const chosen = selectedIds.flatMap(id => rows.find(row => row.id === id) ?? [])
  const nameOf = (assignmentId: string) => rows.find(row => row.id === assignmentId)?.resource.name ?? assignmentId
  const toPromote = chosen.filter(row => row.profileRevisionNumber !== revisionNumber && promotedVersions[row.id] === undefined)
  const versionOf = (row: ConfigurationAssignment) => promotedVersions[row.id] ?? row.version
  const request = executionOf(execution)
  const targets: RolloutTarget[] = chosen.map(row => ({ assignmentId: row.id, expectedVersion: versionOf(row),
    connectionId: sources[row.id] ?? '', execution: request }))
  const inputsKey = JSON.stringify([organizationId, profileId, revisionNumber, targets,
    chosen.map(row => [row.id, row.version, row.profileRevisionNumber, row.targetPath])])
  const currentInputs = useRef(inputsKey); currentInputs.current = inputsKey
  const currentScope = useRef(storageKey); currentScope.current = storageKey
  useEffect(() => { setApproval(null); identity.current = null; preflightEpoch.current += 1 }, [inputsKey])
  useEffect(() => {
    setUnresolved(savedRolloutRequest(storageKey, profileId)); setApproval(null); identity.current = null
  }, [storageKey, profileId])
  const compatible = toPromote.length === 0 || (promotionPreview.data?.compatible === true &&
    promotionPreview.data.revisionNumber === revisionNumber &&
    toPromote.every(row => promotionPreview.data!.items.some(item => item.assignmentId === row.id)))
  const approved = approval?.key === inputsKey && !preflight.isPending ? approval.data : null
  const preflightReady = Boolean(approved?.ready && chosen.length > 0 && approved.items.length === chosen.length &&
    targets.every(target => approved.items.some(item => item.assignmentId === target.assignmentId &&
      item.expectedVersion === target.expectedVersion && item.connectionId === target.connectionId && item.ready &&
      typeof item.desiredSha256 === 'string' && typeof item.connectionUpdatedAt === 'string' && item.remote !== null &&
      (!item.remote.exists || typeof item.remote.sha256 === 'string'))))
  const strategyValid = Number.isInteger(strategy.canaryCount) && Number.isInteger(strategy.batchSize) &&
    Number.isInteger(strategy.pauseSeconds) && strategy.canaryCount >= 0 && strategy.canaryCount <= Math.min(20, chosen.length) &&
    strategy.batchSize >= 1 && strategy.batchSize <= 20 && strategy.pauseSeconds >= 0 && strategy.pauseSeconds <= 3600
  const canStart = Boolean(approved && preflightReady && validExecution(request) && strategyValid &&
    toPromote.length === 0 && !create.isPending && !unresolved)
  const canCheckPreflight = !unresolved && !create.isPending && !preflight.isPending && validExecution(request) &&
    chosen.length > 0 && targets.every(target => target.connectionId !== '') && toPromote.length === 0
  const stepValid: Record<Step, boolean> = {
    1: chosen.length > 0 && targets.every(target => target.connectionId !== ''),
    2: compatible,
    3: toPromote.length === 0,
    4: preflightReady && validExecution(request),
    5: canStart,
  }

  const toggle = (id: string) => {
    setSelectedIds(old => old.includes(id) ? old.filter(item => item !== id) : [...old, id])
    promotionPreview.reset(); preflight.reset()
  }
  const checkCompatibility = () => promotionPreview.mutate({ revisionNumber,
    assignments: toPromote.map(row => ({ assignmentId: row.id, expectedVersion: row.version })) })
  const applyPromotion = () => promote.mutate({ revisionNumber,
    assignments: toPromote.map(row => ({ assignmentId: row.id, expectedVersion: row.version })) }, {
    onSuccess: result => setPromotedVersions(old => ({ ...old,
      ...Object.fromEntries(result.assignments.map(item => [item.assignmentId, item.version])) })),
  })
  const submitRequest = (submitted: RolloutSubmission) => {
    if (submitting.current || create.isPending) { setBlocked(true); return }
    const retained = savedRolloutRequest(storageKey, profileId)
    if (retained && retained.requestId !== submitted.requestId) { setUnresolved(retained); setBlocked(true); return }
    submitting.current = true; setBlocked(false); storeRolloutRequest(storageKey, submitted)
    create.mutate(submitted, {
      onSuccess: value => {
        storeRolloutRequest(storageKey, null)
        if (currentScope.current !== storageKey) return
        setUnresolved(null); identity.current = null; onStarted(value.rolloutId)
      },
      onError: error => {
        if (currentScope.current !== storageKey) return
        if (!(error instanceof ApiError) || error.status >= 500 || error.status === 408) setUnresolved(submitted)
        else { storeRolloutRequest(storageKey, null); setUnresolved(null); identity.current = null; setApproval(null) }
      },
      onSettled: () => { submitting.current = false },
    })
  }
  const start = () => {
    if (!approved || !canStart || submitting.current) { setBlocked(true); return }
    const key = JSON.stringify([inputsKey, approval?.epoch, approved.items, strategy])
    if (identity.current?.key !== key) identity.current = { key, request: {
      profileId, revisionNumber, requestId: createRequestId(), strategy,
      targets: targets.map(target => {
        const item = approved.items.find(value => value.assignmentId === target.assignmentId)!
        return { ...target, desiredSha256: item.desiredSha256!, connectionUpdatedAt: item.connectionUpdatedAt!,
          expectedRemoteSha256: item.remote?.sha256 ?? null, expectedRemoteMissing: !item.remote?.exists }
      }) } }
    submitRequest(identity.current.request)
  }
  const checkPreflight = () => {
    if (!canCheckPreflight || submitting.current) { setBlocked(true); return }
    const key = inputsKey; const epoch = ++preflightEpoch.current
    setApproval(null); identity.current = null; setBlocked(false)
    preflight.mutate({ profileId, revisionNumber, targets }, {
      onSuccess: data => {
        if (currentInputs.current === key && preflightEpoch.current === epoch) setApproval({ key, data, epoch })
      },
    })
  }
  const stepNames = [t.steps.revision, t.steps.compatibility, t.steps.promote, t.steps.preflight, t.steps.strategy]

  return <div className="rollout-wizard">
    {unresolved ? <InlineAlert tone="warning" title={i18n.t.common.unresolvedSubmission}
      action={<button type="button" className="secondary-button" disabled={create.isPending}
        onClick={() => submitRequest(unresolved)}>{i18n.t.common.recoverSubmission}</button>} /> : null}
    {blocked ? <InlineAlert tone="danger" title={i18n.t.common.operationBlocked} /> : null}
    {create.isError && step!==5 ? <InlineAlert tone="danger" title={describeError(create.error,i18n)} /> : null}
    <ol className="rollout-steps">{stepNames.map((name, index) =>
      <li key={name} aria-current={step === index + 1 ? 'step' : undefined}
        className={step === index + 1 ? 'rollout-step-current' : undefined}>{name}</li>)}</ol>
    <p className="cell-secondary">{t.stepOf(step, 5)}</p>

    {step === 1 ? <>
      <div className="configuration-field"><label htmlFor="rollout-revision">{t.targetRevision}</label>
        <select id="rollout-revision" value={revisionNumber}
          onChange={event => { setRevisionNumber(Number(event.target.value)); promotionPreview.reset(); preflight.reset() }}>
          {Array.from({ length: latestRevisionNumber }, (_, index) => latestRevisionNumber - index).map(revision =>
            <option key={revision} value={revision}>{i18n.t.configurations.version(revision)}</option>)}
        </select></div>
      <div className="table-scroll"><table className="data-grid">
        <caption className="visually-hidden">{t.chooseTargets}</caption>
        <thead><tr>
          <th scope="col"><input type="checkbox" aria-label={t.selectAll}
            checked={rows.length > 0 && chosen.length === rows.length}
            onChange={event => { setSelectedIds(event.target.checked ? rows.map(row => row.id) : []); promotionPreview.reset(); preflight.reset() }} /></th>
          <th scope="col">{i18n.t.assignments.columns.resource}</th>
          <th scope="col">{i18n.t.assignments.columns.environment}</th>
          <th scope="col">{i18n.t.assignments.columns.assigned}</th>
          <th scope="col">{t.connection}</th>
        </tr></thead>
        <tbody>{rows.map(row => <tr key={row.id}>
          <td><input type="checkbox" aria-label={`${t.select} ${row.resource.name}`} checked={selectedIds.includes(row.id)}
            onChange={() => toggle(row.id)} /></td>
          <td>{row.resource.name}</td>
          <td>{row.resource.environment.name}</td>
          <td>{t.onRevision(promotedVersions[row.id] !== undefined ? revisionNumber : row.profileRevisionNumber)}</td>
          <td>{selectedIds.includes(row.id) ? <SourcePicker organizationId={organizationId} assignment={row}
            value={sources[row.id] ?? ''} onChange={value => { setSources(old => ({ ...old, [row.id]: value })); preflight.reset() }} />
            : null}</td>
        </tr>)}</tbody>
      </table></div>
      {assignments.hasNextPage ? <button type="button" className="secondary-button" onClick={() => void assignments.fetchNextPage()}>
        {i18n.t.assignments.showMore}</button> : null}
      {chosen.length === 0 ? <p>{t.noTargets}</p> : null}
    </> : null}

    {step === 2 ? <>
      {toPromote.length === 0 ? <InlineAlert tone="success" title={t.nothingToPromote} /> : <>
        <button type="button" className="secondary-button" disabled={promotionPreview.isPending} onClick={checkCompatibility}>
          {promotionPreview.isPending ? t.checking : t.checkCompatibility}</button>
        {promotionPreview.data ? <ul className="rollout-matrix" aria-label={t.steps.compatibility}>
          {chosen.map(row => {
            const item = promotionPreview.data!.items.find(value => value.assignmentId === row.id)
            return <li key={row.id}><span className="rollout-node">{row.resource.name}</span>
              {!item ? <StatusIndicator label={t.alreadyThere} tone="neutral" />
                : item.compatible ? <StatusIndicator label={t.compatible} tone="success" />
                : <span className="rollout-issues">{item.issues.map(issue => <StatusIndicator key={`${issue.code}-${issue.variableName}`}
                  tone="danger" label={t.issue[issue.code] && issue.variableName ? t.issue[issue.code](issue.variableName)
                    : failureText(issue.code, i18n) ?? issue.code} />)}</span>}
            </li>
          })}</ul> : null}
      </>}
      {promotionPreview.isError ? <InlineAlert tone="danger" title={describeError(promotionPreview.error, i18n)} /> : null}
    </> : null}

    {step === 3 ? <>
      {toPromote.length === 0 ? <InlineAlert tone="success"
        title={Object.keys(promotedVersions).length > 0 ? t.promoted : t.nothingToPromote} /> : <>
        <p>{t.promoteSummary(toPromote.length, revisionNumber)}</p>
        <button type="button" className="primary-button" disabled={promote.isPending || !compatible} onClick={applyPromotion}>
          {promote.isPending ? t.promoting : t.promote}</button>
      </>}
      {promote.isError ? <InlineAlert tone="danger" title={describeError(promote.error, i18n)} /> : null}
    </> : null}

    {step === 4 ? <>
      <ExecutionFields idPrefix="rollout" value={execution} onChange={value => { setExecution(value); preflight.reset() }} />
      <button type="button" className="secondary-button" disabled={!canCheckPreflight}
        onClick={checkPreflight}>
        {preflight.isPending ? t.preflighting : t.preflight}</button>
      {preflight.data ? <div className="table-scroll"><table className="data-grid">
        <caption className="visually-hidden">{t.steps.preflight}</caption>
        <thead><tr><th scope="col">{i18n.t.assignments.columns.resource}</th><th scope="col">{t.connection}</th>
          <th scope="col">{i18n.t.assignments.columns.status}</th><th scope="col">{i18n.t.deployments.columns.detail}</th></tr></thead>
        <tbody>{preflight.data.items.map(item => <tr key={item.assignmentId}>
          <td>{nameOf(item.assignmentId)}</td>
          <td>{item.connectionName ?? '—'}</td>
          <td>{item.ready ? <StatusIndicator label={t.ready} tone="success" /> : <StatusIndicator label={t.blocked} tone="danger" />}</td>
          <td>{item.ready ? (item.changed ? t.changedLines(item.addedLines, item.removedLines) : t.unchanged)
            : failureText(item.errorCode, i18n)}</td>
        </tr>)}</tbody>
      </table></div> : null}
      {preflight.isError ? <InlineAlert tone="danger" title={describeError(preflight.error, i18n)} /> : null}
    </> : null}

    {step === 5 ? <>
      <div className="assignment-grid-fields rollout-strategy">
        <div className="configuration-field"><label htmlFor="rollout-canary">{t.canary}</label>
          <input id="rollout-canary" type="number" min={0} max={Math.min(20, chosen.length)} value={strategy.canaryCount}
            onChange={event => setStrategy({ ...strategy, canaryCount: Number(event.target.value) })} /></div>
        <div className="configuration-field"><label htmlFor="rollout-batch">{t.batch}</label>
          <input id="rollout-batch" type="number" min={1} max={20} value={strategy.batchSize}
            onChange={event => setStrategy({ ...strategy, batchSize: Number(event.target.value) })} /></div>
        <div className="configuration-field"><label htmlFor="rollout-pause">{t.pause}</label>
          <input id="rollout-pause" type="number" min={0} max={3600} value={strategy.pauseSeconds}
            onChange={event => setStrategy({ ...strategy, pauseSeconds: Number(event.target.value) })} /></div>
        <div className="configuration-field"><label htmlFor="rollout-mode">{t.rollbackMode}</label>
          <select id="rollout-mode" value={strategy.rollbackMode}
            onChange={event => setStrategy({ ...strategy, rollbackMode: event.target.value as RollbackMode })}>
            <option value="FAILED_TARGET_ONLY">{t.failedOnly}</option>
            <option value="ALL_APPLIED">{t.allApplied}</option>
          </select></div>
        <label className="checkbox-field"><input type="checkbox" checked={strategy.stopOnFailure}
          onChange={event => setStrategy({ ...strategy, stopOnFailure: event.target.checked })} />{t.stopOnFailure}</label>
      </div>
      {!strategyValid ? <InlineAlert tone="warning" title={t.strategyInvalid} /> : null}
      <button type="button" className="primary-button" disabled={!canStart}
        onClick={start}>{create.isPending ? t.starting : t.start}</button>
      {create.isError ? <InlineAlert tone="danger" title={describeError(create.error, i18n)} /> : null}
    </> : null}

    <div className="configuration-editor-actions">
      {step > 1 ? <button type="button" className="secondary-button" onClick={() => setStep((step - 1) as Step)}>{t.back}</button> : null}
      {step < 5 ? <button type="button" className="primary-button" disabled={!stepValid[step]}
        onClick={() => setStep((step + 1) as Step)}>{t.next}</button> : null}
    </div>
  </div>
}

/** Rolling out a profile version: a wizard, then the rollout's live status. */
export function ConfigurationRolloutPanel({ organizationId, profileId, latestRevisionNumber, initialRevisionNumber, preselectRuleId }: {
  organizationId: string; profileId: string; latestRevisionNumber: number
  initialRevisionNumber?: number; preselectRuleId?: string | null
}) {
  const t = useI18n().t.rollouts
  const [rolloutId, setRolloutId] = useState<string | null>(null)
  const [retry, setRetry] = useState<{ attempt: number; ids: string[] }>({ attempt: 0, ids: [] })
  return <WorkspaceSection title={t.title} description={t.description}>
    {rolloutId
      ? <RolloutStatusView organizationId={organizationId} rolloutId={rolloutId}
        onRetryFailed={ids => { setRetry(old => ({ attempt: old.attempt + 1, ids })); setRolloutId(null) }} />
      : <RolloutWizard key={retry.attempt} organizationId={organizationId} profileId={profileId}
        latestRevisionNumber={latestRevisionNumber} initialRevisionNumber={initialRevisionNumber}
        initialSelection={retry.ids} preselectRuleId={retry.attempt === 0 ? preselectRuleId : null} onStarted={setRolloutId} />}
  </WorkspaceSection>
}
