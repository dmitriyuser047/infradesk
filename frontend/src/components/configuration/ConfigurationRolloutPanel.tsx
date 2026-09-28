import { useEffect, useId, useState } from 'react'

import { useConfigurationAssignmentPages } from '../../api/configurationAssignments'
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

  const chosen = selectedIds.flatMap(id => rows.find(row => row.id === id) ?? [])
  const nameOf = (assignmentId: string) => rows.find(row => row.id === assignmentId)?.resource.name ?? assignmentId
  const toPromote = chosen.filter(row => row.profileRevisionNumber !== revisionNumber && promotedVersions[row.id] === undefined)
  const versionOf = (row: ConfigurationAssignment) => promotedVersions[row.id] ?? row.version
  const request = executionOf(execution)
  const targets: RolloutTarget[] = chosen.map(row => ({ assignmentId: row.id, expectedVersion: versionOf(row),
    connectionId: sources[row.id] ?? '', execution: request }))
  const compatible = toPromote.length === 0 || (promotionPreview.data?.compatible === true &&
    promotionPreview.data.revisionNumber === revisionNumber &&
    toPromote.every(row => promotionPreview.data!.items.some(item => item.assignmentId === row.id)))
  const preflightReady = preflight.data?.ready === true && preflight.data.items.length === chosen.length
  const strategyValid = strategy.canaryCount >= 0 && strategy.canaryCount <= Math.min(20, chosen.length) &&
    strategy.batchSize >= 1 && strategy.batchSize <= 20 && strategy.pauseSeconds >= 0 && strategy.pauseSeconds <= 3600
  const stepValid: Record<Step, boolean> = {
    1: chosen.length > 0 && targets.every(target => target.connectionId !== ''),
    2: compatible,
    3: toPromote.length === 0,
    4: preflightReady && validExecution(request),
    5: strategyValid,
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
  const start = () => {
    if (!preflightReady || !strategyValid) return
    create.mutate({ profileId, revisionNumber, requestId: crypto.randomUUID(), strategy,
      targets: targets.map(target => {
        const item = preflight.data!.items.find(value => value.assignmentId === target.assignmentId)!
        return { ...target, desiredSha256: item.desiredSha256!, connectionUpdatedAt: item.connectionUpdatedAt!,
          expectedRemoteSha256: item.remote?.sha256 ?? null, expectedRemoteMissing: !item.remote?.exists }
      }) }, { onSuccess: value => onStarted(value.rolloutId) })
  }
  const stepNames = [t.steps.revision, t.steps.compatibility, t.steps.promote, t.steps.preflight, t.steps.strategy]

  return <div className="rollout-wizard">
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
      <button type="button" className="secondary-button" disabled={preflight.isPending || !validExecution(request)}
        onClick={() => preflight.mutate({ profileId, revisionNumber, targets })}>
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
      <button type="button" className="primary-button" disabled={!preflightReady || !strategyValid || create.isPending}
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
