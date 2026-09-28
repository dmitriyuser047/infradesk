import { useEffect, useId, useState } from 'react'

import { useConfigurationAssignmentPages } from '../../api/configurationAssignments'
import { useCancelRollout, useCreateRollout, usePromoteAssignments, usePromotionPreview,
  useRollout, useRolloutPreflight } from '../../api/configurationRollouts'
import { useResourceContext } from '../../api/infrastructure'
import { InlineAlert, WorkspaceSection } from '../layout/WorkspacePrimitives'
import { useI18n } from '../../i18n'
import { describeError } from '../../i18n/errors'
import type { ConfigurationAssignment } from '../../types/configurationAssignment'
import type { DeploymentExecution } from '../../types/configurationDeployment'
import type { RolloutTarget } from '../../types/configurationRollout'

function SourcePicker({ organizationId, assignment, value, onChange }: {
  organizationId: string; assignment: ConfigurationAssignment; value: string; onChange: (value: string) => void
}) {
  const t = useI18n().t.rollouts
  const id = useId()
  const context = useResourceContext(organizationId, assignment.resource.id)
  const sources = (context.data?.sourceConnections ?? []).filter(source => source.active && source.connectorType === 'SSH')
  useEffect(() => { if (!value && sources.length === 1) onChange(sources[0].id) }, [value, sources.length, onChange])
  return <label htmlFor={id}>{t.connection}<select id={id} value={value} onChange={event => onChange(event.target.value)}>
    <option value="">{t.chooseConnection}</option>
    {sources.map(source => <option key={source.id} value={source.id}>{source.name}</option>)}
  </select></label>
}

/** Explicit target selection, compatibility check, desired-state promotion, remote preflight, then rollout. */
export function ConfigurationRolloutPanel({ organizationId, profileId, latestRevisionNumber }: {
  organizationId: string; profileId: string; latestRevisionNumber: number
}) {
  const i18n = useI18n()
  const t = i18n.t.rollouts
  const assignments = useConfigurationAssignmentPages(organizationId, { profileId }, true)
  const rows = assignments.data?.pages.flat() ?? []
  const [selectedIds, setSelectedIds] = useState<string[]>([])
  const [revisionNumber, setRevisionNumber] = useState(latestRevisionNumber)
  const [sources, setSources] = useState<Record<string, string>>({})
  const [versions, setVersions] = useState<Record<string, number>>({})
  const [activation, setActivation] = useState<DeploymentExecution['activation']>('NONE')
  const [unitName, setUnitName] = useState('')
  const [canaryCount, setCanaryCount] = useState(1)
  const [batchSize, setBatchSize] = useState(1)
  const [pauseSeconds, setPauseSeconds] = useState(0)
  const [stopOnFailure, setStopOnFailure] = useState(true)
  const [rollbackMode, setRollbackMode] = useState<'FAILED_TARGET_ONLY' | 'ALL_APPLIED'>('FAILED_TARGET_ONLY')
  const [rolloutId, setRolloutId] = useState<string | null>(null)
  const promotionPreview = usePromotionPreview(organizationId, profileId)
  const promote = usePromoteAssignments(organizationId, profileId)
  const preflight = useRolloutPreflight(organizationId)
  const create = useCreateRollout(organizationId)
  const rollout = useRollout(organizationId, rolloutId)
  const cancel = useCancelRollout(organizationId)
  const chosen = selectedIds.flatMap(id => rows.find(row => row.id === id) ?? [])
  const promotionSelections = chosen.filter(row => row.profileRevisionNumber !== revisionNumber && !versions[row.id])
    .map(row => ({ assignmentId: row.id, expectedVersion: row.version }))
  const execution: DeploymentExecution = { activation, unitName: activation === 'NONE' ? null : unitName,
    validator: null, newFileMode: 420 }
  const currentVersion = (row: ConfigurationAssignment) => versions[row.id] ?? row.version
  const remoteTargets: RolloutTarget[] = chosen.map(row => ({ assignmentId: row.id,
    expectedVersion: currentVersion(row), connectionId: sources[row.id] ?? '', execution }))
  const validExecution = activation === 'NONE' || /^[A-Za-z0-9_.@:-]+\.service$/.test(unitName)
  const allSources = remoteTargets.every(target => target.connectionId)
  const compatible = promotionSelections.length === 0 || (promotionPreview.data?.compatible &&
    promotionPreview.data.items.length === promotionSelections.length)
  const promoted = promotionSelections.length === 0
  const preflightReady = preflight.data && preflight.data.length === chosen.length && preflight.data.every(item => item.ready)

  const resetChecks = () => { promotionPreview.reset(); preflight.reset() }
  const toggle = (id: string) => { setSelectedIds(old => old.includes(id) ? old.filter(item => item !== id) : [...old, id]); resetChecks() }
  const checkCompatibility = () => promotionPreview.mutate({ revisionNumber, assignments: promotionSelections })
  const applyPromotion = () => promote.mutate({ revisionNumber, assignments: promotionSelections }, {
    onSuccess: result => { setVersions(old => ({ ...old,
      ...Object.fromEntries(result.assignments.map(item => [item.assignmentId, item.version])) })); preflight.reset() },
  })
  const checkRemote = () => preflight.mutate(remoteTargets)
  const start = () => {
    if (!preflightReady) return
    const targets = remoteTargets.map(target => {
      const item = preflight.data!.find(value => value.assignmentId === target.assignmentId)!
      return { ...target, desiredSha256: item.desiredSha256!, connectionUpdatedAt: item.connectionUpdatedAt!,
        expectedRemoteSha256: item.remote?.sha256 ?? null, expectedRemoteMissing: !item.remote?.exists }
    })
    create.mutate({ profileId, revisionNumber, requestId: crypto.randomUUID(), canaryCount, batchSize,
      pauseSeconds, stopOnFailure, rollbackMode, targets }, { onSuccess: value => setRolloutId(value.rolloutId) })
  }

  return <WorkspaceSection title={t.title}>
    <div className="configuration-field"><label htmlFor="rollout-revision">{t.targetRevision}</label>
      <input id="rollout-revision" type="number" min={1} max={latestRevisionNumber} value={revisionNumber}
        onChange={event => { setRevisionNumber(Number(event.target.value)); resetChecks() }} /></div>
    <h3>{t.chooseTargets}</h3>
    <div className="table-scroll"><table className="data-grid"><thead><tr><th scope="col">{t.select}</th>
      <th scope="col">{i18n.t.assignments.columns.resource}</th><th scope="col">{i18n.t.assignments.columns.assigned}</th>
      <th scope="col">{t.connection}</th></tr></thead><tbody>
      {rows.map(row => <tr key={row.id}><td><input type="checkbox" aria-label={`${t.select} ${row.resource.name}`}
        checked={selectedIds.includes(row.id)} onChange={() => toggle(row.id)} /></td>
        <td>{row.resource.name}</td><td>v{versions[row.id] ? revisionNumber : row.profileRevisionNumber}</td>
        <td>{selectedIds.includes(row.id) ? <SourcePicker organizationId={organizationId} assignment={row}
          value={sources[row.id] ?? ''} onChange={value => { setSources(old => ({ ...old, [row.id]: value })); preflight.reset() }} /> : null}</td>
      </tr>)}</tbody></table></div>
    {assignments.hasNextPage ? <button type="button" className="secondary-button" onClick={() => void assignments.fetchNextPage()}>
      {i18n.t.assignments.showMore}</button> : null}
    {chosen.length === 0 ? <p>{t.noTargets}</p> : null}
    {promotionSelections.length > 0 ? <><button type="button" className="secondary-button"
      disabled={promotionPreview.isPending} onClick={checkCompatibility}>{t.checkCompatibility}</button>
      {promotionPreview.data ? <div role="status">{promotionPreview.data.compatible ? t.promotionReady : t.promotionBlocked}
        <ul>{promotionPreview.data.items.filter(item => !item.compatible).map(item => <li key={item.assignmentId}>
          {rows.find(row => row.id === item.assignmentId)?.resource.name}: {item.errorCode} {item.variableName}</li>)}</ul></div> : null}
      <button type="button" className="primary-button" disabled={!compatible || promote.isPending} onClick={applyPromotion}>
        {t.promote}</button></> : null}
    {promotionPreview.isError ? <InlineAlert tone="danger" title={describeError(promotionPreview.error, i18n)} /> : null}
    {promote.isError ? <InlineAlert tone="danger" title={describeError(promote.error, i18n)} /> : null}
    <div className="configuration-field"><label htmlFor="rollout-activation">{i18n.t.deployments.activation}</label>
      <select id="rollout-activation" value={activation} onChange={event => { setActivation(event.target.value as DeploymentExecution['activation']); preflight.reset() }}>
        <option value="NONE">{i18n.t.deployments.none}</option><option value="SYSTEMD_RELOAD">{i18n.t.deployments.reload}</option>
        <option value="SYSTEMD_RESTART">{i18n.t.deployments.restart}</option></select></div>
    {activation !== 'NONE' ? <div className="configuration-field"><label htmlFor="rollout-unit">{i18n.t.deployments.unit}</label>
      <input id="rollout-unit" value={unitName} onChange={event => { setUnitName(event.target.value); preflight.reset() }} /></div> : null}
    <button type="button" className="secondary-button" disabled={!chosen.length || !promoted || !allSources || !validExecution || preflight.isPending}
      onClick={checkRemote}>{t.preflight}</button>
    {preflight.data ? <ul role="status">{preflight.data.map(item => <li key={item.assignmentId}>
      {rows.find(row => row.id === item.assignmentId)?.resource.name}: {item.ready ? t.ready : `${t.notReady} — ${item.errorCode}`}
      {item.ready ? ` (+${item.addedLines}/-${item.removedLines})` : null}</li>)}</ul> : null}
    {preflight.isError ? <InlineAlert tone="danger" title={describeError(preflight.error, i18n)} /> : null}
    <div className="assignment-grid-fields">
      <label>{t.canary}<input type="number" min={0} max={Math.min(20, chosen.length)} value={canaryCount}
        onChange={event => setCanaryCount(Number(event.target.value))} /></label>
      <label>{t.batch}<input type="number" min={1} max={20} value={batchSize}
        onChange={event => setBatchSize(Number(event.target.value))} /></label>
      <label>{t.pause}<input type="number" min={0} max={3600} value={pauseSeconds}
        onChange={event => setPauseSeconds(Number(event.target.value))} /></label>
      <label>{t.stopOnFailure}<input type="checkbox" checked={stopOnFailure}
        onChange={event => setStopOnFailure(event.target.checked)} /></label>
      <label>{t.rollbackMode}<select value={rollbackMode} onChange={event => setRollbackMode(event.target.value as typeof rollbackMode)}>
        <option value="FAILED_TARGET_ONLY">{t.failedOnly}</option><option value="ALL_APPLIED">{t.allApplied}</option></select></label>
    </div>
    <button type="button" className="primary-button" disabled={!preflightReady || create.isPending ||
      canaryCount < 0 || canaryCount > chosen.length || batchSize < 1 || batchSize > 20 || pauseSeconds < 0 || pauseSeconds > 3600}
      onClick={start}>{create.isPending ? t.starting : t.start}</button>
    {create.isError ? <InlineAlert tone="danger" title={describeError(create.error, i18n)} /> : null}
    {rollout.data ? <div role="status" aria-live="polite"><h3>{t.status}</h3>
      <p>{t.state[rollout.data.rollout.state]}</p>
      <ol>{rollout.data.items.map(item => <li key={item.id}>
        {rows.find(row => row.id === item.assignmentId)?.resource.name ?? item.resourceId}: {t.itemState[item.state]}
      </li>)}</ol>
      {['QUEUED', 'RUNNING', 'PAUSED', 'ROLLING_BACK'].includes(rollout.data.rollout.state)
        ? <button type="button" className="secondary-button" disabled={cancel.isPending}
          onClick={() => rolloutId && cancel.mutate(rolloutId)}>{t.cancel}</button> : null}
    </div> : null}
  </WorkspaceSection>
}
