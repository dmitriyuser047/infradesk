import { useEffect, useRef, useState } from 'react'
import { Link, useLocation, useParams } from 'react-router-dom'
import { createRequestId } from '../app/requestId'
import { getConfigRevision, previewConfigRevision, useConfigDeployments, useConfigRevisions,
  previewConfigRollout, useConfigRollouts, useCreateConfigRevision, useDeployConfigRevision,
  useManagedConfigProfile, useStartConfigRollout, type ConfigPreview, type ConfigRevisionContent,
  type ConfigRolloutPreview } from '../api/integrationConfigProfiles'
import { useIntegrationInventory } from '../api/integrationInventory'
import { useIntegration } from '../api/integrations'
import { useOrganizationPermissions } from '../components/auth/authorization'
import { InfrastructureContextPath } from '../components/infrastructure/InfrastructureContextPath'
import { withTab } from '../components/infrastructure/infrastructureLinks'
import { AppShell } from '../components/layout/AppShell'
import { contextSearch } from '../components/layout/workspaceNavigation'
import { InlineAlert, PageLoading, PropertyGrid, StatusIndicator, WorkspaceHeader,
  WorkspaceSection, WorkspaceTabs } from '../components/layout/WorkspacePrimitives'
import { IntegrationDialog } from '../components/integrations/IntegrationDialog'
import { useSyncErrorText } from '../components/integrations/integrationPresentation'
import { RefreshWarning, isUnavailableError } from '../components/layout/RefreshWarning'
import { PageUnavailable, EmptyWorkspaceState } from '../components/layout/WorkspacePrimitives'
import '../styles/pages/integrations.css'
import { useI18n } from '../i18n'
import { describeIntegrationError } from '../components/integrations/integrationPresentation'


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
  const i18n = useI18n(); const c = i18n.t.integrationConfig
  const errorText = useSyncErrorText()
  const previewGeneration = useRef(0)
  const [contentRetry, setContentRetry] = useState(0)
  const location = useLocation()
  const permissions = useOrganizationPermissions(org)
  const canRead = permissions.can('manageIntegrations') && permissions.can('manageConfigurations')
  const canDeploy = canRead && permissions.can('executeOperations')
  const integrationInfo = useIntegration(org, integration, canRead)
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
  const base = `/organizations/${encodeURIComponent(org)}/integrations`
  const scopeSearch = contextSearch(new URLSearchParams(location.search))
  const back = { label: i18n.t.shell.nav.integrations,
    to: `${base}/${encodeURIComponent(integration)}${withTab(scopeSearch, 'profiles')}` }
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
    previewGeneration.current += 1
    setPreviewing(false); setRolloutPreviewing(false); setConfirm(false); setRolloutConfirm(false); setRolloutPreview(null)
    setContent(null); setContentError(null); setPreview(null); setEditing(false); setEditor('')
    void getConfigRevision(org, integration, object, revision).then(value => {
      if (active) setContent(value)
    }, error => { if (active) setContentError(error) })
    return () => { active = false; previewGeneration.current += 1 }
  }, [canRead, org, integration, object, revision, contentRetry])

  if (permissions.isPending) return <AppShell><PageLoading title={c.title} back={back}
    label={i18n.t.common.loading} /></AppShell>
  if (!canRead) return <AppShell><div className="workspace-page"><WorkspaceHeader title={c.title} back={back} />
    <InlineAlert tone="danger" title={c.accessDenied} /></div></AppShell>
  if (managed.isPending) return <AppShell><PageLoading title={c.title} back={back}
    label={i18n.t.common.loading} /></AppShell>
  if (!managed.data || isUnavailableError(managed.error)) return <AppShell><PageUnavailable back={back}
    onRetry={() => managed.refetch()} error={managed.error} notFound={!managed.isError}
    notFoundTitle={c.profileUnavailable} errorTitle={c.loadFailed} /></AppShell>

  const value = managed.data
  const busy = value.latestDeployment?.status === 'QUEUED' || value.latestDeployment?.status === 'RUNNING'
  const label = c.statuses[value.status]
  const chosenRevision = revision ?? value.profile.latestRevisionNumber
  const startEdit = () => {
    if (!content) return
    setEditor(JSON.stringify(content.config, null, 2)); setParseError(''); setEditing(true); setPreview(null)
  }
  const save = () => {
    let json: unknown
    try { json = JSON.parse(editor) } catch { setParseError(c.invalidJson); return }
    if (typeof json !== 'object' || json === null || Array.isArray(json)) {
      setParseError(c.rootMustBeAJsonObject); return
    }
    if (new TextEncoder().encode(editor).length > 256 * 1024) {
      setParseError(c.exceedsThe256KibLimit); return
    }
    create.mutate(json as Record<string, unknown>, { onSuccess: result => {
      setSelected(result.revisionNumber); setEditing(false); setEditor('')
    } })
  }
  const previewSelected = () => {
    setPreviewing(true); setPreviewError(null); setPreview(null)
    const generation = ++previewGeneration.current
    void previewConfigRevision(org, integration, object, chosenRevision).then(value => {
      if (generation === previewGeneration.current) setPreview(value)
    }, error => { if (generation === previewGeneration.current) setPreviewError(error) })
      .finally(() => { if (generation === previewGeneration.current) setPreviewing(false) })
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
    const generation = ++previewGeneration.current
    void previewConfigRollout(org, integration, object, chosenRevision).then(value => {
      if (generation === previewGeneration.current) { setRolloutPreview(value); setRolloutConfirm(true) }
    }, error => { if (generation === previewGeneration.current) setRolloutPreviewError(error) })
      .finally(() => { if (generation === previewGeneration.current) setRolloutPreviewing(false) })
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

  return <AppShell><div className="workspace-page work-page detail-page integration-page">
    {integrationInfo.data ? <InfrastructureContextPath items={[
      { label: i18n.t.integrations.title, to: `${base}${scopeSearch}` },
      { label: integrationInfo.data.name, to: back.to },
      { label: remote?.displayName ?? value.profile.name },
    ]} /> : null}
    <WorkspaceHeader title={remote?.displayName ?? value.profile.name} back={integrationInfo.data ? undefined : back}
      subtitle={`${value.profile.name} · ${value.profile.code}`}
      status={<StatusIndicator label={label} tone={value.status === 'IN_SYNC' ? 'success' :
        value.status === 'REMOTE_DRIFT' ? 'warning' : value.status === 'DEPLOYMENT_FAILED' ? 'danger' : 'neutral'} />} />
    {managed.isError ? <RefreshWarning updatedAt={managed.dataUpdatedAt} retry={() => managed.refetch()} /> : null}
    {value.status === 'REMOTE_DRIFT' ? <InlineAlert tone="warning" title={label}>
      {c.driftHelp}</InlineAlert> : null}
    {value.latestDeployment?.status === 'UNKNOWN' || rollouts.data?.[0]?.status === 'UNKNOWN' ? <InlineAlert tone="warning"
      title={c.deploymentResultIsUnknown}>
      {c.unknownDetail}
    </InlineAlert> : null}
    {value.latestDeployment?.errorCode === 'INTEGRATION_CONFIG_REMOTE_CHANGED' ? <InlineAlert tone="danger"
      title={c.remoteConfigurationChanged}>
      {c.syncReview}
    </InlineAlert> : null}
    <WorkspaceSection title={c.status}><PropertyGrid columns={2} items={[
      { label: c.remoteProfile, value: remote?.displayName ?? '—' },
      { label: c.infradeskProfile, value: value.profile.name },
      { label: c.latestRevision, value: value.profile.latestRevisionNumber },
      { label: c.latestDeployment, value: value.latestDeployment?.revisionNumber ?? '—' },
      { label: c.nodesUsingProfile, value: value.nodesUsingProfile },
    ]} /></WorkspaceSection>
    <WorkspaceTabs tabs={[
      { id: 'configuration', label: c.configuration },
      { id: 'revisions', label: c.revisions },
      { id: 'deployments', label: c.deployments },
      { id: 'rollouts', label: c.safeDeployments },
    ]} active={tab} onChange={next => setTab(next as typeof tab)} />
    {tab === 'configuration' ? <WorkspaceSection title={c.revisionTitle(chosenRevision)}>
      <label className="field integration-revision-select">{c.revision}<select value={chosenRevision} disabled={editing}
        onChange={event => setSelected(Number(event.target.value))}>
        {(revisions.data ?? [{ revisionNumber: chosenRevision }]).map(item => <option key={item.revisionNumber}
          value={item.revisionNumber}>{c.revisionTitle(item.revisionNumber)}</option>)}
      </select></label>
      <div className="integration-row-actions">
        <button className="secondary-button" type="button" disabled={!content || editing} onClick={startEdit}>
          {c.newRevision}</button>
        <button className="secondary-button" type="button" disabled={previewing || rolloutPreviewing || editing} onClick={previewSelected}>
          {previewing ? i18n.t.common.loading : c.previewDeployment}</button>
        {canDeploy ? <button className="secondary-button" type="button" disabled={busy || editing || !content ||
          value.status === 'WAITING_REFRESH' || value.status === 'UNAVAILABLE' || !preview || preview.revisionNumber !== chosenRevision} onClick={() => setConfirm(true)}>
          {c.deploy}</button> : null}
        {canDeploy ? <button className="primary-button" type="button" disabled={busy || editing || !content ||
          rolloutPreviewing || previewing || rollouts.data?.some(item => ![
            'SUCCEEDED', 'ROLLED_BACK', 'FAILED', 'UNKNOWN', 'CANCELLED'].includes(item.status))}
          onClick={previewRollout}>{rolloutPreviewing ? i18n.t.common.loading :
            c.guardedRollout}</button> : null}
      </div>
      {contentError ? <InlineAlert tone="danger" title={c.loadFailed} action={<button className="secondary-button" onClick={() => setContentRetry(value => value + 1)}>{i18n.t.common.retry}</button>}>
        {describeIntegrationError(contentError, i18n)}</InlineAlert> : null}
      {editing ? <><p className="muted-copy">{c.immutable}</p><textarea aria-label={c.jsonConfiguration} className="configuration-content-textarea" value={editor}
        spellCheck={false} onChange={event => { setEditor(event.target.value); setParseError('') }} />
        {parseError ? <InlineAlert tone="danger" title={parseError} /> : null}
        {create.isError ? <InlineAlert tone="danger" title={(describeIntegrationError(create.error, i18n)) ?? undefined} /> : null}
        <div className="integration-row-actions"><button className="secondary-button" type="button"
          onClick={() => { setEditing(false); setEditor('') }}>{i18n.t.common.cancel}</button>
          <button className="primary-button" type="button" disabled={create.isPending} onClick={save}>
            {c.saveRevision}</button></div></> :
        content ? <pre className="configuration-content-preview">{JSON.stringify(content.config, null, 2)}</pre> :
        <p role="status">{i18n.t.common.loading}</p>}
      {previewError ? <InlineAlert tone="danger" title={c.previewFailed}>
        {describeIntegrationError(previewError, i18n)}</InlineAlert> : null}
      {preview ? <div><h3>{c.diffTitle}</h3>
        <p>{preview.changed ? c.changesFound : c.noChanges}</p>
        <pre className="configuration-content-preview">{preview.diff.text}</pre>
        {preview.diff.truncated ? <p>{c.diffTruncated}</p> : null}</div> : null}
      {deploy.isError ? <InlineAlert tone="danger" title={c.requestNotConfirmed}>
        {describeIntegrationError(deploy.error, i18n)} <button type="button" className="text-button" onClick={deploySelected}>
          {c.checkTheSameRequest}</button></InlineAlert> : null}
      {pendingRequest?.revision === chosenRevision && !deploy.isError && !busy ?
        <InlineAlert tone="warning" title={c.requestAwaitingConfirmation}>
          <button type="button" className="text-button" onClick={deploySelected} disabled={deploy.isPending}>
            {c.checkTheSameRequest}</button>
        </InlineAlert> : null}
      {rolloutPreviewError || rollout.isError ? <InlineAlert tone="danger"
        title={c.rolloutRequestFailed}>
        {describeIntegrationError(rolloutPreviewError ?? rollout.error, i18n)}
      </InlineAlert> : null}
      {pendingRolloutRequest ? <InlineAlert tone="warning"
        title={c.rolloutRequestResultIsUnknown}>
        {c.retryDetail}
        <button type="button" className="text-button" onClick={startRollout} disabled={rollout.isPending}>
          {c.checkTheSameRolloutRequest}</button>
      </InlineAlert> : null}
    </WorkspaceSection> : null}
    {tab === 'revisions' ? <WorkspaceSection title={c.revisions}>
      <HistoryState query={revisions} />
      {!isUnavailableError(revisions.error) && revisions.data?.map(item => <button key={item.revisionNumber} className="secondary-button" type="button"
        onClick={() => { setSelected(item.revisionNumber); setTab('configuration') }}>
        {c.revision} {item.revisionNumber} · {i18n.format.dateTime(item.createdAt)}
        {item.revisionNumber === value.profile.latestRevisionNumber ? ` · ${c.latest}` : ''}
        {item.revisionNumber === chosenRevision ? ` · ${c.selected}` : ''}
        {deployments.data?.some(deployment => deployment.revisionNumber === item.revisionNumber && deployment.status === 'SUCCEEDED') ? ` · ${c.wasDeployed}` : ''}</button>)}
    </WorkspaceSection> : null}
    {tab === 'deployments' ? <WorkspaceSection title={c.deploymentHistory}>
      <HistoryState query={deployments} />
      {!isUnavailableError(deployments.error) && deployments.data?.length ? <div className="table-scroll"><table className="data-grid"><thead><tr>
        <th>{c.revision}</th><th>{c.status}</th>
        <th>{c.created}</th><th>{c.error}</th></tr></thead>
        <tbody>{!isUnavailableError(deployments.error) && deployments.data?.map(item => <tr key={item.id}><td>{item.revisionNumber}</td>
          <td><StatusIndicator label={c.operations[item.status]} tone={item.status === "UNKNOWN" ? "warning" : item.status === "FAILED" ? "danger" : item.status === "SUCCEEDED" ? "success" : "info"} /></td><td>{i18n.format.dateTime(item.createdAt)}</td><td>{errorText(item.errorCode) ?? '—'}</td>
        </tr>)}</tbody></table></div> : null}
    </WorkspaceSection> : null}
    {tab === 'rollouts' ? <WorkspaceSection title={c.guardedRolloutHistory}>
      <HistoryState query={rollouts} />
      <p className="muted-copy">{c.safeScope}</p>
      {!isUnavailableError(rollouts.error) && rollouts.data?.[0] ? <PropertyGrid columns={2} items={[
        { label: c.status, value: c.operations[rollouts.data[0].status] },
        { label: c.baseline, value: rollouts.data[0].baselineRevisionNumber ?? '—' },
        { label: c.target, value: rollouts.data[0].targetRevisionNumber },
        { label: c.affectedNodes, value: rollouts.data[0].affectedNodes },
        { label: c.alreadyUnhealthy, value: rollouts.data[0].preexistingUnhealthyNodes },
      ]} /> : null}
      {!isUnavailableError(rollouts.error) && rollouts.data?.length ? <div className="table-scroll"><table className="data-grid"><thead><tr>
        <th>{c.revisions}</th><th>{c.status}</th>
        <th>{c.automaticRollback}</th><th>{c.error}</th></tr></thead>
        <tbody>{!isUnavailableError(rollouts.error) && rollouts.data?.map(item => <tr key={item.id}>
          <td>{item.baselineRevisionNumber ?? '—'} → {item.targetRevisionNumber}</td><td><StatusIndicator label={c.operations[item.status]} tone={item.status === "UNKNOWN" || item.status === "ROLLED_BACK" ? "warning" : item.status === "FAILED" ? "danger" : item.status === "SUCCEEDED" ? "success" : "info"} /></td>
          <td>{item.automaticRollback ? i18n.t.integrations.enabled : i18n.t.integrations.disabled}</td><td>{errorText(item.errorCode) ?? '—'}</td>
        </tr>)}</tbody></table></div> : null}
    </WorkspaceSection> : null}
    {confirm ? <IntegrationDialog title={c.deployRevision(chosenRevision)} onClose={() => setConfirm(false)}
      busy={deploy.isPending} actions={<><button className="secondary-button" type="button" onClick={() => setConfirm(false)}>{i18n.t.common.cancel}</button>
        <button className="primary-button" type="button" onClick={deploySelected} disabled={deploy.isPending}>{c.deploy}</button></>}>
      <p>{c.replaceProfile(remote?.displayName ?? value.profile.name)}</p><p>{c.nodesAffected(value.nodesUsingProfile)}</p>
      <p className="muted-copy">{c.safeScope}</p>
      {preview ? <pre className="configuration-content-preview">{preview.diff.text}</pre> : null}
    </IntegrationDialog> : null}
    {rolloutConfirm && rolloutPreview ? <IntegrationDialog title={c.safeRevision(chosenRevision)}
      onClose={() => setRolloutConfirm(false)} busy={rollout.isPending} actions={<>
        <button className="secondary-button" type="button" onClick={() => setRolloutConfirm(false)}>{i18n.t.common.cancel}</button>
        <button className="primary-button" type="button" onClick={startRollout} disabled={rollout.isPending}>{c.startRollout}</button></>}>
      <strong>{remote?.displayName ?? value.profile.name}</strong><p>{c.safeScope}</p>
      <PropertyGrid columns={2} items={[
        { label: c.baseline, value: rolloutPreview.baselineRevisionNumber },
        { label: c.target, value: rolloutPreview.targetRevisionNumber },
        { label: c.affectedNodes, value: rolloutPreview.affectedNodes },
      ]} />
      {rolloutPreview.preexistingUnhealthyNodes > 0 ? <InlineAlert tone="warning" title={c.alreadyUnhealthy}>
        {rolloutPreview.preexistingUnhealthyNodes}</InlineAlert> : null}
      <p>{c.verifyNodes}</p>
      <label><input type="checkbox" checked={automaticRollback}
        onChange={event => setAutomaticRollback(event.target.checked)} /> {c.automaticRollback}</label>
      <p className="muted-copy">{c.restoreRevision(rolloutPreview.baselineRevisionNumber)}</p>
    </IntegrationDialog> : null}
    <p className="muted-copy"><Link to={back.to}>{c.backToIntegration}</Link></p>
  </div></AppShell>
}

function HistoryState({ query }: { query: { isPending: boolean; isError: boolean; error: unknown;
  data?: unknown[]; dataUpdatedAt: number; refetch: () => unknown } }) {
  const i18n = useI18n()
  if (query.isPending) return <div className="row-skeleton" aria-label={i18n.t.common.loading}><span /><span /></div>
  if (query.isError) return query.data && !isUnavailableError(query.error) ?
    <RefreshWarning updatedAt={query.dataUpdatedAt} retry={() => { void query.refetch() }} /> :
    <InlineAlert tone="danger" title={describeIntegrationError(query.error, i18n)} action={
      <button className="secondary-button" onClick={() => query.refetch()}>{i18n.t.common.retry}</button>} />
  return query.data?.length === 0 ? <EmptyWorkspaceState compact title={i18n.t.integrationConfig.empty} /> : null
}
