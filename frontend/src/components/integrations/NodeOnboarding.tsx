import { useEffect, useMemo, useRef, useState } from 'react'
import { createRequestId } from '../../app/requestId'
import { readPendingSubmission, storePendingSubmission, type PendingSubmission } from '../../app/pendingSubmission'
import { ApiError } from '../../api/httpClient'
import { Link, useSearchParams } from 'react-router-dom'
import { useNodeOnboardingHistory, useNodeOnboardingOptions, useNodeOnboardingRun, usePreviewNodeOnboarding, useReconcileNodeOnboarding, useStartNodeOnboarding } from '../../api/nodeOnboarding'
import { useResource } from '../../api/resources'
import { useOrganizationPermissions } from '../auth/authorization'
import { useI18n } from '../../i18n'
import { InlineAlert, PendingButton, WorkspaceSection } from '../layout/WorkspacePrimitives'
import { IntegrationDialog } from './IntegrationDialog'
import type { NodeOnboardingPreview, NodeOnboardingPreviewRequest, NodeOnboardingRecoveryAction, NodeOnboardingRecoverySummary, NodeOnboardingRun } from '../../types/nodeOnboarding'

const phases = ['VALIDATE', 'PREPARE_SERVER', 'CREATE_NODE', 'GET_INSTALLATION_DATA', 'CONFIGURE_NODE_FIREWALL', 'INSTALL_NODE', 'START_NODE', 'VERIFY_LOCAL_NODE', 'WAIT_FOR_PANEL', 'SYNC_INVENTORY', 'BIND_RESOURCE', 'SET_DESIRED_STATE', 'FINAL_VERIFY'] as const
const texts = {
  en: { add: 'Add node', title: 'Add a Remnawave node', steps: ['Server', 'Remnawave', 'Preparation', 'Preview', 'Execution'],
    unsupported: 'Node provisioning is unavailable for this Remnawave API connection. Sync, inventory, and configuration actions remain available.',
    loading: 'Loading available servers and profiles…', server: 'Server', serverHint: 'Choose a server that already has a server profile assigned.',
    name: 'Node name', address: 'Node address', port: 'Node port', profile: 'Configuration profile', inbounds: 'Active inbounds', cidrs: 'Panel CIDRs', cidrHint: 'Comma-separated IPv4/IPv6 CIDR ranges allowed to reach the node management port.',
    ssh: 'SSH', environment: 'Environment', serverProfile: 'Server profile', missingProfile: 'Assign a server profile in Automation before onboarding this node.',
    automation: 'Open server Automation', next: 'Continue', back: 'Back', preview: 'Review changes', apply: 'Start onboarding', close: 'Close', history: 'Recent onboarding runs', noHistory: 'No onboarding runs yet.',
    changes: 'Changes', warnings: 'Warnings', blockers: 'Blocking problems', run: 'Run', phases: 'Progress', state: 'State', partial: 'Results so far', externalId: 'Remnawave node ID', baseline: 'Server preparation run', syncId: 'Inventory sync session',
    success: 'Node onboarding completed.', failed: 'Onboarding failed. Review the completed phases and safe error message before choosing a next step.', unknown: 'The outcome is unknown. Check Remnawave and the server before taking further action; do not repeat the whole onboarding run.', active: 'Onboarding is in progress. You can leave this page and return using this run.',
    requestError: 'The request could not be confirmed. Retry with the same request ID to safely recover the result.', formError: 'Enter a node name and address, a port from 1 to 65535, a configuration profile, and valid CIDR ranges.',
    checkAgain: 'Check again', restore: 'Restore existing node', recreate: 'Recreate node', deleteRecreate: 'Delete and recreate',
    missingNode: 'The node was not found in Remnawave.', conflict: 'The Remnawave node conflicts with the previous installation identity. No action is available.',
    recoveryUnknown: 'The remote state is still unknown. Check again to repeat the read-only observation.',
    oldIdentity: 'Previous node UUID', oldCorrelation: 'Previous correlation ID', newCorrelation: 'New correlation ID',
    recreateApproval: 'I reviewed the previous node UUID and approve creating a new Remnawave node with a new correlation ID.',
    deleteApproval: 'I approve deleting the existing Remnawave node and creating a replacement with a new correlation ID.',
    apiUnavailable: 'Provisioning support was not confirmed by the Remnawave API.', phaseNames: { VALIDATE: 'Validate request', PREPARE_SERVER: 'Prepare server', DELETE_NODE: 'Delete existing Remnawave node', CONFIRM_NODE_DELETED: 'Confirm node deletion', RETIRE_NODE_FIREWALL: 'Retire previous node firewall rule', RETIRE_LOCAL_NODE: 'Retire previous local node installation', CREATE_NODE: 'Create Remnawave node', GET_INSTALLATION_DATA: 'Get installation data', CONFIGURE_NODE_FIREWALL: 'Configure node firewall', INSTALL_NODE: 'Install node', START_NODE: 'Start node', VERIFY_LOCAL_NODE: 'Verify local node', WAIT_FOR_PANEL: 'Wait for Remnawave panel', SYNC_INVENTORY: 'Sync inventory', BIND_RESOURCE: 'Bind server resource', SET_DESIRED_STATE: 'Set desired state', FINAL_VERIFY: 'Final verification' } },
  ru: { add: 'Добавить узел', title: 'Добавление узла Remnawave', steps: ['Сервер', 'Remnawave', 'Подготовка', 'Проверка', 'Выполнение'],
    unsupported: 'Подготовка узлов недоступна для этого подключения к API Remnawave. Синхронизация, инвентарь и настройка остаются доступны.',
    loading: 'Загрузка серверов и профилей…', server: 'Сервер', serverHint: 'Выберите сервер с назначенным серверным профилем.',
    name: 'Имя узла', address: 'Адрес узла', port: 'Порт узла', profile: 'Профиль конфигурации', inbounds: 'Активные входящие подключения', cidrs: 'CIDR панели', cidrHint: 'CIDR IPv4/IPv6 через запятую, которым разрешён доступ к панели.',
    ssh: 'SSH', environment: 'Окружение', serverProfile: 'Серверный профиль', missingProfile: 'Перед добавлением узла назначьте серверный профиль в разделе автоматизации.',
    automation: 'Открыть автоматизацию сервера', next: 'Далее', back: 'Назад', preview: 'Проверить изменения', apply: 'Запустить добавление', close: 'Закрыть', history: 'Последние запуски', noHistory: 'Запусков добавления узлов пока нет.',
    changes: 'Изменения', warnings: 'Предупреждения', blockers: 'Блокирующие проблемы', run: 'Запуск', phases: 'Ход выполнения', state: 'Состояние', partial: 'Промежуточные результаты', externalId: 'ID узла Remnawave', baseline: 'Запуск подготовки сервера', syncId: 'Сессия синхронизации инвентаря',
    success: 'Добавление узла завершено.', failed: 'Не удалось добавить узел. Проверьте этапы и безопасное описание ошибки перед выбором дальнейших действий.', unknown: 'Результат неизвестен. Проверьте Remnawave и сервер перед дальнейшими действиями; не повторяйте весь процесс.', active: 'Добавление выполняется. Можно закрыть страницу и вернуться по этой ссылке.',
    requestError: 'Не удалось подтвердить запрос. Повторите отправку с тем же ID запроса, чтобы безопасно получить результат.', formError: 'Укажите имя и адрес узла, порт от 1 до 65535, профиль конфигурации и корректные CIDR.',
    checkAgain: 'Проверить снова', restore: 'Восстановить существующий узел', recreate: 'Пересоздать узел', deleteRecreate: 'Удалить и пересоздать',
    missingNode: 'Узел больше не найден в Remnawave.', conflict: 'Узел Remnawave конфликтует с исходной идентичностью установки. Действие недоступно.',
    recoveryUnknown: 'Удалённое состояние всё ещё неизвестно. Проверьте снова, чтобы повторить только чтение состояния.',
    oldIdentity: 'Предыдущий UUID узла', oldCorrelation: 'Предыдущий ID корреляции', newCorrelation: 'Новый ID корреляции',
    recreateApproval: 'Я проверил предыдущий UUID узла и подтверждаю создание нового узла Remnawave с новым ID корреляции.',
    deleteApproval: 'Я подтверждаю удаление существующего узла Remnawave и создание замены с новым ID корреляции.',
    apiUnavailable: 'API Remnawave не подтвердил поддержку подготовки узлов.', phaseNames: { VALIDATE: 'Проверка запроса', PREPARE_SERVER: 'Подготовка сервера', DELETE_NODE: 'Удаление существующего узла Remnawave', CONFIRM_NODE_DELETED: 'Подтверждение удаления узла', RETIRE_NODE_FIREWALL: 'Удаление правила межсетевого экрана предыдущего узла', RETIRE_LOCAL_NODE: 'Удаление предыдущей локальной установки узла', CREATE_NODE: 'Создание узла Remnawave', GET_INSTALLATION_DATA: 'Получение данных установки', CONFIGURE_NODE_FIREWALL: 'Настройка межсетевого экрана', INSTALL_NODE: 'Установка узла', START_NODE: 'Запуск узла', VERIFY_LOCAL_NODE: 'Проверка узла на сервере', WAIT_FOR_PANEL: 'Ожидание панели Remnawave', SYNC_INVENTORY: 'Синхронизация инвентаря', BIND_RESOURCE: 'Привязка сервера', SET_DESIRED_STATE: 'Установка желаемого состояния', FINAL_VERIFY: 'Итоговая проверка' } },
} as const

type Step = 0 | 1 | 2 | 3 | 4
type OnboardingRunPhase = typeof phases[number] | 'DELETE_NODE' | 'CONFIRM_NODE_DELETED' | 'RETIRE_NODE_FIREWALL' | 'RETIRE_LOCAL_NODE'
const activeRun = (state: NodeOnboardingRun['state']) => state === 'QUEUED' || state === 'RUNNING'
function validCidr(value: string) {
  const [ip, mask, ...rest] = value.split('/')
  if (rest.length || !ip || !mask) return false
  const ipv4 = ip.match(/^(\d{1,3}\.){3}\d{1,3}$/)
    && ip.split('.').every(part => Number(part) <= 255)
  let ipv6 = false
  if (ip.includes(':')) {
    try { ipv6 = new URL(`http://[${ip}]/`).hostname.length > 2 } catch { ipv6 = false }
  }
  if (!ipv4 && !ipv6) return false
  const max = ipv4 ? 32 : 128
  return /^\d+$/.test(mask) && Number(mask) > 0 && Number(mask) <= max
}
export function NodeOnboarding({ organizationId, integrationId }: { organizationId: string; integrationId: string }) {
  const i18n = useI18n(); const copy = texts[i18n.locale]
  const permissions = useOrganizationPermissions(organizationId)
  const canConfigure = permissions.can('manageIntegrations') && permissions.can('manageConfigurations') && permissions.can('executeOperations')
  const canRead = permissions.can('readOrganization')
  const canManageIntegrations = permissions.can('manageIntegrations')
  const options = useNodeOnboardingOptions(organizationId, integrationId, canManageIntegrations)
  const history = useNodeOnboardingHistory(organizationId, integrationId, canRead)
  const previewRequest = usePreviewNodeOnboarding(organizationId, integrationId)
  const start = useStartNodeOnboarding(organizationId, integrationId)
  const reconcile = useReconcileNodeOnboarding(organizationId, integrationId)
  const [params, setParams] = useSearchParams()
  const runId = params.get('onboardingRun')
  const submissionKey = `node-onboarding:${organizationId}:${integrationId}`
  const [unresolved,setUnresolved] = useState<PendingSubmission|null>(()=>readPendingSubmission(submissionKey))
  const runQuery = useNodeOnboardingRun(organizationId, integrationId, runId ?? unresolved?.planId ?? null)
  const [open, setOpen] = useState(false); const [step, setStep] = useState<Step>(0)
  const [resourceId, setResourceId] = useState(''); const [nodeName, setNodeName] = useState('')
  const [address, setAddress] = useState(''); const [port, setPort] = useState('2222')
  const [configProfileId, setConfigProfileId] = useState(''); const [activeInboundIds, setActiveInboundIds] = useState<string[]>([])
  const [cidrs, setCidrs] = useState(''); const [reviewed, setReviewed] = useState<{plan:NodeOnboardingPreview;requestId:string}|null>(null)
  const preview = reviewed?.plan
  const [workflowError,setWorkflowError] = useState(false)
  const [recoveryConfirmed, setRecoveryConfirmed] = useState(false)
  const submitting=useRef(false)
  const selectedResource = useResource(organizationId, resourceId || undefined)
  const server = options.data?.servers.find(value => value.id === resourceId)
  const profile = options.data?.profiles.find(value => value.id === configProfileId)
  const cidrValues = useMemo(() => cidrs.split(/[\s,]+/).filter(Boolean), [cidrs])
  const run = runQuery.data?.run
  const hasRun = Boolean(runId)
  const setRunInUrl = (id: string | null) => setParams(previous => { const next = new URLSearchParams(previous); if (id) next.set('onboardingRun', id); else next.delete('onboardingRun'); return next }, { replace: true })
  const closeWizard = () => { setOpen(false); setStep(0); setReviewed(null); setRunInUrl(null) }
  const selectServer = (id: string) => { setResourceId(id); const value = options.data?.servers.find(item => item.id === id); if (value) { setAddress(value.address); if (!nodeName) setNodeName(value.name) } }
  const exactBody = (): NodeOnboardingPreviewRequest => ({ resourceId, nodeName: nodeName.trim(), address: address.trim(), nodePort: Number(port), configProfileId, activeInboundIds, panelCidrs: cidrValues, desiredState: 'ENABLED' })
  const formValid = Boolean(server && nodeName.trim().length >= 3 && nodeName.trim().length <= 30 && !/[\x00-\x1f\x7f]/.test(nodeName) && address.trim() && Number.isInteger(Number(port)) && Number(port) >= 1 && Number(port) <= 65535 && profile && activeInboundIds.length > 0 && cidrValues.length > 0 && cidrValues.length <= 32 && new Set(cidrValues).size === cidrValues.length && cidrValues.every(validCidr))
  const makePreview = async () => { setWorkflowError(false); const value = await previewRequest.mutateAsync(exactBody()); start.reset(); setReviewed({plan:value,requestId:createRequestId()}); setStep(3) }
  const reconcileRun = async (sourceRunId: string, action: NodeOnboardingRecoveryAction) => {
    if (!canConfigure || activeRun(run?.state ?? 'PLANNED') || reconcile.isPending) return
    setWorkflowError(false); setRecoveryConfirmed(false)
    try {
      const value = await reconcile.mutateAsync({ runId: sourceRunId, action })
      start.reset(); setReviewed({ plan: value, requestId: createRequestId() }); setStep(3); setOpen(true)
    } catch { setWorkflowError(true) }
  }
  const recovery = preview?.recovery
  const needsRecreateApproval = recovery?.action === 'RECREATE' || recovery?.action === 'DELETE_RECREATE'
  const recoveryBlocked = recovery?.state === 'UNKNOWN' || recovery?.state === 'PRESENT_CONFLICT'
  const apply = async (identity:PendingSubmission, retryConfirmed = false) => {
    const requestRecoveryAction = preview?.recovery?.action ?? (identity.planId === unresolved?.planId ? runQuery.data?.run.recovery?.action : undefined)
    const requestNeedsRecreateApproval = requestRecoveryAction === 'RECREATE' || requestRecoveryAction === 'DELETE_RECREATE'
    const confirmed = recoveryConfirmed || retryConfirmed
    if (submitting.current || start.isPending || !canConfigure || (preview?.blockingProblems.length ?? 0)>0 || recoveryBlocked || (requestNeedsRecreateApproval && !confirmed)) {setWorkflowError(true);return}
    submitting.current=true;setWorkflowError(false)
    storePendingSubmission(submissionKey,identity)
    try { const value = await start.mutateAsync({ ...identity, ...(requestNeedsRecreateApproval ? { confirmRecreate: true } : {}) }); storePendingSubmission(submissionKey,null);setUnresolved(null);setRunInUrl(value.id);setStep(4);setRecoveryConfirmed(false) }
    catch(error) { if (!(error instanceof ApiError) || error.status>=500 || error.status===408) setUnresolved(identity)
      else {storePendingSubmission(submissionKey,null);setUnresolved(null)} }
    finally {submitting.current=false}
  }
  useEffect(()=>{
    const found = unresolved && (history.data?.items.find(item=>item.requestId===unresolved.requestId && item.state!=='PLANNED') ??
      (runQuery.data?.run.state!=='PLANNED' && runQuery.data?.run.id===unresolved.planId ? runQuery.data.run : null))
    if (!found) return
    storePendingSubmission(submissionKey,null);setUnresolved(null);setRunInUrl(found.id);setOpen(true);setStep(4)
  },[unresolved,history.data,runQuery.data,submissionKey])
  const openExisting = (id: string) => { setRunInUrl(id); setOpen(true); setStep(4) }
  const unsupported = options.data !== undefined && !options.data.nodeApi?.provisioningReady
  const current = hasRun ? run : null

  return <WorkspaceSection title={copy.add}>
    <div className="integration-row-actions">
      <button className="primary-button" type="button" disabled={!!unresolved || !canConfigure || unsupported || options.isPending || options.isError} onClick={() => { setOpen(true); if (runId) setStep(4) }}>{copy.add}</button>
      {history.data?.items.length ? <span className="muted-copy">{copy.history}: {history.data.items.length}</span> : null}
    </div>
    {!canConfigure ? <p className="muted-copy">{i18n.locale === 'ru' ? 'Для добавления узла требуются права управления интеграциями, конфигурациями и операциями.' : 'Adding a node requires manage integrations, manage configurations, and execute operations permissions.'}</p> : null}
    {unsupported ? <InlineAlert tone="info" title={copy.apiUnavailable}>{options.data?.nodeApi?.blocker ?? copy.unsupported}</InlineAlert> : null}
    {options.isError ? <InlineAlert tone="danger" title={copy.apiUnavailable} /> : null}
    {history.isError ? <p role="alert">{i18n.locale === 'ru' ? 'Не удалось загрузить историю запусков.' : 'Could not load onboarding history.'}</p> : null}
    {history.data?.items.length === 0 ? <p className="muted-copy">{copy.noHistory}</p> : null}
    {history.data?.items.map(item => <button key={item.id} className="secondary-button" type="button" onClick={() => openExisting(item.id)}>
      {item.nodeName} · {item.address} · {item.state}</button>)}
    {hasRun && runQuery.isPending ? <p role="status">{copy.loading}</p> : null}
    {hasRun && runQuery.isError ? <InlineAlert tone="danger" title={i18n.locale === 'ru' ? 'Не удалось загрузить запуск' : 'Could not load onboarding run'} /> : null}
    {unresolved && !open ? <InlineAlert tone="warning" title={i18n.t.common.unresolvedSubmission} action={<PendingButton pending={start.isPending} pendingLabel={i18n.t.common.inProgress} disabled={!canConfigure || !runQuery.isSuccess} onClick={()=>void apply(unresolved, runQuery.data?.run.recovery?.action === 'RECREATE' || runQuery.data?.run.recovery?.action === 'DELETE_RECREATE')}>{i18n.t.common.recoverSubmission}</PendingButton>} /> : null}
    {workflowError && !previewRequest.isError ? <InlineAlert tone="danger" title={i18n.t.common.operationBlocked} /> : null}
    {!open && start.isError ? <InlineAlert tone="danger" title={copy.requestError} /> : null}
    {open || hasRun ? <IntegrationDialog title={copy.title} size="large" onClose={closeWizard} busy={start.isPending || previewRequest.isPending || reconcile.isPending}
      actionNote={step === 3 && preview?.blockingProblems.length ? i18n.t.common.blockedAction(preview.blockingProblems.length) : undefined}
      actionFeedback={previewRequest.isError || start.isError || workflowError ? <InlineAlert tone="danger" title={start.isError ? copy.requestError : i18n.t.common.operationBlocked} /> : undefined}
      actions={<>
        {step > 0 && step < 4 && !preview?.recovery ? <button className="secondary-button" type="button" disabled={!!unresolved || start.isPending || previewRequest.isPending} onClick={() => { setReviewed(null); setStep((step - 1) as Step) }}>{copy.back}</button> : null}
        {step < 3 ? <button className="primary-button" type="button" disabled={!canConfigure || unsupported || options.isPending || (step === 0 && (!resourceId || !server?.serverProfileName || server.blockingProblems.length > 0)) || (step === 1 && (!nodeName.trim() || !address.trim() || !Number(port) || !profile || activeInboundIds.length === 0)) || (step === 2 && !formValid)} onClick={() => setStep((step + 1) as Step)}>{copy.next}</button> : null}
        {step === 3 && !preview?.recovery ? <PendingButton className={preview ? 'secondary-button' : 'primary-button'} type="button" pending={previewRequest.isPending} pendingLabel={i18n.t.common.inProgress} disabled={!!unresolved || !canConfigure || !formValid} onClick={() => void makePreview().catch(() => setWorkflowError(true))}>{copy.preview}</PendingButton> : null}
        {step === 3 && reviewed?.plan.recovery ? <PendingButton className="secondary-button" type="button" pending={reconcile.isPending} pendingLabel={i18n.t.common.inProgress} disabled={!canConfigure} onClick={() => void reconcileRun(reviewed.plan.recovery!.sourceRunId, reviewed.plan.recovery!.action === 'DELETE_RECREATE' ? 'DELETE_RECREATE' : 'RECOVER')}>{i18n.locale === 'ru' ? 'Проверить локальную установку' : 'Check local installation'}</PendingButton> : null}
        {step === 3 && reviewed && !recoveryBlocked ? <PendingButton className="primary-button" type="button" pending={start.isPending} pendingLabel={i18n.t.common.inProgress} disabled={!canConfigure || reviewed.plan.blockingProblems.length > 0 || (needsRecreateApproval && !recoveryConfirmed)} onClick={() => void apply({planId:reviewed.plan.run.id,requestId:reviewed.requestId})}>{recovery?.action === 'RECOVER' ? copy.restore : recovery?.action === 'RECREATE' ? copy.recreate : recovery?.action === 'DELETE_RECREATE' ? copy.deleteRecreate : copy.apply}</PendingButton> : null}
        {step === 3 && recovery?.action === 'RECOVER' && (recovery.state === 'PRESENT_EXACT' || recovery.state === 'PRESENT_UNHEALTHY') ? <button className="secondary-button" type="button" disabled={!canConfigure || reconcile.isPending} onClick={() => void reconcileRun(recovery.sourceRunId, 'DELETE_RECREATE')}>{copy.deleteRecreate}</button> : null}
        {step === 4 || hasRun ? <button className="secondary-button" type="button" onClick={closeWizard}>{copy.close}</button> : null}
      </>}>
      {!canConfigure ? <InlineAlert tone="info" title={i18n.locale === 'ru' ? 'Недостаточно прав для подготовки узла' : 'Missing permissions to provision nodes'}>{i18n.locale === 'ru' ? 'Для подготовки требуются права управления интеграциями, конфигурациями и операциями.' : 'Provisioning requires manage integrations, manage configurations, and execute operations.'}</InlineAlert> : null}
      {unsupported ? <InlineAlert tone="info" title={copy.apiUnavailable}>{options.data?.nodeApi?.blocker ?? copy.unsupported}</InlineAlert> : null}
      {options.isPending ? <p role="status">{copy.loading}</p> : null}
      {options.isError ? <InlineAlert tone="danger" title={i18n.locale === 'ru' ? 'Не удалось загрузить параметры' : 'Could not load onboarding options'} /> : null}
      {step < 4 && options.data ? <>
        <ol className="onboarding-steps" aria-label={copy.title}>{copy.steps.map((label, index) => <li key={label} aria-current={step === index ? 'step' : undefined}>{label}</li>)}</ol>
        {step === 0 ? <section><h3>{copy.server}</h3><p className="muted-copy">{copy.serverHint}</p>
          <label className="field">{copy.server}<select value={resourceId} onChange={event => selectServer(event.target.value)}><option value="">—</option>{options.data.servers.map(value => <option key={value.id} value={value.id}>{value.name} · {value.address} · {value.environmentName}</option>)}</select></label>
          {server ? <dl><dt>{copy.environment}</dt><dd>{server.environmentName}</dd><dt>{copy.ssh}</dt><dd>{server.sshStatus}</dd><dt>{copy.serverProfile}</dt><dd>{server.serverProfileName ?? server.serverProfileStatus}</dd></dl> : null}
          {server && (!server.serverProfileName || server.blockingProblems.length) ? <InlineAlert tone="danger" title={copy.missingProfile}>{server.blockingProblems.map(value => <p key={value}>{value}</p>)}{selectedResource.data ? <Link to={`/organizations/${encodeURIComponent(organizationId)}/environments/${encodeURIComponent(selectedResource.data.environmentId)}/resources/${encodeURIComponent(server.id)}`}>{copy.automation}</Link> : null}</InlineAlert> : null}</section> : null}
        {step === 1 ? <section><h3>Remnawave</h3>
          <label className="field">{copy.name}<input value={nodeName} onChange={event => setNodeName(event.target.value)} /></label>
          <label className="field">{copy.address}<input value={address} onChange={event => setAddress(event.target.value)} /></label>
          <label className="field">{copy.port}<input type="number" min="1" max="65535" value={port} onChange={event => setPort(event.target.value)} /></label>
          <label className="field">{copy.profile}<select value={configProfileId} onChange={event => { setConfigProfileId(event.target.value); setActiveInboundIds([]) }}><option value="">—</option>{options.data.profiles.map(value => <option key={value.id} value={value.id}>{value.name}</option>)}</select></label>
          {profile ? <fieldset><legend>{copy.inbounds}</legend>{profile.inbounds.map(value => <label key={value.id}><input type="checkbox" checked={activeInboundIds.includes(value.id)} onChange={event => setActiveInboundIds(current => event.target.checked ? [...current, value.id] : current.filter(id => id !== value.id))} />{value.name}</label>)}</fieldset> : null}</section> : null}
        {step === 2 ? <section><h3>{copy.steps[2]}</h3><label className="field">{copy.cidrs}<textarea value={cidrs} onChange={event => setCidrs(event.target.value)} /></label><p className="muted-copy">{copy.cidrHint}</p>{!formValid ? <p role="alert">{copy.formError}</p> : null}</section> : null}
        {step === 3 && preview ? <section><h3>{copy.steps[3]}</h3><p>{preview.serverName} · {preview.serverProfileName} · r{preview.revisionNumber}</p><p>{preview.configProfileName}: {preview.inboundNames.join(', ')}</p>
          <p>{preview.run.nodeName} · {preview.run.address}:{preview.run.nodePort}</p>
          {preview.run.externalNodeId && !preview.recovery ? <InlineAlert tone="warning" title={i18n.locale === 'ru' ? 'Будет использован уже созданный узел' : 'The previously created node will be reused'}>
            {i18n.locale === 'ru' ? 'InfraDesk подтвердит исходную идентичность узла перед продолжением. Новый узел создаваться не будет.' : 'InfraDesk will verify the original node identity before continuing. A new node will not be created.'}
            <br /><span>{copy.externalId}: {preview.run.externalNodeId}</span>
          </InlineAlert> : null}
          <p>{preview.nodeImage}</p><p>{copy.cidrs}: {preview.panelCidrs?.join(', ') ?? cidrValues.join(', ')}</p>
          {preview.nodeApi ? <p>Remnawave {preview.nodeApi.serverVersion} · {preview.nodeApi.apiGeneration} · {preview.nodeApi.sourceCommit}</p> : null}
          <ReviewList title={copy.changes} values={preview.changes} /><ReviewList title={copy.warnings} values={preview.warnings.filter(code => code !== 'REMNAWAVE_ONBOARDING_REUSE_EXISTING_NODE')} /><ReviewList title={copy.serverProfile} values={preview.blockingProblems.filter(code => code.startsWith('PROVISIONING_') && !['PROVISIONING_NODE_INSTALLATION_UNMANAGED', 'PROVISIONING_NODE_PORT_OCCUPIED'].includes(code)).map(code => i18n.t.provisioning.errors[code] ?? code)} danger /><ReviewList title={copy.blockers} values={preview.blockingProblems.filter(code => !code.startsWith('REMNAWAVE_LOCAL_INSTALLATION_') && !code.startsWith('PROVISIONING_')).map(code => localDiagnosis(code, i18n.locale) ?? (['REMNAWAVE_ONBOARDING_EXISTING_NODE_REQUIRES_REVIEW', 'REMNAWAVE_ONBOARDING_RECOVERY_CONFLICT'].includes(code)
            ? i18n.locale === 'ru' ? 'На сервере уже создан узел. Его состояние или новые параметры не допускают безопасное продолжение onboarding. Проверьте предыдущий запуск и узел; не создавайте дубликат.' : 'A node was already created on this server. Its state or the new inputs prevent safe onboarding recovery. Review the previous run and node; do not create a duplicate.'
            : i18n.t.provisioning.errors[code] ?? code))} danger />
          </section> : null}
      </> : null}
      {step === 3 && preview?.recovery ? <RecoveryReview recovery={preview.recovery} newCorrelation={preview.run.correlationId} confirmed={recoveryConfirmed} onConfirm={setRecoveryConfirmed} copy={copy} /> : null}
      {(step === 4 || (hasRun && step !== 3)) && current ? <RunStatus run={current} detail={runQuery.data} copy={copy} /> : null}
      {(step === 4 || (hasRun && step !== 3)) && current && ['FAILED', 'UNKNOWN', 'SUCCEEDED'].includes(current.state) && canConfigure ? <div className="integration-row-actions">
        <PendingButton className="secondary-button" type="button" pending={reconcile.isPending} pendingLabel={i18n.t.common.inProgress} disabled={reconcile.isPending || runQuery.isPending} onClick={() => void reconcileRun(current.id, 'RECOVER')}>{copy.checkAgain}</PendingButton>
      </div> : null}
      {(step === 4 || (hasRun && step !== 3)) && !current && !runQuery.isPending ? <InlineAlert tone="danger" title={i18n.locale === 'ru' ? 'Запуск не найден' : 'Run not found'} /> : null}
    </IntegrationDialog> : null}
  </WorkspaceSection>
}

function localStateLabel(state: string, locale: string): string {
  const labels: Record<string, [string, string]> = {
    ABSENT: ['Установка отсутствует; удаление предыдущей установки не требуется.', 'Installation absent; no previous installation needs retirement.'],
    OWNED_COMPLETE: ['Подтверждена установка предыдущего узла.', 'Previous node installation confirmed.'],
    OWNED_PARTIAL: ['Подтверждены части установки предыдущего узла.', 'Previous node installation artifacts confirmed.'],
    OWNED_DAMAGED: ['Подтверждена повреждённая установка предыдущего узла.', 'Damaged previous node installation confirmed.'],
    FOREIGN: ['Владелец установки не подтверждён.', 'Installation ownership is unproven.'],
    PORT_CONFLICT: ['Порт занят другой установкой.', 'Another installation occupies the port.'],
    UNKNOWN: ['Состояние локальной установки неизвестно.', 'Local installation state is unknown.'],
  }
  return labels[state]?.[locale === 'ru' ? 0 : 1] ?? state
}
function localDiagnosis(code: string, locale: string): string | undefined {
  const reasons: Record<string, [string, string]> = {
    SSH_UNAVAILABLE: ['Нет подтверждённого доступа по SSH. Проверьте подключение и учётные данные.', 'SSH access unavailable. Check the connection and credentials.'],
    OBSERVATION_TIMEOUT: ['Проверка превысила время ожидания. Проверьте доступность сервера.', 'Observation timed out. Check server availability.'],
    OUTPUT_TRUNCATED: ['Ответ проверки неполный. Повторите проверку.', 'Observation output incomplete. Check again.'],
    COMPOSE_UNREADABLE: ['Не удалось прочитать файлы установки. Проверьте права доступа.', 'Installation files could not be read. Check access permissions.'],
    OWNER_UNPROVEN: ['Не удалось подтвердить владельца установки. Требуется ручная проверка.', 'Installation ownership could not be proven. Manual review required.'],
    CONTAINER_STATE_UNKNOWN: ['Не удалось проверить контейнеры. Проверьте доступность Docker.', 'Containers could not be inspected. Check Docker availability.'],
    FIREWALL_STATE_UNKNOWN: ['Не удалось проверить правила межсетевого экрана. Проверьте состояние UFW.', 'Firewall rules could not be verified. Check UFW.'],
    PORT_STATE_UNKNOWN: ['Не удалось проверить занятость порта. Проверьте состояние сервера.', 'Port occupancy could not be verified. Check the server.'],
    STATE_UNKNOWN: ['Проверка не подтвердила безопасное состояние. Требуется повторная или ручная проверка.', 'Observation did not prove a safe state. Check again or review manually.'],
  }
  return reasons[code.replace(/^REMNAWAVE_LOCAL_INSTALLATION_/, '')]?.[locale === 'ru' ? 0 : 1]
}
function ReviewList({ title, values, danger = false }: { title: string; values: string[]; danger?: boolean }) {
  return <section><h4>{title}</h4>{values.length ? <ul>{values.map((value, index) => <li key={`${index}-${value}`}>{value}</li>)}</ul> : <p>—</p>}{danger && values.length ? <p role="alert">{title}</p> : null}</section>
}
function RecoveryReview({ recovery, newCorrelation, confirmed, onConfirm, copy }: { recovery: NodeOnboardingRecoverySummary; newCorrelation?: string; confirmed: boolean; onConfirm: (value: boolean) => void; copy: typeof texts[keyof typeof texts] }) {
  const i18n = useI18n()
  const local = recovery.localInstallation
  const localReview = local ? <section><h4>{i18n.locale === 'ru' ? 'Локальная установка' : 'Local installation'}</h4><InlineAlert tone={['FOREIGN', 'PORT_CONFLICT', 'UNKNOWN'].includes(local.state) ? 'warning' : 'info'} title={localStateLabel(local.state, i18n.locale)}>
    {local.diagnosis ? localDiagnosis(local.diagnosis, i18n.locale) : null}
    {['FOREIGN', 'PORT_CONFLICT'].includes(local.state) ? <span>{i18n.locale === 'ru' ? 'Требуется ручная проверка владельца установки и занятого порта. Автоматические изменения заблокированы.' : 'Review installation ownership and the occupied port manually. Automatic changes are blocked.'}</span> : null}
    {local.state === 'UNKNOWN' ? <span>{i18n.locale === 'ru' ? 'Восстановите доступ и повторите проверку локальной установки. Изменения заблокированы.' : 'Restore access and check the local installation again. Changes are blocked.'}</span> : null}
    {local.state.startsWith('OWNED_') && recovery.action !== 'RECOVER' ? <span>{i18n.locale === 'ru' ? 'После подтверждения InfraDesk удалит только доказанную установку и правила предыдущего узла, затем создаст новый узел.' : 'After confirmation, InfraDesk will retire the proven previous installation and its rules, then create a new node.'}</span> : null}
  </InlineAlert></section> : null
  if (recovery.state === 'UNKNOWN') return <section><h4>Remnawave Panel</h4><InlineAlert tone="warning" title={copy.recoveryUnknown} />{localReview}</section>
  if (recovery.state === 'PRESENT_CONFLICT') return <section><h4>Remnawave Panel</h4><InlineAlert tone="danger" title={copy.conflict} />{localReview}</section>
  if (recovery.state === 'CONFIRMED_NOT_FOUND') return <section><h4>Remnawave Panel</h4><InlineAlert tone="warning" title={copy.missingNode} />{localReview}
    <dl><dt>{copy.oldIdentity}</dt><dd>{recovery.previousExternalNodeId ?? '—'}</dd><dt>{copy.oldCorrelation}</dt><dd>{recovery.previousCorrelationId}</dd><dt>{copy.newCorrelation}</dt><dd>{newCorrelation ?? '—'}</dd></dl>
    {!local || !['FOREIGN', 'PORT_CONFLICT', 'UNKNOWN'].includes(local.state) ? <label><input type="checkbox" checked={confirmed} onChange={event => onConfirm(event.target.checked)} />{copy.recreateApproval}</label> : null}</section>
  if (recovery.action === 'DELETE_RECREATE') return <section><h4>Remnawave Panel</h4><InlineAlert tone="warning" title={copy.deleteRecreate} />{localReview}
    <dl><dt>{copy.oldIdentity}</dt><dd>{recovery.previousExternalNodeId ?? '—'}</dd><dt>{copy.oldCorrelation}</dt><dd>{recovery.previousCorrelationId}</dd><dt>{copy.newCorrelation}</dt><dd>{newCorrelation ?? '—'}</dd></dl>
    {!local || !['FOREIGN', 'PORT_CONFLICT', 'UNKNOWN'].includes(local.state) ? <label><input type="checkbox" checked={confirmed} onChange={event => onConfirm(event.target.checked)} />{copy.deleteApproval}</label> : null}</section>
  return <section><h4>Remnawave Panel</h4><InlineAlert tone="info" title={copy.restore} />{localReview}<dl><dt>{copy.oldIdentity}</dt><dd>{recovery.previousExternalNodeId ?? '—'}</dd></dl></section>
}
function RunStatus({ run, detail, copy }: { run: NodeOnboardingRun; detail: ReturnType<typeof useNodeOnboardingRun>['data']; copy: typeof texts[keyof typeof texts] }) {
  const i18n = useI18n()
  const failureReason = (code: string) => ['FIREWALL_RULE_UNSUPPORTED', 'PROVISIONING_FIREWALL_RULE_UNSUPPORTED'].includes(code)
    ? i18n.locale === 'ru' ? 'Не удалось безопасно обработать текущую конфигурацию UFW.' : 'The current UFW configuration could not be safely processed.'
    : code === 'PROVISIONING_FIREWALL_OWNERSHIP_CONFLICT'
      ? i18n.locale === 'ru' ? 'Правило UFW этого узла отличается от проверенного плана. Проверьте правило перед повторной подготовкой.' : 'This node’s UFW rule differs from the reviewed plan. Review the rule before preparing another plan.'
      : i18n.t.provisioning.errors[code] ?? i18n.t.common.operationBlocked
  const failure = (code: string) => <><span>{failureReason(code)}</span>{/^[A-Z0-9_]{1,96}$/.test(code) ? <><br /><span className="technical-value">{i18n.locale === 'ru' ? 'Код' : 'Code'}: {code}</span></> : null}</>
  const tone = run.state === 'SUCCEEDED' ? 'success' : run.state === 'FAILED' ? 'danger' : run.state === 'UNKNOWN' ? 'warning' : 'info'
  const message = run.state === 'SUCCEEDED' ? copy.success : run.state === 'FAILED' ? copy.failed : run.state === 'UNKNOWN' ? copy.unknown : copy.active
  const byPhase = new Map(detail?.phases.map(phase => [phase.phase, phase]))
  const hasFirewallRetirement = detail?.phases.some(phase => phase.phase === 'RETIRE_NODE_FIREWALL') || run.phase === 'RETIRE_NODE_FIREWALL'
  const retirementPhases: OnboardingRunPhase[] = run.recovery?.localInstallation?.state === 'ABSENT' ? [] : [...(hasFirewallRetirement ? ['RETIRE_NODE_FIREWALL' as const] : []), 'RETIRE_LOCAL_NODE']
  const runPhases: OnboardingRunPhase[] = run.recovery?.action === 'DELETE_RECREATE'
    ? [...phases.slice(0, 2), 'DELETE_NODE', 'CONFIRM_NODE_DELETED', ...retirementPhases, ...phases.slice(2)]
    : run.recovery?.action === 'RECREATE' ? [...phases.slice(0, 2), ...retirementPhases, ...phases.slice(2)] : [...phases]
  return <section><InlineAlert tone={tone} title={`${copy.run}: ${run.state}`}>{message}{run.failureCode ? <><br />{failure(run.failureCode)}</> : null}</InlineAlert>
    <p>{run.nodeName} · {run.address}:{run.nodePort}</p><h3>{copy.phases}</h3><ol>{runPhases.map(phase => {
      const record = byPhase.get(phase)
      const code = record?.failureCode ?? (run.phase === phase ? run.failureCode : null)
      const name = copy.phaseNames[phase]
      return <li key={phase}>{code ? <InlineAlert tone={record?.state === 'UNKNOWN' || run.state === 'UNKNOWN' ? 'warning' : 'danger'} title={`${name} — ${record?.state ?? run.state}`}>{failure(code)}</InlineAlert>
        : <><span>{name}</span> — {record?.state ?? 'PENDING'}</>}</li>
    })}</ol>
    {(run.externalNodeId || run.baselineRunId || run.syncSessionId) ? <><h3>{copy.partial}</h3><dl>
      {run.externalNodeId ? <><dt>{copy.externalId}</dt><dd>{run.externalNodeId}</dd></> : null}
      {run.baselineRunId ? <><dt>{copy.baseline}</dt><dd>{run.baselineRunId}</dd></> : null}
      {run.syncSessionId ? <><dt>{copy.syncId}</dt><dd>{run.syncSessionId}</dd></> : null}</dl></> : null}
  </section>
}
