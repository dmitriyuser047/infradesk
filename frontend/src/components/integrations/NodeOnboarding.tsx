import { PackageProbeFindings } from '../resources/PackageProbeFindings'
import { useEffect, useMemo, useRef, useState } from 'react'
import { createRequestId } from '../../app/requestId'
import { readPendingSubmission, storePendingSubmission, type PendingSubmission } from '../../app/pendingSubmission'
import { ApiError } from '../../api/httpClient'
import { Link, useSearchParams } from 'react-router-dom'
import { useNodeOnboardingHistory, useNodeOnboardingOptions, useNodeOnboardingRun, usePreviewNodeOnboarding, useReconcileNodeOnboarding, useStartNodeOnboarding } from '../../api/nodeOnboarding'
import { useResource } from '../../api/resources'
import { useOrganizationPermissions } from '../auth/authorization'
import { useI18n } from '../../i18n'
import { InlineAlert, PendingButton, StatusIndicator, WorkspaceSection } from '../layout/WorkspacePrimitives'
import { IntegrationDialog } from './IntegrationDialog'
import { PanelNetworkAccess } from './PanelNetworkAccess'
import { NodeCertificateImport } from './NodeCertificateImport'
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
    requestError: 'The request could not be confirmed. Retry with the same request ID to safely recover the result.', formError: 'Check the node name, address, port, profile and inbounds. Manual network settings require valid outbound Panel sources.',
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
    requestError: 'Не удалось подтвердить запрос. Повторите отправку с тем же ID запроса, чтобы безопасно получить результат.', formError: 'Проверьте имя, адрес, порт, профиль и inbounds. Для ручной настройки сети нужны корректные исходящие адреса Panel.',
    checkAgain: 'Проверить снова', restore: 'Восстановить существующий узел', recreate: 'Пересоздать узел', deleteRecreate: 'Удалить и пересоздать',
    missingNode: 'Узел больше не найден в Remnawave.', conflict: 'Узел Remnawave конфликтует с исходной идентичностью установки. Действие недоступно.',
    recoveryUnknown: 'Удалённое состояние всё ещё неизвестно. Проверьте снова, чтобы повторить только чтение состояния.',
    oldIdentity: 'Предыдущий UUID узла', oldCorrelation: 'Предыдущий ID корреляции', newCorrelation: 'Новый ID корреляции',
    recreateApproval: 'Я проверил предыдущий UUID узла и подтверждаю создание нового узла Remnawave с новым ID корреляции.',
    deleteApproval: 'Я подтверждаю удаление существующего узла Remnawave и создание замены с новым ID корреляции.',
    apiUnavailable: 'API Remnawave не подтвердил поддержку подготовки узлов.', phaseNames: { VALIDATE: 'Проверка запроса', PREPARE_SERVER: 'Подготовка сервера', DELETE_NODE: 'Удаление существующего узла Remnawave', CONFIRM_NODE_DELETED: 'Подтверждение удаления узла', RETIRE_NODE_FIREWALL: 'Удаление правила межсетевого экрана предыдущего узла', RETIRE_LOCAL_NODE: 'Удаление предыдущей локальной установки узла', CREATE_NODE: 'Создание узла Remnawave', GET_INSTALLATION_DATA: 'Получение данных установки', CONFIGURE_NODE_FIREWALL: 'Настройка межсетевого экрана', INSTALL_NODE: 'Установка узла', START_NODE: 'Запуск узла', VERIFY_LOCAL_NODE: 'Проверка узла на сервере', WAIT_FOR_PANEL: 'Ожидание панели Remnawave', SYNC_INVENTORY: 'Синхронизация инвентаря', BIND_RESOURCE: 'Привязка сервера', SET_DESIRED_STATE: 'Установка желаемого состояния', FINAL_VERIFY: 'Итоговая проверка' } },
} as const

type Step = 0 | 1 | 2 | 3 | 4
type OnboardingRunPhase = typeof phases[number] | 'DELETE_NODE' | 'CONFIRM_NODE_DELETED' | 'RETIRE_NODE_FIREWALL' | 'RETIRE_LOCAL_NODE' | 'CONFIRM_PREVIOUS_NODE_ABSENT' | 'UNBIND_PREVIOUS_NODE' | 'RETIRE_PREVIOUS_CLIENT_FIREWALL' | 'VERIFY_PREVIOUS_RETIRED' | 'RESOLVE_PANEL_SOURCE' | 'ADD_PANEL_SOURCES' | 'FINALIZE_PANEL_SOURCES'
const replacementLabel = (locale: string) => locale === 'ru' ? 'Пересоздать с новыми параметрами' : 'Recreate with new settings'
const requiresRecreateApproval = (action?: NodeOnboardingRecoveryAction) =>
  action === 'RECREATE' || action === 'DELETE_RECREATE' || action === 'RECREATE_WITH_NEW_CONFIG'
const serverProblem = (code: string, locale: string) => ({
  REMNAWAVE_ONBOARDING_PROFILE_REQUIRED: locale === 'ru' ? 'Назначьте серверный профиль.' : 'Assign a server profile.',
  REMNAWAVE_ONBOARDING_BINDING_CONFLICT: locale === 'ru' ? 'Сервер уже связан с другим узлом Remnawave.' : 'This server is already bound to another Remnawave node.',
  REMNAWAVE_ONBOARDING_EXISTING_NODE_REQUIRES_REVIEW: locale === 'ru' ? 'Обнаружено состояние существующего узла, которое нельзя безопасно классифицировать автоматически.' : 'The existing node state cannot be safely classified automatically.',
}[code] ?? code)
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
  const [expandedHistory, setExpandedHistory] = useState(false)
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
  const [addressMode, setAddressMode] = useState<'PUBLIC_IP' | 'DOMAIN'>('PUBLIC_IP')
  const [configProfileId, setConfigProfileId] = useState(''); const [activeInboundIds, setActiveInboundIds] = useState<string[]>([])
  const [protocolMode, setProtocolMode] = useState<'SHADOWSOCKS' | 'HYSTERIA2' | 'EXISTING'>('SHADOWSOCKS')
  const [clientPort, setClientPort] = useState('443'); const [tlsDomain, setTlsDomain] = useState('')
  const [tlsCertificateId, setTlsCertificateId] = useState<string | undefined>()
  const [tlsMode, setTlsMode] = useState<'HTTP01' | 'IMPORT'>('HTTP01')
  const [tlsEmail, setTlsEmail] = useState(''); const [tlsTerms, setTlsTerms] = useState(false)
  const [httpCertificateId, setHttpCertificateId] = useState(createRequestId)
  const tlsReady = tlsMode === 'IMPORT' ? Boolean(tlsCertificateId) : tlsTerms && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(tlsEmail)
  const generatedAvailable = options.data?.nodeApi?.capabilities.includes('PROTOCOL_PROFILE_CREATE') ?? false
  const protocol = generatedAvailable ? protocolMode : 'EXISTING'
  const [cidrs, setCidrs] = useState(''); const [reviewed, setReviewed] = useState<{plan:NodeOnboardingPreview;requestId:string}|null>(null)
  const [sourceMode, setSourceMode] = useState<'AUTO' | 'MANUAL'>('AUTO')
  const preview = reviewed?.plan
  const previewCode = previewRequest.error instanceof ApiError ? previewRequest.error.code : undefined
  const addressError = previewCode === 'REMNAWAVE_NODE_ADDRESS_DNS_UNCONFIRMED'
    ? i18n.locale === 'ru' ? 'DNS домена не подтверждён для выбранного сервера. Укажите его домен или выберите автоматический публичный IP.' : 'The domain DNS does not match the selected server. Enter its domain or choose the automatic public IP.'
    : previewCode === 'REMNAWAVE_NODE_PUBLIC_IP_UNCONFIRMED' || previewCode === 'REMNAWAVE_NODE_PUBLIC_IP_AMBIGUOUS'
    ? i18n.locale === 'ru' ? 'Не удалось однозначно подтвердить публичный IP сервера. Проверьте адреса сервера или явно выберите проверенный домен.' : 'A unique public server IP could not be confirmed. Review the server addresses or explicitly choose a verified domain.'
    : undefined
  const [workflowError,setWorkflowError] = useState(false)
  const [recoveryConfirmed, setRecoveryConfirmed] = useState(false)
  const submitting=useRef(false)
  const selectedResource = useResource(organizationId, resourceId || undefined)
  const server = options.data?.servers.find(value => value.id === resourceId)
  const profile = options.data?.profiles.find(value => value.id === configProfileId)
  const protocolValid = protocol === 'EXISTING' ? Boolean(profile && activeInboundIds.length > 0) :
    Number.isInteger(Number(clientPort)) && Number(clientPort) > 0 && Number(clientPort) <= 65535 && Number(clientPort) !== Number(port) &&
    (protocol !== 'HYSTERIA2' || /^[a-z0-9][a-z0-9.-]*\.[a-z0-9.-]+$/.test(tlsDomain))
  const cidrValues = useMemo(() => cidrs.split(/[\s,]+/).filter(Boolean), [cidrs])
  const run = runQuery.data?.run
  const hasRun = Boolean(runId)
  const setRunInUrl = (id: string | null) => setParams(previous => { const next = new URLSearchParams(previous); if (id) next.set('onboardingRun', id); else next.delete('onboardingRun'); return next }, { replace: true })
  const closeWizard = () => { setOpen(false); setStep(0); setReviewed(null); setRunInUrl(null); setHttpCertificateId(createRequestId()) }
  const selectServer = (id: string) => { setResourceId(id); setTlsCertificateId(undefined); setHttpCertificateId(createRequestId()); const value = options.data?.servers.find(item => item.id === id); if (value) { setAddress(value.address); if (!nodeName) setNodeName(value.name) } }
  const exactBody = (): NodeOnboardingPreviewRequest => ({ ...(preview?.recovery && preview.input ? preview.input : {
    resourceId, nodeName: nodeName.trim(), address: addressMode === 'PUBLIC_IP' ? '' : address.trim(), nodeAddressMode: addressMode, nodePort: Number(port),
    ...(protocol === 'EXISTING' ? { configProfileId, activeInboundIds } : {
      protocol: protocol === 'HYSTERIA2' ? { version: 1 as const, kind: 'HYSTERIA2' as const, port: Number(clientPort), serverName: tlsDomain } :
        { version: 1 as const, kind: 'SHADOWSOCKS' as const, port: Number(clientPort), method: 'chacha20-ietf-poly1305' as const },
      ...(protocol === 'HYSTERIA2' ? tlsMode === 'IMPORT' ? { tlsCertificateId } :
        { tlsHttp01: { certificateId: httpCertificateId, email: tlsEmail, agreeTerms: true as const } } : {}),
    }), desiredState: 'ENABLED' as const,
  }), panelSourceMode: sourceMode, ...(sourceMode === 'MANUAL' ? { panelCidrs: cidrValues } : {}) })
  const formValid = Boolean(((preview?.recovery && preview.input) || (server && nodeName.trim().length >= 3 && nodeName.trim().length <= 30 && !/[\x00-\x1f\x7f]/.test(nodeName) && (addressMode === 'PUBLIC_IP' || address.trim()) && Number.isInteger(Number(port)) && Number(port) >= 1 && Number(port) <= 65535 && protocolValid && (protocol !== 'HYSTERIA2' || tlsReady))) && (sourceMode === 'AUTO' || (cidrValues.length > 0 && cidrValues.length <= 32 && new Set(cidrValues).size === cidrValues.length && cidrValues.every(validCidr))))
  const makePreview = async () => { setWorkflowError(false); try { const value = await previewRequest.mutateAsync(exactBody()); start.reset(); setReviewed({plan:value,requestId:createRequestId()}); setStep(3) } catch { setWorkflowError(true) } }
  const reconcileRun = async (sourceRunId: string, action: NodeOnboardingRecoveryAction, manualFallback = false) => {
    if (!canConfigure || activeRun(run?.state ?? 'PLANNED') || reconcile.isPending) return
    setWorkflowError(false); setRecoveryConfirmed(false)
    try {
      const value = await reconcile.mutateAsync({ runId: sourceRunId, action })
      start.reset(); setReviewed({ plan: value, requestId: createRequestId() }); setStep(manualFallback ? 2 : 3); setOpen(true)
      if (manualFallback) { setSourceMode('MANUAL'); setCidrs('') }
    } catch { setWorkflowError(true) }
  }
  const recovery = preview?.recovery
  const needsRecreateApproval = requiresRecreateApproval(recovery?.action)
  const recoveryBlocked = recovery?.state === 'UNKNOWN' || recovery?.state === 'PRESENT_CONFLICT'
  const apply = async (identity:PendingSubmission, retryConfirmed = false) => {
    const requestRecoveryAction = preview?.recovery?.action ?? (identity.planId === unresolved?.planId ? runQuery.data?.run.recovery?.action : undefined)
    const requestNeedsRecreateApproval = requiresRecreateApproval(requestRecoveryAction)
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
    <p className="muted-copy">{i18n.t.integrationInventory.onboardingHint}</p>
    {history.data?.items.length ? <div className="onboarding-run-history" aria-label={copy.history}>
      {history.data.items.filter((item, index) => expandedHistory || index < 3 || item.state === 'QUEUED' || item.state === 'RUNNING').map(item => <button key={item.id}
        className="onboarding-run-row" type="button" onClick={() => openExisting(item.id)}>
        <span className="onboarding-run-target"><strong>{item.nodeName}</strong><small>{item.address}:{item.nodePort}</small></span>
        <time dateTime={item.createdAt}>{i18n.format.dateTime(item.createdAt)}</time>
        <StatusIndicator label={i18n.t.integrationInventory.runStates[item.state]}
          tone={item.state === 'SUCCEEDED' ? 'success' : item.state === 'FAILED' ? 'danger' : item.state === 'UNKNOWN' ? 'warning' : 'info'} />
      </button>)}
      {history.data.items.length > 3 ? <button className="onboarding-history-toggle" type="button" aria-expanded={expandedHistory}
        onClick={() => setExpandedHistory(value => !value)}>
        {expandedHistory ? i18n.t.integrationInventory.collapseRuns : `${i18n.t.integrationInventory.allRuns} (${history.data.items.length})`}
      </button> : null}
    </div> : null}
    {hasRun && runQuery.isPending ? <p role="status">{copy.loading}</p> : null}
    {hasRun && runQuery.isError ? <InlineAlert tone="danger" title={i18n.locale === 'ru' ? 'Не удалось загрузить запуск' : 'Could not load onboarding run'} /> : null}
    {unresolved && !open ? <InlineAlert tone="warning" title={i18n.t.common.unresolvedSubmission} action={<PendingButton pending={start.isPending} pendingLabel={i18n.t.common.inProgress} disabled={!canConfigure || !runQuery.isSuccess} onClick={()=>void apply(unresolved, requiresRecreateApproval(runQuery.data?.run.recovery?.action))}>{i18n.t.common.recoverSubmission}</PendingButton>} /> : null}
    {workflowError && !previewRequest.isError ? <InlineAlert tone="danger" title={i18n.t.common.operationBlocked} /> : null}
    {!open && start.isError ? <InlineAlert tone="danger" title={copy.requestError} /> : null}
    {open || hasRun ? <IntegrationDialog title={copy.title} size="large" onClose={closeWizard} busy={start.isPending || previewRequest.isPending || reconcile.isPending}
      actionNote={step === 3 && preview?.blockingProblems.length ? i18n.t.common.blockedAction(preview.blockingProblems.length) : undefined}
      actionFeedback={previewRequest.isError || start.isError || workflowError ? <InlineAlert tone="danger" title={start.isError ? copy.requestError : addressError ?? i18n.t.common.operationBlocked} /> : undefined}
      actions={<>
        {step > 0 && step < 4 && !preview?.recovery ? <button className="secondary-button" type="button" disabled={!!unresolved || start.isPending || previewRequest.isPending} onClick={() => { setReviewed(null); setStep((step - 1) as Step) }}>{copy.back}</button> : null}
        {step < 3 ? <button className="primary-button" type="button" disabled={!canConfigure || unsupported || options.isPending || previewRequest.isPending || (step === 0 && (!resourceId || !server?.serverProfileName || server.blockingProblems.length > 0)) || (step === 1 && (!nodeName.trim() || (addressMode === 'DOMAIN' && !address.trim()) || !Number(port) || !protocolValid)) || (step === 2 && !formValid)} onClick={() => step === 2 && preview?.recovery ? void makePreview() : setStep((step + 1) as Step)}>{copy.next}</button> : null}
        {step === 3 && !preview?.recovery ? <PendingButton className={preview ? 'secondary-button' : 'primary-button'} type="button" pending={previewRequest.isPending} pendingLabel={i18n.t.common.inProgress} disabled={!!unresolved || !canConfigure || !formValid} onClick={() => void makePreview().catch(() => setWorkflowError(true))}>{copy.preview}</PendingButton> : null}
        {step === 3 && reviewed?.plan.recovery ? <PendingButton className="secondary-button" type="button" pending={reconcile.isPending} pendingLabel={i18n.t.common.inProgress} disabled={!canConfigure} onClick={() => reviewed.plan.recovery!.action === 'RECREATE_WITH_NEW_CONFIG' ? void makePreview() : void reconcileRun(reviewed.plan.recovery!.sourceRunId, reviewed.plan.recovery!.action === 'DELETE_RECREATE' ? 'DELETE_RECREATE' : 'RECOVER')}>{i18n.locale === 'ru' ? 'Проверить локальную установку' : 'Check local installation'}</PendingButton> : null}
        {step === 3 && reviewed && !recoveryBlocked ? <PendingButton className="primary-button" type="button" pending={start.isPending} pendingLabel={i18n.t.common.inProgress} disabled={!canConfigure || reviewed.plan.blockingProblems.length > 0 || (needsRecreateApproval && !recoveryConfirmed)} onClick={() => void apply({planId:reviewed.plan.run.id,requestId:reviewed.requestId})}>{recovery?.action === 'RECREATE_WITH_NEW_CONFIG' ? replacementLabel(i18n.locale) : recovery?.action === 'REPAIR_PANEL_CONNECTIVITY' ? i18n.locale === 'ru' ? 'Исправить доступ' : 'Repair Panel access' : recovery?.action === 'RECOVER' ? copy.restore : recovery?.action === 'RECREATE' ? copy.recreate : recovery?.action === 'DELETE_RECREATE' ? copy.deleteRecreate : copy.apply}</PendingButton> : null}
        {step === 3 && recovery?.action === 'RECOVER' && (recovery.state === 'PRESENT_EXACT' || recovery.state === 'PRESENT_UNHEALTHY') ? <button className="secondary-button" type="button" disabled={!canConfigure || reconcile.isPending} onClick={() => void reconcileRun(recovery.sourceRunId, 'DELETE_RECREATE')}>{copy.deleteRecreate}</button> : null}
        {step === 4 || hasRun ? <button className="secondary-button" type="button" onClick={closeWizard}>{copy.close}</button> : null}
      </>}>
      {!canConfigure ? <InlineAlert tone="info" title={i18n.locale === 'ru' ? 'Недостаточно прав для подготовки узла' : 'Missing permissions to provision nodes'}>{i18n.locale === 'ru' ? 'Для подготовки требуются права управления интеграциями, конфигурациями и операциями.' : 'Provisioning requires manage integrations, manage configurations, and execute operations.'}</InlineAlert> : null}
      {unsupported ? <InlineAlert tone="info" title={copy.apiUnavailable}>{options.data?.nodeApi?.blocker ?? copy.unsupported}</InlineAlert> : null}
      {options.isPending ? <p role="status">{copy.loading}</p> : null}
      {options.isError ? <InlineAlert tone="danger" title={i18n.locale === 'ru' ? 'Не удалось загрузить параметры' : 'Could not load onboarding options'} /> : null}
      {step < 4 && options.data ? <>
        <ol className="remnawave-onboarding-steps" aria-label={copy.title}>{copy.steps.map((label, index) => <li key={label} aria-current={step === index ? 'step' : undefined}>{label}</li>)}</ol>
        {step === 0 ? <section><h3>{copy.server}</h3><p className="muted-copy">{copy.serverHint}</p>
          <label className="field">{copy.server}<select value={resourceId} onChange={event => selectServer(event.target.value)}><option value="">—</option>{options.data.servers.map(value => <option key={value.id} value={value.id}>{value.name} · {value.address} · {value.environmentName}</option>)}</select></label>
          {server ? <dl><dt>{copy.environment}</dt><dd>{server.environmentName}</dd><dt>{copy.ssh}</dt><dd>{server.sshStatus}</dd><dt>{copy.serverProfile}</dt><dd>{server.serverProfileName ?? server.serverProfileStatus}</dd></dl> : null}
          {server?.previousBindingReview ? <InlineAlert tone="warning" title={i18n.locale === 'ru' ? 'Предыдущая привязка' : 'Previous binding'}>{i18n.locale === 'ru' ? 'Сервер связан с предыдущим неактивным узлом. InfraDesk проверит отсутствие узла в Panel и владение установкой. Привязка будет снята только при подтверждённом пересоздании.' : 'This server is bound to a previous inactive node. InfraDesk will verify Panel absence and local ownership. The binding will be removed only after confirmed recreation.'}</InlineAlert> : null}
          {server && (!server.serverProfileName || server.blockingProblems.length) ? <InlineAlert tone="danger" title={!server.serverProfileName ? copy.missingProfile : copy.blockers}>{server.blockingProblems.map(value => <p key={value}>{serverProblem(value, i18n.locale)}</p>)}{selectedResource.data ? <Link to={`/organizations/${encodeURIComponent(organizationId)}/environments/${encodeURIComponent(selectedResource.data.environmentId)}/resources/${encodeURIComponent(server.id)}`}>{copy.automation}</Link> : null}</InlineAlert> : null}</section> : null}
        {step === 1 ? <section><h3>{generatedAvailable ? i18n.locale === 'ru' ? 'Выберите протокол' : 'Choose a protocol' : 'Remnawave'}</h3>
          {generatedAvailable ? <fieldset className="onboarding-protocol-options"><legend>{i18n.locale === 'ru' ? 'Протокол подключения' : 'Connection protocol'}</legend>
            {(['SHADOWSOCKS', 'HYSTERIA2', 'EXISTING'] as const).map(mode => <label key={mode}>
              <input type="radio" name="protocol" checked={protocol === mode} onChange={() => { setProtocolMode(mode); setReviewed(null) }} />
              <strong>{mode === 'EXISTING' ? i18n.locale === 'ru' ? 'Существующий профиль' : 'Existing profile' : mode === 'HYSTERIA2' ? 'Hysteria2' : 'Shadowsocks'}</strong>
              <span>{mode === 'SHADOWSOCKS' ? i18n.locale === 'ru' ? 'TCP и UDP, сертификат не нужен' : 'TCP and UDP, no certificate required' :
                mode === 'HYSTERIA2' ? i18n.locale === 'ru' ? 'QUIC / UDP, требуется домен и TLS' : 'QUIC / UDP, requires a domain and TLS' :
                i18n.locale === 'ru' ? 'Расширенная настройка через Panel' : 'Advanced Panel configuration'}</span>
            </label>)}
          </fieldset> : null}
          {protocol !== 'EXISTING' ? <>
            <label className="field">{i18n.locale === 'ru' ? 'Порт для клиентов' : 'Client port'}<input type="number" min="1" max="65535" value={clientPort} onChange={event => setClientPort(event.target.value)} /></label>
            <p className="muted-copy">{i18n.locale === 'ru' ? 'InfraDesk создаст отдельный профиль для этого сервера и настроит доступ на выбранный порт.' : 'InfraDesk will create a separate profile for this server and configure access to this port.'}</p>
            {protocol === 'HYSTERIA2' ? <label className="field">{i18n.locale === 'ru' ? 'Домен TLS (SNI)' : 'TLS domain (SNI)'}<input value={tlsDomain} placeholder="vpn.example.com" autoComplete="off" onChange={event => { setTlsDomain(event.target.value.trim().toLowerCase()); setTlsCertificateId(undefined); setHttpCertificateId(createRequestId()) }} /></label> : null}
          </> : null}
          <label className="field">{copy.name}<input value={nodeName} onChange={event => setNodeName(event.target.value)} /></label>
          <fieldset><legend>{copy.address}</legend>
            <label><input type="radio" name="nodeAddressMode" checked={addressMode === 'PUBLIC_IP'} onChange={() => setAddressMode('PUBLIC_IP')} />{i18n.locale === 'ru' ? 'Публичный IP сервера (автоматически)' : 'Server public IP (automatic)'}</label>
            <label><input type="radio" name="nodeAddressMode" checked={addressMode === 'DOMAIN'} onChange={() => { setAddressMode('DOMAIN'); setAddress('') }} />{i18n.locale === 'ru' ? 'Домен с проверкой DNS' : 'Domain with DNS verification'}</label>
            {addressMode === 'DOMAIN' ? <label className="field">{copy.address}<input value={address} onChange={event => setAddress(event.target.value)} /></label> : <p>{i18n.locale === 'ru' ? 'Адрес будет подтверждён на выбранном сервере. DNS не требуется.' : 'The address will be confirmed on the selected server. DNS is not required.'}</p>}
          </fieldset>
          <label className="field">{copy.port}<input type="number" min="1" max="65535" value={port} onChange={event => setPort(event.target.value)} /></label>
          {protocol === 'EXISTING' ? <label className="field">{copy.profile}<select value={configProfileId} onChange={event => { setConfigProfileId(event.target.value); setActiveInboundIds([]) }}><option value="">—</option>{options.data.profiles.map(value => <option key={value.id} value={value.id}>{value.name}</option>)}</select></label> : null}
          {protocol === 'EXISTING' && profile ? <fieldset><legend>{copy.inbounds}</legend>{profile.inbounds.map(value => <label key={value.id}><input type="checkbox" checked={activeInboundIds.includes(value.id)} onChange={event => setActiveInboundIds(current => event.target.checked ? [...current, value.id] : current.filter(id => id !== value.id))} />{value.name}</label>)}</fieldset> : null}</section> : null}
        {step === 2 ? <section><h3>{copy.steps[2]}</h3><p>{i18n.locale === 'ru' ? 'Remnawave Panel → Node: сетевой доступ настраивается автоматически.' : 'Remnawave Panel → Node: network access is configured automatically.'}</p>
          {protocol === 'HYSTERIA2' && !preview?.recovery ? <fieldset><legend>{i18n.locale === 'ru' ? 'TLS для Hysteria2' : 'TLS for Hysteria2'}</legend>
            <label><input type="radio" name="tlsMode" checked={tlsMode === 'HTTP01'} onChange={() => setTlsMode('HTTP01')} />{i18n.locale === 'ru' ? 'Выпустить автоматически (HTTP-01)' : 'Issue automatically (HTTP-01)'}</label>
            <label><input type="radio" name="tlsMode" checked={tlsMode === 'IMPORT'} onChange={() => setTlsMode('IMPORT')} />{i18n.locale === 'ru' ? 'Импортировать сертификат' : 'Import certificate'}</label>
            {tlsMode === 'IMPORT' ? <NodeCertificateImport key={`${resourceId}:${tlsDomain}`} organizationId={organizationId} integrationId={integrationId}
              resourceId={resourceId} domain={tlsDomain} onImported={setTlsCertificateId} /> : <>
              <p>{i18n.locale === 'ru' ? 'Домен должен указывать напрямую на этот сервер. InfraDesk временно откроет TCP/80 для проверки Let’s Encrypt и удалит временный доступ после выпуска. Если порт занят или домен за прокси, используйте импорт.' : 'The domain must point directly to this server. InfraDesk temporarily opens TCP/80 for Let’s Encrypt validation and removes the temporary access afterwards. Use import if the port is occupied or the domain is proxied.'}</p>
              <label className="field">Email<input type="email" value={tlsEmail} onChange={event => setTlsEmail(event.target.value)} /></label>
              <label><input type="checkbox" checked={tlsTerms} onChange={event => setTlsTerms(event.target.checked)} />{i18n.locale === 'ru' ? 'Принимаю условия Let’s Encrypt и разрешаю временную проверку на TCP/80.' : 'I agree to the Let’s Encrypt terms and temporary TCP/80 validation.'} <a href="https://letsencrypt.org/repository/" target="_blank" rel="noreferrer">{i18n.locale === 'ru' ? 'Условия' : 'Terms'}</a></label>
            </>}
          </fieldset> : null}
          <details open={sourceMode === 'MANUAL' || undefined}><summary>{i18n.locale === 'ru' ? 'Расширенные настройки сети' : 'Advanced network settings'}</summary>
            <label><input type="radio" name="panelSourceMode" checked={sourceMode === 'AUTO'} onChange={() => setSourceMode('AUTO')} />{i18n.locale === 'ru' ? 'Автоматически' : 'Automatic'}</label>
            <label><input type="radio" name="panelSourceMode" checked={sourceMode === 'MANUAL'} onChange={() => setSourceMode('MANUAL')} />{i18n.locale === 'ru' ? 'Указать вручную' : 'Manual'}</label>
            {sourceMode === 'MANUAL' ? <><label className="field">{i18n.locale === 'ru' ? 'Исходящие адреса Remnawave Panel' : 'Remnawave Panel outbound addresses'}<textarea value={cidrs} onChange={event => setCidrs(event.target.value)} /></label><p>{i18n.locale === 'ru'
              ? 'Это IP/CIDR сервера, с которого Panel подключается к Node, а не адрес вашего компьютера или SSH-клиента. Ручной режим нужен для NAT, reverse proxy или отдельного исходящего адреса.'
              : 'These are IP/CIDR sources used by Panel to connect to Node, not your computer or SSH client. Use manual mode for NAT, reverse proxies or a separate outbound address.'}</p></> : null}
          </details>{!formValid ? <p role="alert">{copy.formError}</p> : null}</section> : null}
        {step === 3 && preview ? <section><h3>{copy.steps[3]}</h3><p>{preview.serverName} · {preview.serverProfileName} · r{preview.revisionNumber}</p><p>{preview.configProfileName}: {preview.inboundNames.join(', ')}</p>
          <p>{preview.run.nodeName} · {preview.run.address}:{preview.run.nodePort}</p>
          {preview.run.externalNodeId && !preview.recovery ? <InlineAlert tone="warning" title={i18n.locale === 'ru' ? 'Будет использован уже созданный узел' : 'The previously created node will be reused'}>
            {i18n.locale === 'ru' ? 'InfraDesk подтвердит исходную идентичность узла перед продолжением. Новый узел создаваться не будет.' : 'InfraDesk will verify the original node identity before continuing. A new node will not be created.'}
            <br /><span>{copy.externalId}: {preview.run.externalNodeId}</span>
          </InlineAlert> : null}
          <p>{preview.nodeImage}</p>{preview.panelSource ? <PanelNetworkAccess source={preview.panelSource} /> : <p>{copy.cidrs}: {preview.panelCidrs?.join(', ') ?? cidrValues.join(', ')}</p>}
          {preview.panelSource?.confidence === 'UNRESOLVED' ? <div className="integration-row-actions"><button type="button" disabled={previewRequest.isPending || reconcile.isPending} onClick={() => preview.recovery && preview.recovery.action !== 'RECREATE_WITH_NEW_CONFIG' ? void reconcileRun(preview.recovery.sourceRunId, 'RECOVER') : void makePreview().catch(() => setWorkflowError(true))}>{copy.checkAgain}</button><button type="button" onClick={() => setStep(2)}>{i18n.locale === 'ru' ? 'Расширенные настройки' : 'Advanced settings'}</button></div> : null}
          {preview.nodeApi ? <p>Remnawave {preview.nodeApi.serverVersion} · {preview.nodeApi.apiGeneration} · {preview.nodeApi.sourceCommit}</p> : null}
          {preview.protocolPorts?.length ? <ProtocolPorts facts={preview.protocolPorts} /> : null}
          <PackageProbeFindings findings={preview.packageFindings} /><ReviewList title={copy.changes} values={preview.changes} /><ReviewList title={copy.warnings} values={preview.warnings.filter(code => code !== 'REMNAWAVE_ONBOARDING_REUSE_EXISTING_NODE')} /><ReviewList title={copy.serverProfile} values={preview.blockingProblems.filter(code => code.startsWith('PROVISIONING_') && !['PROVISIONING_NODE_INSTALLATION_UNMANAGED', 'PROVISIONING_NODE_PORT_OCCUPIED'].includes(code)).map(code => i18n.t.provisioning.errors[code] ?? code)} danger /><ReviewList title={copy.blockers} values={preview.blockingProblems.filter(code => !code.startsWith('REMNAWAVE_LOCAL_INSTALLATION_') && !code.startsWith('PROVISIONING_')).map(code => networkPhaseNames(i18n.locale)[code] ?? localDiagnosis(code, i18n.locale) ?? (['REMNAWAVE_ONBOARDING_EXISTING_NODE_REQUIRES_REVIEW', 'REMNAWAVE_ONBOARDING_RECOVERY_CONFLICT'].includes(code)
            ? i18n.locale === 'ru' ? 'На сервере уже создан узел. Его состояние или новые параметры не допускают безопасное продолжение onboarding. Проверьте предыдущий запуск и узел; не создавайте дубликат.' : 'A node was already created on this server. Its state or the new inputs prevent safe onboarding recovery. Review the previous run and node; do not create a duplicate.'
            : i18n.t.provisioning.errors[code] ?? code))} danger />
          </section> : null}
      </> : null}
      {step === 3 && preview?.recovery ? <RecoveryReview recovery={preview.recovery} newCorrelation={preview.blockingProblems.length ? undefined : preview.run.correlationId} confirmed={recoveryConfirmed} onConfirm={setRecoveryConfirmed} copy={copy} /> : null}
      {(step === 4 || (hasRun && step !== 3 && !preview?.recovery)) && current ? <RunStatus run={current} detail={runQuery.data} copy={copy} /> : null}
      {(step === 4 || (hasRun && step !== 3 && !preview?.recovery)) && current && ['FAILED', 'UNKNOWN', 'SUCCEEDED'].includes(current.state) && canConfigure ? <div className="integration-row-actions">
        {current.failureCode === 'REMNAWAVE_PANEL_SOURCE_OBSERVATION_UNAVAILABLE' ? <PendingButton className="secondary-button" type="button" pending={reconcile.isPending} pendingLabel={i18n.t.common.inProgress} disabled={reconcile.isPending || runQuery.isPending} onClick={() => void reconcileRun(current.id, 'RECOVER', true)}>{i18n.locale === 'ru' ? 'Расширенные ручные настройки' : 'Advanced manual settings'}</PendingButton> : null}
        {current.failureCode === 'REMNAWAVE_PANEL_CONNECTIVITY_TIMEOUT' || current.failureCode === 'REMNAWAVE_NODE_CONNECTION_TIMEOUT' ? <PendingButton className="primary-button" type="button" pending={reconcile.isPending} pendingLabel={i18n.t.common.inProgress} disabled={reconcile.isPending || runQuery.isPending} onClick={() => void reconcileRun(current.id, 'RECOVER')}>{i18n.locale === 'ru' ? 'Исправить доступ' : 'Repair Panel access'}</PendingButton> : null}
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
  const network: Record<string, [string,string]> = {
    REMNAWAVE_PANEL_SOURCE_UNRESOLVED: ['Не удалось определить сетевой источник Panel. Проверьте снова или откройте расширенные настройки сети.', 'Could not resolve a Panel network source. Check again or open advanced network settings.'],
    REMNAWAVE_PANEL_SOURCE_LIMIT_EXCEEDED: ['Найдено слишком много адресов для безопасного обновления. Требуется ручная проверка.', 'Too many sources for a safe update. Manual review is required.'],
    REMNAWAVE_PANEL_CONNECTIVITY_MANUAL_ONLY: ['Правила доступа к Node конфликтуют с управляемой политикой. Требуется ручная проверка; чужие правила не изменяются.', 'Node firewall rules conflict with the managed policy. Manual review is required; foreign rules will not be changed.'],
    REMNAWAVE_ONBOARDING_SOURCE_CHANGED: ['Сетевые источники Panel изменились после проверки. Подготовьте новый план.', 'Panel network sources changed after review. Prepare a new preview.'],
  }
  if(network[code]) return network[code][locale === 'ru' ? 0 : 1]
  const reasons: Record<string, [string, string]> = {
    SSH_UNAVAILABLE: ['Нет подтверждённого доступа по SSH. Проверьте подключение и учётные данные.', 'SSH access unavailable. Check the connection and credentials.'],
    OBSERVATION_TIMEOUT: ['Проверка превысила время ожидания. Проверьте доступность сервера.', 'Observation timed out. Check server availability.'],
    OUTPUT_TRUNCATED: ['Ответ проверки неполный. Повторите проверку.', 'Observation output incomplete. Check again.'],
    COMPOSE_UNREADABLE: ['Не удалось прочитать файлы установки. Проверьте права доступа.', 'Installation files could not be read. Check access permissions.'],
    OWNER_UNPROVEN: ['Не удалось подтвердить владельца установки. Требуется ручная проверка.', 'Installation ownership could not be proven. Manual review required.'],
    CONTAINER_STATE_UNKNOWN: ['Не удалось проверить контейнеры. Проверьте доступность Docker.', 'Containers could not be inspected. Check Docker availability.'],
    FIREWALL_STATE_UNKNOWN: ['Не удалось проверить правила межсетевого экрана. Проверьте состояние UFW.', 'Firewall rules could not be verified. Check UFW.'],
    PORT_STATE_UNKNOWN: ['Не удалось проверить занятость порта. Проверьте состояние сервера.', 'Port occupancy could not be verified. Check the server.'],
    HASH_PROBE_FAILED: ['Не удалось проверить SHA-256 установки.', 'Installation SHA-256 probe failed.'],
    FILESYSTEM_METADATA_UNAVAILABLE: ['Не удалось прочитать метаданные файловой системы.', 'Filesystem metadata could not be read.'],
    STAGING_METADATA_UNAVAILABLE: ['Не удалось прочитать метаданные staging.', 'Staging metadata could not be read.'],
    PROBE_EXECUTION_FAILED: ['Shell-проверка завершилась с ошибкой исполнения.', 'The shell probe exited with an execution error.'],
    PROBE_OUTPUT_INVALID: ['Shell-проверка вернула некорректный результат.', 'The shell probe returned an invalid result.'],
    STATE_UNKNOWN: ['Проверка не подтвердила безопасное состояние. Требуется повторная или ручная проверка.', 'Observation did not prove a safe state. Check again or review manually.'],
  }
  return reasons[code.replace(/^REMNAWAVE_LOCAL_INSTALLATION_/, '')]?.[locale === 'ru' ? 0 : 1]
}
function ProtocolPorts({ facts }: { facts: NonNullable<NodeOnboardingPreview['protocolPorts']> }) {
  const i18n = useI18n()
  const names = {
    FREE: ['Свободен', 'Free'], OWNED_EXPECTED: ['Используется управляемым Xray', 'Owned Xray listener'],
    FOREIGN_LISTENER: ['Занят другим процессом', 'Occupied by another process'],
    FIREWALL_CONFLICT: ['Конфликт правил доступа', 'Firewall conflict'],
    OBSERVATION_UNKNOWN: ['Не удалось проверить', 'Observation unavailable'],
  }
  return <section><h4>{i18n.locale === 'ru' ? 'Клиентские порты' : 'Client ports'}</h4><ul>{facts.map(fact =>
    <li key={`${fact.port}-${fact.transport}`}>{fact.transport.toUpperCase()}/{fact.port}: {names[fact.state][i18n.locale === 'ru' ? 0 : 1]}</li>)}</ul></section>
}
function ReviewList({ title, values, danger = false }: { title: string; values: string[]; danger?: boolean }) {
  const i18n = useI18n()
  const labels = networkPhaseNames(i18n.locale)
  const names = texts[i18n.locale].phaseNames
  return <section><h4>{title}</h4>{values.length ? <ul>{values.map((value, index) => <li key={`${index}-${value}`}>{labels[value] ?? names[value as keyof typeof names] ?? value}</li>)}</ul> : <p>—</p>}{danger && values.length ? <p role="alert">{title}</p> : null}</section>
}
function networkPhaseNames(locale: string): Record<string, string> {
  const discovery = locale === 'ru' ? { UPDATE_NODE_ADDRESS: 'Обновление адреса существующего узла', WAIT_FOR_PANEL: 'Наблюдение подключения Panel', OBSERVE_PANEL_SOURCE: 'Наблюдение исходящего адреса Panel', ADD_OBSERVED_PANEL_SOURCE: 'Проверка обнаруженного источника', VERIFY_OBSERVED_PANEL: 'Наблюдение подключения с обнаруженным IP', FINALIZE_PANEL_SOURCES: 'Сетевая проверка завершена' }
    : { UPDATE_NODE_ADDRESS: 'Update existing node address', WAIT_FOR_PANEL: 'Observe Panel connection', OBSERVE_PANEL_SOURCE: 'Observe actual Panel source', ADD_OBSERVED_PANEL_SOURCE: 'Test observed source', VERIFY_OBSERVED_PANEL: 'Observe connection with discovered IP', FINALIZE_PANEL_SOURCES: 'Network verification completed' }
  const base = locale === 'ru' ? { RESOLVE_PANEL_SOURCE: 'Определение сетевых источников Panel', ADD_PANEL_SOURCES: 'Добавление проверенных источников Panel', FINALIZE_PANEL_SOURCES: 'Подтверждение или откат сетевого доступа', REMNAWAVE_PANEL_SOURCE_UNRESOLVED: 'Не удалось определить адреса Panel. Повторите проверку или укажите источники в расширенных настройках.', REMNAWAVE_PANEL_SOURCE_LIMIT_EXCEEDED: 'Слишком много сетевых источников. Требуется ручная проверка.', REMNAWAVE_PANEL_CONNECTIVITY_MANUAL_ONLY: 'Правила доступа конфликтуют с управляемой политикой. Требуется ручная проверка.' } : { RESOLVE_PANEL_SOURCE: 'Resolve Panel network sources', ADD_PANEL_SOURCES: 'Add reviewed Panel sources', FINALIZE_PANEL_SOURCES: 'Confirm or roll back Panel access', REMNAWAVE_PANEL_SOURCE_UNRESOLVED: 'Could not resolve Panel addresses. Check again or enter sources in advanced settings.', REMNAWAVE_PANEL_SOURCE_LIMIT_EXCEEDED: 'Too many network sources. Manual review is required.', REMNAWAVE_PANEL_CONNECTIVITY_MANUAL_ONLY: 'Firewall rules conflict with the managed policy. Manual review is required.' }
  const protocol: Record<string, string> = locale === 'ru' ? {
    PROTOCOL_PREFLIGHT: 'Проверка клиентского порта и правил доступа',
    CONFIRM_PREVIOUS_NODE_ABSENT: 'Подтверждение отсутствия предыдущего узла',
    UNBIND_PREVIOUS_NODE: 'Снятие предыдущей привязки',
    RETIRE_PREVIOUS_CLIENT_FIREWALL: 'Удаление клиентских правил предыдущего узла',
    VERIFY_PREVIOUS_RETIRED: 'Проверка удаления старой установки и свободных портов',
    REMNAWAVE_ONBOARDING_REPLACEMENT_IRREVERSIBLE: 'После удаления предыдущей установки старая конфигурация не будет восстановлена автоматически.',
    REMNAWAVE_PROTOCOL_PORT_OCCUPIED: 'Клиентский порт занят другим процессом. Выберите свободный порт или проверьте сервер вручную.',
    REMNAWAVE_PROTOCOL_PORT_OBSERVATION_UNKNOWN: 'Состояние порта не подтверждено. Восстановите доступ и повторите проверку.',
    REMNAWAVE_CLIENT_FIREWALL_CONFLICT: 'Правила доступа конфликтуют с клиентским портом. Нужна ручная проверка.',
    REMNAWAVE_TLS_HTTP01_FIREWALL_CONFLICT: 'Правила доступа блокируют TCP/80. Исправьте их вручную или импортируйте сертификат.',
    CREATE_PROTOCOL_PROFILE: 'Создание отдельного профиля протокола', ISSUE_TLS: 'Выпуск TLS-сертификата', INSTALL_TLS: 'Установка TLS-сертификата',
    CONFIGURE_CLIENT_FIREWALL: 'Открытие клиентского порта', VERIFY_PROTOCOL: 'Проверка профиля и сокета Xray',
    REMNAWAVE_TLS_HTTP01_TEMPORARY_PORT_80: 'Для HTTP-01 будет временно открыт TCP/80 с автоматической очисткой.',
    REMNAWAVE_TLS_HTTP01_DNS_UNCONFIRMED: 'Домен TLS не указывает напрямую на этот сервер. Исправьте DNS или импортируйте сертификат.',
    REMNAWAVE_PROTOCOL_TLS_REQUIRED: 'Для Hysteria2 нужен проверенный сертификат или автоматический выпуск HTTP-01.',
    REMNAWAVE_TLS_HTTP01_PORT_OCCUPIED: 'TCP/80 занят. Используйте импорт сертификата.',
    REMNAWAVE_TLS_HTTP01_UNAVAILABLE: 'HTTP-01 недоступен на этом сервере. Используйте импорт сертификата.',
    REMNAWAVE_TLS_HTTP01_FAILED: 'Центр сертификации не подтвердил домен. Проверьте DNS и доступность TCP/80.',
    REMNAWAVE_TLS_HTTP01_UNKNOWN: 'Результат выпуска неизвестен. Повторный заказ сертификата заблокирован до проверки.',
  } : {
    PROTOCOL_PREFLIGHT: 'Check client port and firewall policy',
    CONFIRM_PREVIOUS_NODE_ABSENT: 'Confirm previous node absence',
    UNBIND_PREVIOUS_NODE: 'Remove previous binding',
    RETIRE_PREVIOUS_CLIENT_FIREWALL: 'Retire previous client firewall rules',
    VERIFY_PREVIOUS_RETIRED: 'Verify retirement and free ports',
    REMNAWAVE_ONBOARDING_REPLACEMENT_IRREVERSIBLE: 'The retired configuration will not be restored automatically.',
    REMNAWAVE_PROTOCOL_PORT_OCCUPIED: 'Another process occupies the client port. Choose a free port or review the server manually.',
    REMNAWAVE_PROTOCOL_PORT_OBSERVATION_UNKNOWN: 'Port state could not be confirmed. Restore access and check again.',
    REMNAWAVE_CLIENT_FIREWALL_CONFLICT: 'Firewall policy conflicts with the client port. Manual review is required.',
    REMNAWAVE_TLS_HTTP01_FIREWALL_CONFLICT: 'Firewall policy blocks TCP/80. Review the rules manually or import a certificate.',
    CREATE_PROTOCOL_PROFILE: 'Create isolated protocol profile', ISSUE_TLS: 'Issue TLS certificate', INSTALL_TLS: 'Install TLS certificate',
    CONFIGURE_CLIENT_FIREWALL: 'Open client port', VERIFY_PROTOCOL: 'Verify profile and Xray socket',
    REMNAWAVE_TLS_HTTP01_TEMPORARY_PORT_80: 'HTTP-01 temporarily opens TCP/80 with automatic cleanup.',
    REMNAWAVE_TLS_HTTP01_DNS_UNCONFIRMED: 'TLS domain does not point directly to this server. Fix DNS or import a certificate.',
    REMNAWAVE_PROTOCOL_TLS_REQUIRED: 'Hysteria2 requires a validated certificate or automatic HTTP-01 issuance.',
    REMNAWAVE_TLS_HTTP01_PORT_OCCUPIED: 'TCP/80 is occupied. Import a certificate instead.',
    REMNAWAVE_TLS_HTTP01_UNAVAILABLE: 'HTTP-01 is unavailable on this server. Import a certificate instead.',
    REMNAWAVE_TLS_HTTP01_FAILED: 'The CA could not validate this domain. Check DNS and TCP/80 connectivity.',
    REMNAWAVE_TLS_HTTP01_UNKNOWN: 'Issuance outcome is unknown. Another order is blocked pending observation.',
  }
  return { ...base, ...discovery, ...protocol }
}
function RecoveryReview({ recovery, newCorrelation, confirmed, onConfirm, copy }: { recovery: NodeOnboardingRecoverySummary; newCorrelation?: string; confirmed: boolean; onConfirm: (value: boolean) => void; copy: typeof texts[keyof typeof texts] }) {
  const i18n = useI18n()
  const local = recovery.localInstallation
  const localReview = local ? <section><h4>{i18n.locale === 'ru' ? 'Локальная установка' : 'Local installation'}</h4><InlineAlert tone={['FOREIGN', 'PORT_CONFLICT', 'UNKNOWN'].includes(local.state) ? 'warning' : 'info'} title={localStateLabel(local.state, i18n.locale)}>
    {local.diagnosis ? localDiagnosis(local.diagnosis, i18n.locale) : null}
    {['FOREIGN', 'PORT_CONFLICT'].includes(local.state) ? <span>{i18n.locale === 'ru' ? 'Требуется ручная проверка владельца установки и занятого порта. Автоматические изменения заблокированы.' : 'Review installation ownership and the occupied port manually. Automatic changes are blocked.'}</span> : null}
    {local.state === 'UNKNOWN' ? <span>{i18n.locale === 'ru' ? 'Восстановите доступ и повторите проверку локальной установки. Изменения заблокированы.' : 'Restore access and check the local installation again. Changes are blocked.'}</span> : null}
    {local.state.startsWith('OWNED_') && ['RECREATE','DELETE_RECREATE','RECREATE_WITH_NEW_CONFIG'].includes(recovery.action) ? <span>{i18n.locale === 'ru' ? 'После подтверждения InfraDesk удалит только доказанную установку и правила предыдущего узла, затем создаст новый узел.' : 'After confirmation, InfraDesk will retire the proven previous installation and its rules, then create a new node.'}</span> : null}
  </InlineAlert></section> : null
  if (recovery.state === 'UNKNOWN') return <section><h4>Remnawave Panel</h4><InlineAlert tone="warning" title={copy.recoveryUnknown} />{localReview}</section>
  if (recovery.state === 'PRESENT_CONFLICT') return <section><h4>Remnawave Panel</h4><InlineAlert tone="danger" title={copy.conflict} />{localReview}</section>
  if (recovery.state === 'CONFIRMED_NOT_FOUND') return <section><h4>Remnawave Panel</h4><InlineAlert tone="warning" title={copy.missingNode} />{localReview}
    <dl><dt>{copy.oldIdentity}</dt><dd>{recovery.previousExternalNodeId ?? '—'}</dd><dt>{copy.oldCorrelation}</dt><dd>{recovery.previousCorrelationId}</dd><dt>{copy.newCorrelation}</dt><dd>{newCorrelation ?? '—'}</dd></dl>
    {recovery.action === 'RECREATE_WITH_NEW_CONFIG' ? <p>{i18n.locale === 'ru' ? 'На сервере обнаружена предыдущая установка InfraDesk. После подтверждения будут удалены только её файлы и правила, затем создан узел с текущими параметрами и новым идентификатором. После удаления InfraDesk не будет автоматически восстанавливать старую конфигурацию.' : 'A previous InfraDesk installation was found. After confirmation, only its owned files and rules will be retired, then a node will be created with the current settings and a new identity. InfraDesk will not automatically restore the retired configuration.'}</p> : null}
    {recovery.previousInstallation?.inventoryObjectId ? <p>{i18n.locale === 'ru' ? 'Предыдущая привязка будет удалена.' : 'The previous binding will be removed.'}</p> : null}
    {!local || !['FOREIGN', 'PORT_CONFLICT', 'UNKNOWN'].includes(local.state) ? <label><input type="checkbox" checked={confirmed} onChange={event => onConfirm(event.target.checked)} />{recovery.action === 'RECREATE_WITH_NEW_CONFIG' ? i18n.locale === 'ru' ? 'Я подтверждаю пересоздание управляемого узла с новыми параметрами.' : 'I confirm recreation of the managed node with the new settings.' : copy.recreateApproval}</label> : null}</section>
  if (recovery.action === 'DELETE_RECREATE') return <section><h4>Remnawave Panel</h4><InlineAlert tone="warning" title={copy.deleteRecreate} />{localReview}
    <dl><dt>{copy.oldIdentity}</dt><dd>{recovery.previousExternalNodeId ?? '—'}</dd><dt>{copy.oldCorrelation}</dt><dd>{recovery.previousCorrelationId}</dd><dt>{copy.newCorrelation}</dt><dd>{newCorrelation ?? '—'}</dd></dl>
    {!local || !['FOREIGN', 'PORT_CONFLICT', 'UNKNOWN'].includes(local.state) ? <label><input type="checkbox" checked={confirmed} onChange={event => onConfirm(event.target.checked)} />{copy.deleteApproval}</label> : null}</section>
  return <section><h4>Remnawave Panel</h4><InlineAlert tone="info" title={copy.restore} />{localReview}<dl><dt>{copy.oldIdentity}</dt><dd>{recovery.previousExternalNodeId ?? '—'}</dd></dl></section>
}
function RunStatus({ run, detail, copy }: { run: NodeOnboardingRun; detail: ReturnType<typeof useNodeOnboardingRun>['data']; copy: typeof texts[keyof typeof texts] }) {
  const i18n = useI18n()
  const failureReason = (code: string) => code === 'REMNAWAVE_PANEL_SOURCE_OBSERVATION_UNAVAILABLE' ? i18n.locale === 'ru' ? 'На сервере недоступна безопасная пассивная проверка источника Panel. Временный доступ отменён. Доступны расширенные ручные настройки.' : 'Safe passive Panel source observation is unavailable on this host. Temporary access was rolled back. Advanced manual settings are available.'
    : code === 'REMNAWAVE_PANEL_SOURCE_NO_TRAFFIC' ? i18n.locale === 'ru' ? 'Входящие SYN на порт Node не обнаружены за время проверки. Panel не подключилась; временный доступ отменён. Проверьте доступность Node со стороны Panel и повторите проверку.' : 'No inbound SYN reached the Node port during observation. Panel did not connect; temporary access was rolled back. Check reachability from Panel and retry observation.'
    : code === 'REMNAWAVE_PANEL_CONNECTIVITY_MANUAL_ONLY' && run.observedPanelSource?.status === 'AMBIGUOUS' ? i18n.locale === 'ru' ? 'Пассивная проверка обнаружила несколько источников или превышение лимита пакетов. Источник Panel неоднозначен; временный доступ отменён. Требуется ручная проверка.' : 'Passive observation found multiple sources or exceeded the packet limit. The Panel source is ambiguous; temporary access was rolled back. Manual review is required.'
    : code === 'REMNAWAVE_PANEL_CONNECTIVITY_MANUAL_ONLY' ? i18n.locale === 'ru' ? 'Правила доступа конфликтуют с управляемой политикой. Требуется ручная проверка; чужие правила не изменялись.' : 'Firewall rules conflict with the managed policy. Manual review is required; foreign rules were not changed.'
    : code === 'REMNAWAVE_PANEL_CONNECTIVITY_TIMEOUT' ? i18n.locale === 'ru'
    ? `Node работает на сервере и слушает порт ${run.nodePort}, но Remnawave Panel не смог подключиться. Вероятная причина: исходящий адрес Panel не входит в разрешённые источники. Также проверьте доступность адреса Node.`
    : `Node is healthy and listens on port ${run.nodePort}, but Remnawave Panel could not connect. The Panel outbound address may be outside the allowed sources. Also check the Node address.`
    : ['FIREWALL_RULE_UNSUPPORTED', 'PROVISIONING_FIREWALL_RULE_UNSUPPORTED'].includes(code)
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
  const fallbackPhases: OnboardingRunPhase[] = run.recovery?.action === 'DELETE_RECREATE'
    ? [...phases.slice(0, 2), 'DELETE_NODE', 'CONFIRM_NODE_DELETED', ...retirementPhases, ...phases.slice(2)]
    : run.recovery?.action === 'RECREATE_WITH_NEW_CONFIG' ? [...phases.slice(0, 2), 'CONFIRM_PREVIOUS_NODE_ABSENT', ...(run.recovery.previousInstallation?.inventoryObjectId ? ['UNBIND_PREVIOUS_NODE' as const] : []), ...(run.recovery.previousInstallation?.protocol ? ['RETIRE_PREVIOUS_CLIENT_FIREWALL' as const] : []), 'RETIRE_NODE_FIREWALL', 'RETIRE_LOCAL_NODE', 'VERIFY_PREVIOUS_RETIRED', ...phases.slice(2)]
    : run.recovery?.action === 'RECREATE' ? [...phases.slice(0, 2), ...retirementPhases, ...phases.slice(2)] : [...phases]
  const runPhases = detail?.phases.length ? detail.phases.map(p => p.phase) : fallbackPhases
  const additionalNames = networkPhaseNames(i18n.locale)
  return <section><InlineAlert tone={tone} title={`${copy.run}: ${run.state}`}>{message}{run.failureCode ? <><br />{failure(run.failureCode)}</> : null}</InlineAlert>
    {run.connectivityFinding ? <section><h4>{i18n.locale === 'ru' ? 'Сетевой доступ Panel' : 'Panel connectivity'}</h4><p>{i18n.locale === 'ru' ? 'Локальная Node исправна. Правила доступа настроены.' : 'The local Node is healthy. Firewall rules are configured.'}</p><p>{run.connectivityFinding.panelSources.join(', ')} · {run.connectivityFinding.sourceEvidence}</p></section> : null}
    <p>{run.nodeName} · {run.address}:{run.nodePort}</p>
    {detail?.certificate ? <p>{i18n.locale === 'ru' ? 'TLS для' : 'TLS for'} {detail.certificate.domain} · {i18n.locale === 'ru' ? 'действует до' : 'expires'} {i18n.format.dateTime(detail.certificate.expiresAt)}.
      {' '}{i18n.locale === 'ru' ? 'Автоматическое продление пока не настроено.' : 'Automatic renewal is not configured.'}</p> : null}
    {run.protocol ? <ol className="onboarding-progress-groups">{[
      { label: i18n.locale === 'ru' ? 'Сервер' : 'Server', codes: ['VALIDATE', 'PREPARE_SERVER'] },
      ...(run.recovery?.action === 'RECREATE_WITH_NEW_CONFIG' ? [{ label: i18n.locale === 'ru' ? 'Предыдущая установка' : 'Previous installation', codes: ['CONFIRM_PREVIOUS_NODE_ABSENT', 'UNBIND_PREVIOUS_NODE', 'RETIRE_PREVIOUS_CLIENT_FIREWALL', 'RETIRE_NODE_FIREWALL', 'RETIRE_LOCAL_NODE', 'VERIFY_PREVIOUS_RETIRED'] }] : []),
      { label: i18n.locale === 'ru' ? 'Протокол и TLS' : 'Protocol and TLS', codes: ['PROTOCOL_PREFLIGHT', 'ISSUE_TLS', 'CREATE_PROTOCOL_PROFILE', 'CREATE_NODE', 'INSTALL_TLS', 'CONFIGURE_CLIENT_FIREWALL'] },
      { label: i18n.locale === 'ru' ? 'Установка' : 'Installation', codes: ['GET_INSTALLATION_DATA', 'CONFIGURE_NODE_FIREWALL', 'INSTALL_NODE', 'START_NODE', 'VERIFY_LOCAL_NODE'] },
      { label: i18n.locale === 'ru' ? 'Связь с Panel' : 'Panel connection', codes: ['WAIT_FOR_PANEL', 'OBSERVE_PANEL_SOURCE', 'ADD_OBSERVED_PANEL_SOURCE', 'VERIFY_OBSERVED_PANEL', 'FINALIZE_PANEL_SOURCES'] },
      { label: i18n.locale === 'ru' ? 'Проверка результата' : 'Deployment verification', codes: ['SYNC_INVENTORY', 'BIND_RESOURCE', 'SET_DESIRED_STATE', 'VERIFY_PROTOCOL', 'FINAL_VERIFY'] },
    ].map(group => {
      const records = group.codes.flatMap(code => byPhase.get(code) ? [byPhase.get(code)!] : [])
      const state = records.some(record => record.state === 'FAILED') ? 'FAILED' : records.some(record => record.state === 'UNKNOWN') ? 'UNKNOWN' :
        records.some(record => record.state === 'RUNNING') ? 'RUNNING' : records.length && records.every(record => record.state === 'SUCCEEDED') &&
        (!records.some(record => record.outcome === 'NOT_CONNECTED') || run.connectivityCompletion === 'PROMOTED') ? 'SUCCEEDED' : 'PENDING'
      const labels = i18n.locale === 'ru' ? { FAILED: 'Ошибка', UNKNOWN: 'Нужна проверка', RUNNING: 'Выполняется', SUCCEEDED: 'Проверено', PENDING: 'Ожидает' } :
        { FAILED: 'Failed', UNKNOWN: 'Needs observation', RUNNING: 'In progress', SUCCEEDED: 'Verified', PENDING: 'Waiting' }
      return <li key={group.label} data-state={state}><strong>{group.label}</strong><span>{labels[state]}</span></li>
    })}</ol> : null}
    <details open={!run.protocol || run.state === 'FAILED' || run.state === 'UNKNOWN'}><summary>{copy.phases}</summary><ol>{runPhases.map(phase => {
      const record = byPhase.get(phase)
      const code = record?.failureCode ?? (run.phase === phase ? run.failureCode : null)
      const name = additionalNames[phase] ?? copy.phaseNames[phase as keyof typeof copy.phaseNames] ?? phase
      const outcomes: Record<string,string> = i18n.locale === 'ru'
        ? { CONNECTED: 'Подключено', NOT_CONNECTED: 'Не подключено', NOT_CONFIRMED: 'Подключение не подтверждено', ROLLED_BACK: '✓ временные изменения отменены; ✗ Panel не подключилась', NOT_REQUIRED: 'Не требуется', AUTO_OBSERVED: 'Исходящий IP обнаружен', NO_TRAFFIC: 'Входящие SYN не обнаружены', AMBIGUOUS: 'Источники неоднозначны', UNAVAILABLE: 'Пассивная проверка недоступна' }
        : { CONNECTED: 'Connected', NOT_CONNECTED: 'Not connected', NOT_CONFIRMED: 'Connectivity not confirmed', ROLLED_BACK: '✓ temporary changes rolled back; ✗ Panel did not connect', NOT_REQUIRED: 'Not required', AUTO_OBSERVED: 'Outbound IP observed', NO_TRAFFIC: 'No inbound SYN observed', AMBIGUOUS: 'Sources are ambiguous', UNAVAILABLE: 'Passive observation unavailable' }
      if(record?.outcome === 'ROLLED_BACK' || record?.outcome === 'ROLLED_BACK_UNKNOWN') return <li key={phase}><InlineAlert tone="success" title={name}>{record.outcome === 'ROLLED_BACK' ? outcomes.ROLLED_BACK : i18n.locale === 'ru' ? '✓ временные изменения отменены; подключение Panel не удалось проверить' : '✓ temporary changes rolled back; Panel connectivity could not be verified'}</InlineAlert></li>
      return <li key={phase}>{code ? <InlineAlert tone={record?.state === 'UNKNOWN' || run.state === 'UNKNOWN' ? 'warning' : 'danger'} title={`${name} — ${record?.state ?? run.state}`}>{failure(code)}</InlineAlert>
        : <><span>{name}</span> — {record?.outcome ? outcomes[record.outcome] ?? record.outcome : record?.state ?? 'PENDING'}</>}</li>
    })}</ol></details>
    {run.protocol && run.state === 'SUCCEEDED' ? <InlineAlert tone="info" title={i18n.locale === 'ru' ? 'Нода настроена и подключена к Panel' : 'Node configured and connected to Panel'}>
      {i18n.locale === 'ru' ? 'Профиль и клиентский сокет Xray проверены. Подключение пользователя и передача VPN-трафика ещё не проверялись. Для выдачи доступа привяжите inbound к нужной группе пользователей в Remnawave.' : 'The profile and Xray client socket are verified. User connection and VPN traffic have not been tested. Assign the inbound to the intended user group in Remnawave to publish access.'}
    </InlineAlert> : null}
    {(run.externalNodeId || run.baselineRunId || run.syncSessionId) ? <><h3>{copy.partial}</h3><dl>
      {run.externalNodeId ? <><dt>{copy.externalId}</dt><dd>{run.externalNodeId}</dd></> : null}
      {run.baselineRunId ? <><dt>{copy.baseline}</dt><dd>{run.baselineRunId}</dd></> : null}
      {run.syncSessionId ? <><dt>{copy.syncId}</dt><dd>{run.syncSessionId}</dd></> : null}</dl></> : null}
  </section>
}
