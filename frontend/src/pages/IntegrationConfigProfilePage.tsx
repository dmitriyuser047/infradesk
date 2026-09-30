import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { createRequestId } from '../app/requestId'
import { getConfigRevision, previewConfigRevision, useConfigDeployments, useConfigRevisions,
  previewConfigRollout, useConfigRollouts, useCreateConfigRevision, useDeployConfigRevision,
  useManagedConfigProfile, useStartConfigRollout, type ConfigPreview, type ConfigRevisionContent,
  type ConfigRolloutPreview, type ConfigStatus } from '../api/integrationConfigProfiles'
import { useIntegrationInventory } from '../api/integrationInventory'
import { useOrganizationPermissions } from '../components/auth/authorization'
import { AppShell } from '../components/layout/AppShell'
import { InlineAlert, PageLoading, PropertyGrid, StatusIndicator, WorkspaceHeader,
  WorkspaceSection, WorkspaceTabs } from '../components/layout/WorkspacePrimitives'
import { useI18n } from '../i18n'
import { describeError } from '../i18n/errors'

export const statusText: Record<ConfigStatus, [string, string]> = {
  UNAVAILABLE: ['Профиль недоступен', 'Profile unavailable'],
  DEPLOYING: ['Развёртывание', 'Deploying'],
  WAITING_REFRESH: ['Ожидание синхронизации', 'Waiting for synchronization'],
  DEPLOYMENT_FAILED: ['Ошибка развёртывания', 'Deployment failed'],
  IN_SYNC: ['Синхронизировано', 'In sync'],
  LOCAL_CHANGES: ['Локальные изменения', 'Local changes'],
  REMOTE_DRIFT: ['Изменено в Remnawave', 'Remote drift'],
}

function savedDeployRequest(key: string): string | null {
  try {
    const value = window.sessionStorage.getItem(key)
    return value && /^[0-9a-f-]{36}$/i.test(value) ? value : null
  } catch { return null }
}

function storeDeployRequest(key: string, id: string | null): void {
  try {
    if (id) window.sessionStorage.setItem(key, id)
    else window.sessionStorage.removeItem(key)
  } catch { /* Retain the current component's request ID. */ }
}

export function IntegrationConfigProfilePage() {
  const { organizationId, integrationId, objectId } = useParams()
  if (!organizationId || !integrationId || !objectId) return null
  return <ProfilePage organizationId={organizationId} integrationId={integrationId} objectId={objectId} />
}

function ProfilePage({ organizationId: org, integrationId: integration, objectId: object }: {
  organizationId: string; integrationId: string; objectId: string
}) {
  const i18n = useI18n(); const ru = i18n.locale === 'ru'
  const permissions = useOrganizationPermissions(org)
  const canRead = permissions.can('manageIntegrations') && permissions.can('manageConfigurations')
  const canDeploy = canRead && permissions.can('executeOperations')
  const managed = useManagedConfigProfile(org, integration, object, canRead)
  const inventory = useIntegrationInventory(org, integration, 'config-profiles',
    { search: '', active: '', state: '', offset: 0, limit: 100 }, canRead)
  const remote = inventory.data?.items.find(item => item.id === object)
  const revisions = useConfigRevisions(org, integration, object, Boolean(managed.data))
  const deployments = useConfigDeployments(org, integration, object, Boolean(managed.data))
  const rollouts = useConfigRollouts(org, integration, object, Boolean(managed.data))
  const create = useCreateConfigRevision(org, integration, object)
  const deploy = useDeployConfigRevision(org, integration, object)
  const rollout = useStartConfigRollout(org, integration, object)
  const [tab, setTab] = useState<'configuration' | 'revisions' | 'deployments' | 'rollouts'>('configuration')
  const [selected, setSelected] = useState<number | null>(null)
  const [content, setContent] = useState<ConfigRevisionContent | null>(null)
  const [contentError, setContentError] = useState<unknown>(null)
  const [editing, setEditing] = useState(false)
  const [editor, setEditor] = useState('')
  const [parseError, setParseError] = useState('')
  const [preview, setPreview] = useState<ConfigPreview | null>(null)
  const [previewError, setPreviewError] = useState<unknown>(null)
  const [previewing, setPreviewing] = useState(false)
  const [confirm, setConfirm] = useState(false)
  const [rolloutConfirm, setRolloutConfirm] = useState(false)
  const [rolloutPreview, setRolloutPreview] = useState<ConfigRolloutPreview | null>(null)
  const [rolloutPreviewError, setRolloutPreviewError] = useState<unknown>(null)
  const [rolloutPreviewing, setRolloutPreviewing] = useState(false)
  const [automaticRollback, setAutomaticRollback] = useState(true)
  const [pendingRequest, setPendingRequest] = useState<{ revision: number; id: string } | null>(null)
  const [pendingRolloutRequest, setPendingRolloutRequest] = useState<{ revision: number; id: string } | null>(null)
  const back = { label: ru ? 'Интеграция' : 'Integration',
    to: `/organizations/${org}/integrations/${integration}?tab=profiles` }
  const revision = selected ?? managed.data?.profile.latestRevisionNumber ?? null
  const chosen = revision
  const requestKey = chosen === null ? null :
    `integration-config-deploy-request:${org}:${integration}:${object}:${chosen}`
  const rolloutRequestKey = chosen === null ? null :
    `integration-config-rollout-request:${org}:${integration}:${object}:${chosen}`

  useEffect(() => {
    if (chosen === null || requestKey === null) return
    const id = savedDeployRequest(requestKey)
    setPendingRequest(id ? { revision: chosen, id } : null)
  }, [chosen, requestKey])

  useEffect(() => {
    if (!pendingRequest || !deployments.data) return
    const found = deployments.data.find(item => item.requestId === pendingRequest.id)
    if (!found || found.status === 'QUEUED' || found.status === 'RUNNING') return
    storeDeployRequest(`integration-config-deploy-request:${org}:${integration}:${object}:${pendingRequest.revision}`, null)
    setPendingRequest(null)
  }, [deployments.data, pendingRequest, org, integration, object])

  useEffect(() => {
    if (!rolloutRequestKey || !rollouts.data) return
    const requestId = savedDeployRequest(rolloutRequestKey)
    if (!requestId) { setPendingRolloutRequest(null); return }
    const found = rollouts.data.find(item => item.requestId === requestId)
    if (found && ['SUCCEEDED', 'ROLLED_BACK', 'FAILED', 'UNKNOWN', 'CANCELLED'].includes(found.status)) {
      storeDeployRequest(rolloutRequestKey, null)
      setPendingRolloutRequest(null)
    } else if (found) setPendingRolloutRequest(null)
    else if (chosen !== null) setPendingRolloutRequest({ revision: chosen, id: requestId })
  }, [rollouts.data, rolloutRequestKey, chosen])

  useEffect(() => {
    if (!canRead || revision === null) return
    let active = true
    setContent(null); setContentError(null); setPreview(null); setEditing(false); setEditor('')
    void getConfigRevision(org, integration, object, revision).then(value => {
      if (active) setContent(value)
    }, error => { if (active) setContentError(error) })
    return () => { active = false }
  }, [canRead, org, integration, object, revision])

  if (permissions.isPending) return <AppShell><PageLoading title="Config Profile" back={back}
    label={i18n.t.common.loading} /></AppShell>
  if (!canRead) return <AppShell><div className="workspace-page"><WorkspaceHeader title="Config Profile" back={back} />
    <InlineAlert tone="danger" title={ru ? 'Нет доступа' : 'Access denied'} /></div></AppShell>
  if (managed.isPending) return <AppShell><PageLoading title="Config Profile" back={back}
    label={i18n.t.common.loading} /></AppShell>
  if (managed.isError || !managed.data) return <AppShell><div className="workspace-page">
    <WorkspaceHeader title="Config Profile" back={back} />
    <InlineAlert tone="danger" title={ru ? 'Профиль недоступен' : 'Profile unavailable'}>
      {managed.isError ? describeError(managed.error, i18n) : null}</InlineAlert></div></AppShell>

  const value = managed.data
  const busy = value.latestDeployment?.status === 'QUEUED' || value.latestDeployment?.status === 'RUNNING'
  const label = statusText[value.status][ru ? 0 : 1]
  const chosenRevision = revision ?? value.profile.latestRevisionNumber
  const startEdit = () => {
    if (!content) return
    setEditor(JSON.stringify(content.config, null, 2)); setParseError(''); setEditing(true); setPreview(null)
  }
  const save = () => {
    let json: unknown
    try { json = JSON.parse(editor) } catch { setParseError(ru ? 'Некорректный JSON' : 'Invalid JSON'); return }
    if (typeof json !== 'object' || json === null || Array.isArray(json)) {
      setParseError(ru ? 'Корень должен быть объектом JSON' : 'Root must be a JSON object'); return
    }
    if (new TextEncoder().encode(editor).length > 256 * 1024) {
      setParseError(ru ? 'Превышен лимит 256 KiB' : 'Exceeds the 256 KiB limit'); return
    }
    create.mutate(json as Record<string, unknown>, { onSuccess: result => {
      setSelected(result.revisionNumber); setEditing(false); setEditor('')
    } })
  }
  const previewSelected = () => {
    setPreviewing(true); setPreviewError(null); setPreview(null)
    void previewConfigRevision(org, integration, object, chosenRevision).then(setPreview, setPreviewError)
      .finally(() => setPreviewing(false))
  }
  const deploySelected = () => {
    if (!requestKey) return
    const id = savedDeployRequest(requestKey) ??
      (pendingRequest?.revision === chosenRevision ? pendingRequest.id : null) ?? createRequestId()
    storeDeployRequest(requestKey, id)
    setPendingRequest({ revision: chosenRevision, id }); setConfirm(false)
    deploy.mutate({ revision: chosenRevision, requestId: id }, {
      onSuccess: () => { setPreview(null) },
      onError: () => { void deployments.refetch() },
    })
  }
  const previewRollout = () => {
    setRolloutPreviewing(true); setRolloutPreviewError(null); setRolloutPreview(null)
    void previewConfigRollout(org, integration, object, chosenRevision).then(value => {
      setRolloutPreview(value); setRolloutConfirm(true)
    }, setRolloutPreviewError).finally(() => setRolloutPreviewing(false))
  }
  const startRollout = () => {
    if (!rolloutRequestKey) return
    const requestId = savedDeployRequest(rolloutRequestKey) ?? createRequestId()
    storeDeployRequest(rolloutRequestKey, requestId); setRolloutConfirm(false)
    rollout.mutate({ revision: chosenRevision, requestId, automaticRollback }, {
      onSuccess: () => { setPendingRolloutRequest(null); setTab('rollouts') },
      onError: () => { setPendingRolloutRequest({ revision: chosenRevision, id: requestId }); void rollouts.refetch() },
    })
  }

  return <AppShell><div className="workspace-page integration-page">
    <WorkspaceHeader title={remote?.displayName ?? value.profile.name} back={back}
      subtitle={`${value.profile.name} · ${value.profile.code}`}
      status={<StatusIndicator label={label} tone={value.status === 'IN_SYNC' ? 'success' :
        value.status === 'DEPLOYMENT_FAILED' || value.status === 'REMOTE_DRIFT' ? 'danger' : 'neutral'} />} />
    {value.status === 'REMOTE_DRIFT' ? <InlineAlert tone="warning" title={label}>
      {ru ? 'Конфигурация изменена в Remnawave. Проверьте diff перед новым развёртыванием.' :
        'The configuration changed in Remnawave. Review the diff before deploying again.'}</InlineAlert> : null}
    {value.latestDeployment?.status === 'UNKNOWN' ? <InlineAlert tone="warning"
      title={ru ? 'Результат неизвестен' : 'Deployment result is unknown'}>
      {ru ? 'Невозможно безопасно определить, применена ли конфигурация. Синхронизируйте интеграцию перед новым развёртыванием.' :
        'InfraDesk cannot safely determine whether Remnawave applied the configuration. Synchronize before deploying again.'}
    </InlineAlert> : null}
    {value.latestDeployment?.errorCode === 'INTEGRATION_CONFIG_REMOTE_CHANGED' ? <InlineAlert tone="danger"
      title={ru ? 'Удалённая конфигурация изменилась' : 'Remote configuration changed'}>
      {ru ? 'Синхронизируйте интеграцию и проверьте новый diff.' : 'Synchronize and review the new diff.'}
    </InlineAlert> : null}
    <WorkspaceSection title={ru ? 'Состояние' : 'Status'}><PropertyGrid columns={2} items={[
      { label: ru ? 'Удалённый профиль' : 'Remote profile', value: remote?.displayName ?? '—' },
      { label: ru ? 'Профиль InfraDesk' : 'InfraDesk profile', value: value.profile.name },
      { label: ru ? 'Последняя ревизия' : 'Latest revision', value: value.profile.latestRevisionNumber },
      { label: ru ? 'Последнее развёртывание' : 'Latest deployment', value: value.latestDeployment?.revisionNumber ?? '—' },
      { label: ru ? 'Удалённый hash' : 'Remote hash', value: value.remoteSha256 ?? '—' },
      { label: ru ? 'Локальный hash' : 'Local hash', value: value.latestSha256 },
      { label: ru ? 'Узлов с профилем' : 'Nodes using profile', value: value.nodesUsingProfile },
    ]} /></WorkspaceSection>
    <WorkspaceTabs tabs={[
      { id: 'configuration', label: ru ? 'Конфигурация' : 'Configuration' },
      { id: 'revisions', label: ru ? 'Ревизии' : 'Revisions' },
      { id: 'deployments', label: ru ? 'Развёртывания' : 'Deployments' },
      { id: 'rollouts', label: 'Rollouts' },
    ]} active={tab} onChange={next => setTab(next as typeof tab)} />
    {tab === 'configuration' ? <WorkspaceSection title={ru ? `Ревизия ${chosenRevision}` : `Revision ${chosenRevision}`}>
      <div className="integration-row-actions">
        <button className="secondary-button" type="button" disabled={!content || editing} onClick={startEdit}>
          {ru ? 'Новая ревизия' : 'New revision'}</button>
        <button className="secondary-button" type="button" disabled={previewing || editing} onClick={previewSelected}>
          {previewing ? i18n.t.common.loading : ru ? 'Предпросмотр развёртывания' : 'Preview deployment'}</button>
        {canDeploy ? <button className="primary-button" type="button" disabled={busy || editing || !content ||
          value.status === 'WAITING_REFRESH' || value.status === 'UNAVAILABLE'} onClick={() => setConfirm(true)}>
          {ru ? 'Развернуть' : 'Deploy'}</button> : null}
        {canDeploy ? <button className="primary-button" type="button" disabled={busy || editing || !content ||
          rolloutPreviewing || rollouts.data?.some(item => ![
            'SUCCEEDED', 'ROLLED_BACK', 'FAILED', 'UNKNOWN', 'CANCELLED'].includes(item.status))}
          onClick={previewRollout}>{rolloutPreviewing ? i18n.t.common.loading :
            ru ? 'Безопасное развёртывание' : 'Guarded rollout'}</button> : null}
      </div>
      {contentError ? <InlineAlert tone="danger" title={ru ? 'Ошибка загрузки' : 'Load failed'}>
        {describeError(contentError, i18n)}</InlineAlert> : null}
      {editing ? <><textarea aria-label="JSON configuration" className="configuration-content-textarea" value={editor}
        spellCheck={false} onChange={event => { setEditor(event.target.value); setParseError('') }} />
        {parseError ? <InlineAlert tone="danger" title={parseError} /> : null}
        {create.isError ? <InlineAlert tone="danger" title={describeError(create.error, i18n)} /> : null}
        <div className="integration-row-actions"><button className="secondary-button" type="button"
          onClick={() => { setEditing(false); setEditor('') }}>{i18n.t.common.cancel}</button>
          <button className="primary-button" type="button" disabled={create.isPending} onClick={save}>
            {ru ? 'Сохранить ревизию' : 'Save revision'}</button></div></> :
        content ? <pre className="configuration-content-preview">{JSON.stringify(content.config, null, 2)}</pre> :
        <p role="status">{i18n.t.common.loading}</p>}
      {previewError ? <InlineAlert tone="danger" title={ru ? 'Предпросмотр не удался' : 'Preview failed'}>
        {describeError(previewError, i18n)}</InlineAlert> : null}
      {preview ? <div><h3>{ru ? 'Текущая конфигурация Remnawave → выбранная ревизия' :
        'Current Remnawave configuration → selected revision'}</h3>
        <p>{preview.changed ? ru ? 'Есть изменения' : 'Changes found' : ru ? 'Изменений нет' : 'No changes'}</p>
        <pre className="configuration-content-preview">{preview.diff.text}</pre>
        {preview.diff.truncated ? <p>{ru ? 'Diff усечён' : 'Diff truncated'}</p> : null}</div> : null}
      {deploy.isError ? <InlineAlert tone="danger" title={ru ? 'Запрос не подтверждён' : 'Request not confirmed'}>
        {describeError(deploy.error, i18n)} <button type="button" className="text-button" onClick={deploySelected}>
          {ru ? 'Проверить тот же запрос' : 'Check the same request'}</button></InlineAlert> : null}
      {pendingRequest?.revision === chosenRevision && !deploy.isError && !busy ?
        <InlineAlert tone="warning" title={ru ? 'Запрос ожидает подтверждения' : 'Request awaiting confirmation'}>
          <button type="button" className="text-button" onClick={deploySelected} disabled={deploy.isPending}>
            {ru ? 'Проверить тот же запрос' : 'Check the same request'}</button>
        </InlineAlert> : null}
      {rolloutPreviewError || rollout.isError ? <InlineAlert tone="danger"
        title={ru ? 'Запрос rollout не выполнен' : 'Rollout request failed'}>
        {describeError(rolloutPreviewError ?? rollout.error, i18n)}
      </InlineAlert> : null}
      {pendingRolloutRequest ? <InlineAlert tone="warning"
        title={ru ? 'Результат запроса rollout неизвестен' : 'Rollout request result is unknown'}>
        {ru ? 'Повторная проверка использует тот же request ID.' :
          'Check the request again with the same request ID.'}
        <button type="button" className="text-button" onClick={startRollout} disabled={rollout.isPending}>
          {ru ? 'Проверить тот же запрос' : 'Check the same rollout request'}</button>
      </InlineAlert> : null}
    </WorkspaceSection> : null}
    {tab === 'revisions' ? <WorkspaceSection title={ru ? 'Ревизии' : 'Revisions'}>
      {revisions.data?.map(item => <button key={item.revisionNumber} className="secondary-button" type="button"
        onClick={() => { setSelected(item.revisionNumber); setTab('configuration') }}>
        {ru ? 'Ревизия' : 'Revision'} {item.revisionNumber} · {i18n.format.dateTime(item.createdAt)}</button>)}
    </WorkspaceSection> : null}
    {tab === 'deployments' ? <WorkspaceSection title={ru ? 'История развёртываний' : 'Deployment history'}>
      <div className="table-scroll"><table className="data-grid"><thead><tr>
        <th>{ru ? 'Ревизия' : 'Revision'}</th><th>{ru ? 'Статус' : 'Status'}</th>
        <th>{ru ? 'Создано' : 'Created'}</th><th>{ru ? 'Ошибка' : 'Error'}</th></tr></thead>
        <tbody>{deployments.data?.map(item => <tr key={item.id}><td>{item.revisionNumber}</td>
          <td>{item.status}</td><td>{i18n.format.dateTime(item.createdAt)}</td><td>{item.errorCode ?? '—'}</td>
        </tr>)}</tbody></table></div>
    </WorkspaceSection> : null}
    {tab === 'rollouts' ? <WorkspaceSection title={ru ? 'Безопасные rollout' : 'Guarded rollout history'}>
      <p>{ru ? 'Remnawave применяет изменения Config Profile ко всем включённым узлам этого профиля.' :
        'Remnawave applies Config Profile changes to all enabled nodes using this profile.'}</p>
      <p className="muted-copy">{ru ? 'Node-level canary недоступен: rollout выполняется для всего профиля.' :
        'Node-level canary is unavailable for this provider. Rollout is profile-wide.'}</p>
      {rollouts.data?.[0] ? <ol>
        <li>{rollouts.data[0].status === 'PREPARING' ? '•' : '✓'} Preparing fresh baseline</li>
        <li>{rollouts.data[0].baselineRevisionNumber ? '✓' : '•'} Baseline revision {rollouts.data[0].baselineRevisionNumber ?? '—'}</li>
        <li>{['APPLYING', 'VERIFYING', 'ROLLBACK_APPLYING', 'ROLLBACK_VERIFYING', 'SUCCEEDED', 'ROLLED_BACK', 'FAILED', 'UNKNOWN']
          .includes(rollouts.data[0].status) ? '✓' : '•'} Applying revision {rollouts.data[0].targetRevisionNumber}</li>
        <li>{['VERIFYING', 'ROLLBACK_APPLYING', 'ROLLBACK_VERIFYING', 'SUCCEEDED', 'ROLLED_BACK', 'FAILED', 'UNKNOWN']
          .includes(rollouts.data[0].status) ? '✓' : '•'} Waiting for fresh observation</li>
        <li>{['ROLLBACK_APPLYING', 'ROLLBACK_VERIFYING', 'ROLLED_BACK'].includes(rollouts.data[0].status) ? '✕' : '•'} Verifying {rollouts.data[0].affectedNodes} nodes</li>
        {['ROLLBACK_APPLYING', 'ROLLBACK_VERIFYING', 'ROLLED_BACK'].includes(rollouts.data[0].status) ?
          <li>{rollouts.data[0].status === 'ROLLED_BACK' ? '✓' : '•'} Restoring revision {rollouts.data[0].baselineRevisionNumber}</li> : null}
      </ol> : null}
      <div className="table-scroll"><table className="data-grid"><thead><tr>
        <th>{ru ? 'Ревизии' : 'Revisions'}</th><th>{ru ? 'Статус' : 'Status'}</th>
        <th>{ru ? 'Авто rollback' : 'Auto rollback'}</th><th>{ru ? 'Ошибка' : 'Error'}</th></tr></thead>
        <tbody>{rollouts.data?.map(item => <tr key={item.id}>
          <td>{item.baselineRevisionNumber ?? '—'} → {item.targetRevisionNumber}</td><td>{item.status}</td>
          <td>{item.automaticRollback ? 'On' : 'Off'}</td><td>{item.errorCode ?? '—'}</td>
        </tr>)}</tbody></table></div>
    </WorkspaceSection> : null}
    {confirm ? <div className="dialog-backdrop" role="presentation"><section role="dialog" aria-modal="true"
      aria-label={ru ? 'Подтвердить развёртывание' : 'Confirm deployment'} className="monitor-rule-dialog integration-bind-dialog">
      <div className="dialog-heading"><h2>{ru ? `Развернуть ревизию ${chosenRevision}?` : `Deploy revision ${chosenRevision}?`}</h2></div>
      <div className="dialog-body"><p>{ru ? `Это заменит конфигурацию профиля ${remote?.displayName ?? value.profile.name} в Remnawave.` :
        `This will replace the configuration of ${remote?.displayName ?? value.profile.name} in Remnawave.`}</p>
        <p>{ru ? `Узлов с профилем: ${value.nodesUsingProfile}. Они могут быть затронуты.` :
          `Nodes using profile: ${value.nodesUsingProfile}. They may be affected.`}</p></div>
      <div className="dialog-actions"><button className="secondary-button" type="button" onClick={() => setConfirm(false)}>
        {i18n.t.common.cancel}</button><button className="primary-button" type="button" onClick={deploySelected}>
        {ru ? 'Развернуть' : 'Deploy'}</button></div></section></div> : null}
    {rolloutConfirm && rolloutPreview ? <div className="dialog-backdrop" role="presentation"><section role="dialog"
      aria-modal="true" aria-label="Confirm guarded rollout" className="monitor-rule-dialog integration-bind-dialog">
      <div className="dialog-heading"><h2>{ru ? `Guarded rollout ревизии ${chosenRevision}?` :
        `Guarded rollout revision ${chosenRevision}?`}</h2></div>
      <div className="dialog-body">
        <p>{ru ? 'Remnawave применит Config Profile ко всем включённым узлам, использующим этот профиль.' :
          'Remnawave applies a Config Profile update to all enabled nodes using this profile.'}</p>
        <p>{ru ? 'Затронуто узлов' : 'Affected nodes'}: {rolloutPreview.affectedNodes}</p>
        {rolloutPreview.preexistingUnhealthyNodes > 0 ? <InlineAlert tone="warning"
          title={`${rolloutPreview.preexistingUnhealthyNodes} nodes were already unhealthy before rollout.`} /> : null}
        <p>{ru ? 'InfraDesk проверит их состояние после обновления.' :
          'InfraDesk will verify their state after the update.'}</p>
        <label><input type="checkbox" checked={automaticRollback}
          onChange={event => setAutomaticRollback(event.target.checked)} /> {' '}
          {ru ? `Восстановить ревизию ${rolloutPreview.baselineRevisionNumber} при подтверждённой регрессии` :
            `Restore revision ${rolloutPreview.baselineRevisionNumber} if a health regression is confirmed`}</label>
        <p className="muted-copy">{ru ? 'Node-level canary недоступен.' :
          'Node-level canary is unavailable for this provider.'}</p>
      </div><div className="dialog-actions"><button className="secondary-button" type="button"
        onClick={() => setRolloutConfirm(false)}>{i18n.t.common.cancel}</button>
        <button className="primary-button" type="button" onClick={startRollout} disabled={rollout.isPending}>
          {ru ? 'Начать rollout' : 'Start rollout'}</button></div>
    </section></div> : null}
    <p className="muted-copy"><Link to={back.to}>{ru ? 'Вернуться к интеграции' : 'Back to integration'}</Link></p>
  </div></AppShell>
}
