import { useEffect, useRef, useState } from 'react'
import { createRequestId } from '../../app/requestId'
import { readPendingSubmission, storePendingSubmission, type PendingSubmission } from '../../app/pendingSubmission'
import { ApiError } from '../../api/httpClient'
import { activeNodeUpgrade, useNodeImageStatus, useNodeUpgrade, useNodeUpgradeActions, useNodeUpgrades } from '../../api/remnawaveNodeUpgrades'
import { useRefreshFleet } from '../../api/remnawaveFleets'
import { useI18n } from '../../i18n'
import { InlineAlert, WorkspaceSection } from '../layout/WorkspacePrimitives'

const texts = {
  en: { title: 'Node versions', detail: 'Choose a reviewed release, then upgrade the canary and remaining nodes in waves.',
    target: 'Desired Node release', select: 'Set desired release', selection: 'Selecting a release changes metadata only.',
    version: 'Version', status: 'Availability', architecture: 'Architecture', technical: 'Image details', current: 'Installed',
    compatibility: 'Panel compatibility', released: 'Published', preview: 'Preview upgrade', canary: 'Canary nodes', wave: 'Wave size',
    auto: 'Roll back the current wave automatically after a known failure', pauseCanary: 'Pause after canary', start: 'Start upgrade',
    refresh: 'Refresh fleet', pause: 'Pause', resume: 'Resume', rollback: 'Roll back', scope: 'Rollback scope', currentWave: 'Current wave',
    all: 'All completed nodes', history: 'Upgrade history', details: 'Details', node: 'Node', phase: 'Phase', actions: 'Actions',
    progress: 'Wave', error: 'The request could not be completed.', loading: 'Loading versions…',
    unknown: 'Outcome unknown. Inspect the node and Panel manually. Automatic retry, rollback and wave advancement have stopped.',
    stale: 'Fresh evidence is required. Refresh the fleet, wait for observations, then resume.',
    health: 'A health precondition failed. Inspect the nodes before resuming.',
    canaryPaused: 'Paused after canary verification.', operatorPaused: 'Paused by an operator.',
    pauseRequested: 'Pause requested. The current atomic step will finish before the next node.',
    rollbackIncomplete: 'Rollback did not complete on every node.', noPrevious: 'No reviewed previous digest; automatic rollback is unavailable.',
    states: { CURRENT: 'Current', UPDATE_AVAILABLE: 'Update available', AHEAD_OF_TARGET: 'Ahead of target', AHEAD_OF_CATALOG: 'Ahead of catalog', UNSUPPORTED: 'Unsupported',
      UNKNOWN: 'Unknown', READY: 'Ready', BLOCKED: 'Blocked', REFRESH_REQUIRED: 'Refresh required', PLANNED: 'Planned', QUEUED: 'Queued',
      RUNNING: 'Running', PAUSED: 'Paused', SUCCEEDED: 'Succeeded', FAILED: 'Failed', ROLLING_BACK: 'Rolling back', ROLLED_BACK: 'Rolled back',
      PENDING: 'Pending', SKIPPED: 'Skipped', COMPATIBLE: 'Compatible', INCOMPATIBLE: 'Incompatible', UNCONFIRMED: 'Unconfirmed' } as Record<string, string>,
    phases: { VALIDATE: 'Validate', PREPARE_CANARY: 'Prepare canary', PREFETCH_CANARY: 'Pull canary images', UPGRADE_CANARY: 'Upgrade canary',
      VERIFY_CANARY: 'Verify canary', PREFETCH_WAVE: 'Pull wave images', UPGRADE_WAVE: 'Upgrade wave', VERIFY_WAVE: 'Verify wave',
      FINAL_VERIFY: 'Final verification', ROLLBACK: 'Rollback', COMPLETE: 'Complete', PREFETCH: 'Pull image', SWITCH: 'Switch image',
      LOCAL_VERIFY: 'Verify local runtime', PANEL_VERIFY: 'Verify Panel evidence' } as Record<string, string> },
  ru: { title: 'Версии Node', detail: 'Выберите проверенный релиз, затем обновите canary и остальные узлы волнами.',
    target: 'Desired релиз Node', select: 'Выбрать desired релиз', selection: 'Выбор релиза меняет только метаданные.',
    version: 'Версия', status: 'Доступность', architecture: 'Архитектура', technical: 'Данные образа', current: 'Установлено',
    compatibility: 'Совместимость с Panel', released: 'Опубликован', preview: 'Preview обновления', canary: 'Canary узлы', wave: 'Размер волны',
    auto: 'Автоматически откатить текущую волну при доказанной ошибке', pauseCanary: 'Пауза после canary', start: 'Начать обновление',
    refresh: 'Обновить evidence Fleet', pause: 'Пауза', resume: 'Продолжить', rollback: 'Откатить', scope: 'Область отката', currentWave: 'Текущая волна',
    all: 'Все завершённые узлы', history: 'История обновлений', details: 'Подробности', node: 'Узел', phase: 'Этап', actions: 'Действия',
    progress: 'Волна', error: 'Не удалось выполнить запрос.', loading: 'Загрузка версий…',
    unknown: 'Результат неизвестен. Проверьте узел и Panel вручную. Автоматические retry, rollback и переход к следующей волне остановлены.',
    stale: 'Нужны свежие evidence. Обновите Fleet, дождитесь observations, затем продолжите.',
    health: 'Узел не прошёл health precondition. Проверьте узлы перед продолжением.',
    canaryPaused: 'Пауза после проверки canary.', operatorPaused: 'Пауза по запросу оператора.',
    pauseRequested: 'Запрошена пауза. Текущее атомарное действие завершится до следующего узла.',
    rollbackIncomplete: 'Откат завершён не на всех узлах.', noPrevious: 'Нет проверенного предыдущего digest; автоматический откат недоступен.',
    states: { CURRENT: 'Актуально', UPDATE_AVAILABLE: 'Доступно обновление', AHEAD_OF_TARGET: 'Новее desired', AHEAD_OF_CATALOG: 'Новее каталога', UNSUPPORTED: 'Не поддерживается',
      UNKNOWN: 'Неизвестно', READY: 'Готово', BLOCKED: 'Заблокировано', REFRESH_REQUIRED: 'Нужно обновить evidence', PLANNED: 'Запланировано', QUEUED: 'В очереди',
      RUNNING: 'Выполняется', PAUSED: 'Приостановлено', SUCCEEDED: 'Успешно', FAILED: 'Ошибка', ROLLING_BACK: 'Откат', ROLLED_BACK: 'Откачено',
      PENDING: 'Ожидает', SKIPPED: 'Пропущено', COMPATIBLE: 'Совместимо', INCOMPATIBLE: 'Несовместимо', UNCONFIRMED: 'Не подтверждено' } as Record<string, string>,
    phases: { VALIDATE: 'Валидация', PREPARE_CANARY: 'Подготовка canary', PREFETCH_CANARY: 'Загрузка образов canary', UPGRADE_CANARY: 'Обновление canary',
      VERIFY_CANARY: 'Проверка canary', PREFETCH_WAVE: 'Загрузка образов волны', UPGRADE_WAVE: 'Обновление волны', VERIFY_WAVE: 'Проверка волны',
      FINAL_VERIFY: 'Итоговая проверка', ROLLBACK: 'Откат', COMPLETE: 'Завершено', PREFETCH: 'Загрузка образа', SWITCH: 'Смена образа',
      LOCAL_VERIFY: 'Локальная проверка', PANEL_VERIFY: 'Проверка evidence Panel' } as Record<string, string> },
}

export function NodeVersionsPanel({ organizationId, integrationId, fleetId, canManage, canControl }: {
  organizationId: string; integrationId: string; fleetId: string; canManage: boolean; canControl: boolean
}) {
  const { locale, t } = useI18n(); const copy = texts[locale]
  const status = useNodeImageStatus(organizationId, integrationId, fleetId)
  const history = useNodeUpgrades(organizationId, integrationId, fleetId)
  const actions = useNodeUpgradeActions(organizationId, integrationId, fleetId)
  const refresh = useRefreshFleet(organizationId, integrationId, fleetId)
  const [releaseId, setReleaseId] = useState('')
  const [canary, setCanary] = useState<string[]>([])
  const [waveSize, setWaveSize] = useState(2)
  const [automaticRollback, setAutomaticRollback] = useState(false)
  const [pauseAfterCanary, setPauseAfterCanary] = useState(true)
  const [open, setOpen] = useState<string | null>(null)
  const [scope, setScope] = useState('CURRENT_WAVE')
  const startRequests = useRef<Record<string, string>>({})
  const submissionKey = `node-upgrade:${organizationId}:${integrationId}:${fleetId}`
  const [unresolved,setUnresolved] = useState<PendingSubmission|null>(()=>readPendingSubmission(submissionKey))
  const submit = (identity:PendingSubmission) => {
    storePendingSubmission(submissionKey,identity)
    actions.start.mutate(identity,{onSuccess:r=>{storePendingSubmission(submissionKey,null);setUnresolved(null);setOpen(r.id);actions.preview.reset()},
      onError:error=>{if (!(error instanceof ApiError) || error.status>=500 || error.status===408) setUnresolved(identity)
        else {storePendingSubmission(submissionKey,null);setUnresolved(null)}}})
  }
  useEffect(()=>{
    const found = unresolved && history.data?.items.find(item=>item.id===unresolved.planId && item.state!=='PLANNED')
    if (!found) return
    storePendingSubmission(submissionKey,null);setUnresolved(null);setOpen(found.id);actions.preview.reset()
  },[unresolved,history.data,submissionKey])
  const active = status.data?.active ?? history.data?.items.find(r => activeNodeUpgrade(r.state)) ?? null
  const detail = useNodeUpgrade(organizationId, integrationId, fleetId, open ?? active?.id ?? null)
  const preview = actions.preview.data
  const target = status.data?.target
  const selected = status.data?.releases.find(r => r.releaseId === (releaseId || target?.release.releaseId))
  const error = [actions.select.error, actions.preview.error, actions.start.error, actions.control.error, refresh.error, status.error, history.error, detail.error].find(Boolean)
  const issue = (code: string) => code.endsWith('REFRESH_REQUIRED') ? copy.stale : code.endsWith('NO_PREVIOUS_IMAGE') ? copy.noPrevious
    : code.endsWith('HEALTH_GATE') ? copy.health : code
  const label = (state: string) => copy.states[state] ?? state
  const canRollback = detail.data && detail.data.snapshot.members.filter(m => !m.skipped).every(m => m.previousImageReference !== null)
  const rollbackSupported = Boolean(status.data?.members.length && status.data.members.every(m =>
    m.observation?.managedFiles && m.releaseId && status.data.releases.some(r => r.releaseId === m.releaseId &&
      r.status !== 'BLOCKED' && r.compatibility.state === 'COMPATIBLE')))
  return <WorkspaceSection title={copy.title} description={copy.detail}>
    {unresolved && !preview ? <InlineAlert tone="warning" title={t.common.unresolvedSubmission} action={<button type="button" className="secondary-button" disabled={!canControl || !history.isSuccess || actions.start.isPending} onClick={()=>submit(unresolved)}>{t.common.recoverSubmission}</button>} /> : null}
    {status.isPending ? <p className="muted">{copy.loading}</p> : null}
    {error ? <InlineAlert tone="danger" title={copy.error}>{error instanceof ApiError ? issue(error.code) : copy.error}</InlineAlert> : null}
    <p>{copy.compatibility}: {status.data?.panel.serverVersion ?? label('UNKNOWN')}</p>
    <p>{copy.target}: {target?.release.nodeVersion ?? '—'}</p>
    {canManage ? <div className="form-grid">
      <label>{copy.target}<select value={releaseId || target?.release.releaseId || ''} disabled={Boolean(active)} onChange={e => {
        setReleaseId(e.target.value); actions.preview.reset()
      }}><option value="">—</option>{status.data?.releases.map(r => <option key={r.releaseId} value={r.releaseId}
        disabled={r.status !== 'AVAILABLE' || r.compatibility.state !== 'COMPATIBLE'}>{r.nodeVersion} · {label(r.compatibility.state)}</option>)}</select></label>
      <button type="button" className="secondary-button" disabled={!selected || Boolean(active) || actions.select.isPending}
        onClick={() => { actions.preview.reset(); actions.select.mutate(selected!.releaseId) }}>{copy.select}</button>
      <p className="muted">{copy.selection}</p>
    </div> : null}
    {selected ? <details><summary>{copy.technical}</summary>
      <p>{copy.compatibility}: {label(selected.compatibility.state)} · Panel {selected.minimumPanelVersion} — &lt;{selected.maximumPanelVersionExclusive}</p>
      <p>{copy.released}: {new Date(selected.releasedAt).toLocaleString(locale)}</p>
      <code>{selected.imageRepository}@{selected.manifestDigest}</code>
      <ul>{selected.platforms.map(p => <li key={p.platform}>{p.platform} · <code>{p.manifestDigest}</code><br />Image ID: <code>{p.configDigest}</code></li>)}</ul>
    </details> : null}
    <table className="data-table"><thead><tr><th>{copy.node}</th><th>{copy.current}</th><th>{copy.status}</th><th>{copy.architecture}</th></tr></thead>
      <tbody>{status.data?.members.map(m => <tr key={m.membershipId}><td>{m.nodeName}</td><td>{m.reportedVersion ?? m.releaseId ?? label('UNKNOWN')}</td>
        <td>{label(m.status)}</td><td>{m.observation?.platform ?? label('UNKNOWN')}<details><summary>{copy.technical}</summary>
          <code>{m.observation?.actualImageId ?? label('UNKNOWN')}</code></details></td></tr>)}</tbody></table>
    {canControl && target && !active ? <div className="form-grid">
      <fieldset><legend>{copy.canary}</legend>{status.data?.members.filter(m => m.status !== 'CURRENT').map(m => <label key={m.membershipId}>
        <input type="checkbox" checked={canary.includes(m.membershipId)} onChange={e => { actions.preview.reset()
          setCanary(previous => e.target.checked ? [...previous, m.membershipId] : previous.filter(id => id !== m.membershipId)) }} />{m.nodeName}</label>)}</fieldset>
      <label>{copy.wave}<input type="number" min={1} max={25} value={waveSize} onChange={e => { actions.preview.reset(); setWaveSize(Number(e.target.value)) }} /></label>
      <label><input type="checkbox" checked={automaticRollback && rollbackSupported} disabled={!rollbackSupported}
        onChange={e => { actions.preview.reset(); setAutomaticRollback(e.target.checked) }} />{copy.auto}</label>
      {!rollbackSupported ? <p className="muted">{copy.noPrevious}</p> : null}
      <label><input type="checkbox" checked={pauseAfterCanary} onChange={e => { actions.preview.reset(); setPauseAfterCanary(e.target.checked) }} />{copy.pauseCanary}</label>
      <button type="button" className="secondary-button" disabled={!!unresolved || actions.preview.isPending || waveSize < 1 || waveSize > 25} onClick={() =>
        actions.preview.mutate({ releaseRevisionId: target.id, canaryMemberIds: canary, waveSize, automaticRollback: automaticRollback && rollbackSupported, pauseAfterCanary })}>{copy.preview}</button>
    </div> : null}
    {preview ? <div>
      <InlineAlert tone={preview.status === 'READY' ? 'info' : 'warning'} title={label(preview.status)} />
      {preview.issues.map(code => <p key={code}>{issue(code)}</p>)}
      {preview.plan ? <><p>{copy.target}: {preview.plan.target.nodeVersion} · {copy.progress}: {preview.plan.waveCount}</p>
        <ul>{preview.plan.members.map(m => <li key={m.membershipId}>{m.nodeName} · {m.skipped ? label('SKIPPED') : `${copy.progress} ${m.wave + 1}`}
          {m.previousImageReference === null ? ` · ${copy.noPrevious}` : ''}</li>)}</ul>
        <p>{new Date(preview.expiresAt!).toLocaleString(locale)}</p></> : null}
      {canControl && !active && preview.status === 'READY' && preview.planId ? <button type="button" className="primary-button"
        disabled={actions.start.isPending || Date.parse(preview.expiresAt!) <= Date.now()} onClick={() => {
          const planId = preview.planId!
          const requestId = startRequests.current[planId] ?? (startRequests.current[planId] = createRequestId())
          submit({planId,requestId})
        }}>{copy.start}</button> : null}
    </div> : null}
    {canControl ? <button type="button" className="secondary-button" disabled={refresh.isPending} onClick={() => refresh.mutate()}>{copy.refresh}</button> : null}
    {detail.data ? <WorkspaceSection title={`${copy.details}: ${detail.data.targetVersion}`}>
      <p>{label(detail.data.state)} · {copy.phase}: {copy.phases[detail.data.phase] ?? detail.data.phase} · {copy.progress} {detail.data.currentWave + 1}/{detail.data.waveCount}</p>
      {detail.data.state === 'UNKNOWN' ? <InlineAlert tone="warning" title={label('UNKNOWN')}>{copy.unknown}</InlineAlert> : null}
      {detail.data.pauseRequested ? <p>{copy.pauseRequested}</p> : null}
      {detail.data.state === 'PAUSED' ? <p>{detail.data.pauseReason === 'PAUSED_REFRESH_REQUIRED' ? copy.stale
        : detail.data.pauseReason === 'CANARY' ? copy.canaryPaused : detail.data.pauseReason === 'OPERATOR' ? copy.operatorPaused : copy.health}</p> : null}
      {detail.data.failureCode ? <p>{issue(detail.data.failureCode)}</p> : null}
      {detail.data.rollbackIncomplete ? <InlineAlert tone="danger" title={copy.rollbackIncomplete} /> : null}
      {canControl && activeNodeUpgrade(detail.data.state) && detail.data.state !== 'ROLLING_BACK' ? <div className="action-row">
        <button type="button" disabled={actions.control.isPending || detail.data.pauseRequested} onClick={() => actions.control.mutate({ id: detail.data!.id,
          command: detail.data!.state === 'PAUSED' ? 'resume' : 'pause' })}>{detail.data.state === 'PAUSED' ? copy.resume : copy.pause}</button>
        <label>{copy.scope}<select value={scope} onChange={e => setScope(e.target.value)}><option value="CURRENT_WAVE">{copy.currentWave}</option><option value="ALL_COMPLETED">{copy.all}</option></select></label>
        <button type="button" disabled={!canRollback || actions.control.isPending || detail.data.rollbackRequested} onClick={() =>
          actions.control.mutate({ id: detail.data!.id, command: 'rollback', scope })}>{copy.rollback}</button>
      </div> : null}
      <table className="data-table"><thead><tr><th>{copy.node}</th><th>{copy.progress}</th><th>{copy.status}</th></tr></thead><tbody>{detail.data.members.map(m =>
        <tr key={m.id}><td>{detail.data!.snapshot.members.find(p => p.membershipId === m.membershipId)?.nodeName}</td><td>{m.wave + 1}</td><td>{label(m.state)}{m.failureCode ? ` · ${issue(m.failureCode)}` : ''}</td></tr>)}</tbody></table>
      <details><summary>{copy.actions}</summary><ul>{detail.data.actions.map(a => <li key={a.id}>{copy.phases[a.kind] ?? a.kind} · {label(a.state)}
        {a.rollback ? ` · ${copy.rollback}` : ''} · <code>{a.targetReference}</code></li>)}</ul></details>
    </WorkspaceSection> : null}
    <details><summary>{copy.history}</summary><ul>{history.data?.items.map(r => <li key={r.id}>{new Date(r.createdAt).toLocaleString(locale)} · {r.targetVersion} · {label(r.state)}
      <button type="button" className="link-button" onClick={() => setOpen(r.id)}>{copy.details}</button></li>)}</ul></details>
  </WorkspaceSection>
}
