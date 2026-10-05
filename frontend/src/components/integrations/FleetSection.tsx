import { useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useAddFleetMember, useCreateFleet, useCreateFleetRevision, useFleet, useFleetCandidates, useFleets,
  usePreviewFleetRevision, usePromoteFleetRevision, useRefreshFleet, useRemoveFleetMember } from '../../api/remnawaveFleets'
import { useNodeOnboardingOptions } from '../../api/nodeOnboarding'
import { useServerProfiles } from '../../api/serverProfiles'
import { useOrganizationPermissions } from '../auth/authorization'
import { useI18n } from '../../i18n'
import { EmptyWorkspaceState, InlineAlert, PropertyGrid, StatusIndicator, WorkspaceSection } from '../layout/WorkspacePrimitives'
import type { StatusTone } from '../layout/WorkspacePrimitives'
import { FleetRolloutPanel } from './FleetRolloutPanel'
import { NodeVersionsPanel } from './NodeVersionsPanel'
import { IntegrationDialog } from './IntegrationDialog'
import type { Fleet, FleetCompliance, FleetDesiredRequest, FleetDetail, FleetHealth, FleetMember,
  FleetRevision, FleetRevisionImpact } from '../../types/remnawaveFleet'

const texts = {
  en: {
    title: 'Fleet', detail: 'Declare how a group of Remnawave nodes should look. InfraDesk reports what differs and changes nothing.',
    create: 'Create fleet', createTitle: 'Create a fleet', empty: 'No fleets yet',
    emptyDetail: 'A fleet is a named group of Remnawave nodes with one desired configuration.',
    unsupported: 'Fleet desired state needs a Remnawave integration with discovered nodes and an adopted configuration profile.',
    loading: 'Loading…', name: 'Name', code: 'Code', description: 'Description', members: 'Members',
    serverProfile: 'Server profile', configProfile: 'Remnawave configuration profile', revision: 'Revision',
    inbounds: 'Active inbounds', port: 'Node port', cidrs: 'Panel CIDRs', desiredState: 'Desired node state',
    cidrHint: 'Comma-separated IPv4/IPv6 ranges allowed to reach the node management port.',
    selectMembers: 'Select members', next: 'Continue', back: 'Back', close: 'Close', cancel: 'Cancel',
    desiredRevision: 'Desired revision', newRevision: 'Create revision', refresh: 'Refresh state',
    refreshed: (count: number) => `${count} member${count === 1 ? '' : 's'} will be checked again.`,
    promote: 'Make desired', promoted: 'This revision is now the desired state. Members that differ are reported as drifted.',
    notDesired: 'Not desired', isDesired: 'Desired', history: 'Revision history', created: 'Created',
    compliance: 'Compliance', health: 'Health', lastChecked: 'Last checked', never: 'Not checked yet',
    stale: 'Needs a refresh', staleDetail: 'The newest evidence is older than the freshness window, so this state is not current.',
    node: 'Node', server: 'Server', location: 'Location', actual: 'Actual', desired: 'Desired',
    rollout: 'Rollout eligibility', eligible: 'No blockers', blockers: 'Blockers',
    localManagement: 'Local installation', managed: 'Managed by InfraDesk', unmanaged: 'Not managed by InfraDesk',
    unmanagedDetail: 'InfraDesk has no record of installing this node, so its local state and firewall cannot be confirmed.',
    memberDetail: 'Desired versus actual', remove: 'Remove from fleet',
    removeDetail: 'The fleet stops owning this desired state. Nothing is changed on the node or in Remnawave.',
    addMember: 'Add member', addMemberTitle: 'Add a node to the fleet', noCandidates: 'No nodes available to add',
    noCandidatesDetail: 'A node must be discovered, active and linked to an active InfraDesk server.',
    impact: 'Impact of this revision', impactDetail: 'An estimate from the stored state. Nothing is applied by this preview.',
    expectedDrift: 'Expected drift', compliantAfter: 'Compliant after change', unknownAfter: 'Unknown',
    blockedAfter: 'Blocked', affected: 'Affected members', noChange: 'No change',
    noDesiredRevision: 'No desired revision', formError: 'Choose a server profile and configuration profile, at least one inbound, a port from 1 to 65535 and valid CIDR ranges.',
    requestError: 'The request could not be completed.',
    createdDetail: 'The fleet was created. Members that already differ from the desired configuration are reported as drifted; nothing was applied.',
    complianceNames: { COMPLIANT: 'Compliant', DRIFTED: 'Drifted', UNKNOWN: 'Unknown', BLOCKED: 'Blocked' } as Record<string, string>,
    healthNames: { HEALTHY: 'Healthy', DEGRADED: 'Degraded', UNKNOWN: 'Unknown' } as Record<string, string>,
    stateNames: { ENABLED: 'Enabled', DISABLED: 'Disabled' } as Record<string, string>,
    reasons: {
      SERVER_PROFILE_ASSIGNMENT_MISMATCH: 'Another server profile revision is assigned',
      SERVER_PROFILE_CONTENT_DRIFT: 'The server differs from the desired profile',
      SERVER_PROFILE_UNOBSERVED: 'The server has not been observed',
      CONFIG_PROFILE_MISMATCH: 'The node uses another configuration profile',
      CONFIG_REVISION_DRIFT: 'The remote configuration differs from the desired revision',
      CONFIG_REVISION_UNOBSERVED: 'The remote configuration has not been observed',
      ACTIVE_INBOUNDS_DRIFT: 'The active inbounds differ',
      ACTIVE_INBOUNDS_UNOBSERVED: 'The active inbounds have not been observed',
      DESIRED_NODE_STATE_DRIFT: 'The stored intent differs from the fleet',
      ACTUAL_NODE_STATE_DRIFT: 'The node state differs from the fleet',
      NODE_PORT_DRIFT: 'The node management port differs',
      PANEL_CIDR_DRIFT: 'The allowed panel ranges differ',
      LOCAL_INSTALLATION_DRIFT: 'The managed installation on the server differs',
      LOCAL_OBSERVATION_UNAVAILABLE: 'The server could not be observed',
      LOCAL_MANAGEMENT_UNAVAILABLE: 'InfraDesk does not manage this local installation',
      BINDING_MISSING: 'The node is not linked to this server',
      INVENTORY_MISSING: 'The node is no longer in the inventory',
      INVENTORY_STALE: 'The inventory is out of date',
      RESOURCE_UNAVAILABLE: 'The linked server is unavailable',
      SERVER_PROFILE_UNAVAILABLE: 'The desired server profile is unavailable',
      CONFIG_PROFILE_UNAVAILABLE: 'The desired configuration profile is unavailable',
      API_CONTRACT_UNCONFIRMED: 'The reviewed Remnawave contract is unconfirmed',
    } as Record<string, string>,
    healthReasons: {
      NODE_DISCONNECTED: 'The node is not connected', NODE_DISABLED_UNEXPECTEDLY: 'The node is disabled',
      NODE_ENABLED_UNEXPECTEDLY: 'The node is enabled while the fleet wants it disabled',
      LOCAL_CONTAINER_NOT_RUNNING: 'The container is not running',
      LOCAL_PORT_NOT_LISTENING: 'The management port is not listening',
      LOCAL_CONTAINER_UNSTABLE: 'The container is restarting',
      LOCAL_OBSERVATION_FAILED: 'The server could not be observed',
      INVENTORY_STALE: 'The inventory is out of date', INVENTORY_MISSING: 'The node is not in the inventory',
    } as Record<string, string>,
    blockerNames: {
      NO_TRUSTED_SSH: 'No trusted SSH source', NO_MANAGED_LOCAL_INSTALLATION: 'No managed local installation',
      API_CONTRACT_UNCONFIRMED: 'Remnawave contract unconfirmed', BINDING_INVALID: 'The server link is invalid',
      UNKNOWN_REMOTE_STATE: 'The remote state is unknown', ACTIVE_CONFLICTING_OPERATION: 'Another operation is running',
      REFERENCED_OBJECT_UNAVAILABLE: 'A referenced object is unavailable',
    } as Record<string, string>,
  },
  ru: {
    title: 'Fleet', detail: 'Опишите, как должна выглядеть группа узлов Remnawave. InfraDesk показывает расхождения и ничего не меняет.',
    create: 'Создать fleet', createTitle: 'Создание fleet', empty: 'Fleet пока нет',
    emptyDetail: 'Fleet — именованная группа узлов Remnawave с одной желаемой конфигурацией.',
    unsupported: 'Для желаемого состояния нужен Remnawave с обнаруженными узлами и управляемым профилем конфигурации.',
    loading: 'Загрузка…', name: 'Название', code: 'Код', description: 'Описание', members: 'Узлы',
    serverProfile: 'Серверный профиль', configProfile: 'Профиль конфигурации Remnawave', revision: 'Ревизия',
    inbounds: 'Активные входящие подключения', port: 'Порт узла', cidrs: 'CIDR панели',
    desiredState: 'Желаемое состояние узла',
    cidrHint: 'CIDR IPv4/IPv6 через запятую, которым разрешён доступ к порту управления узла.',
    selectMembers: 'Выбор узлов', next: 'Далее', back: 'Назад', close: 'Закрыть', cancel: 'Отмена',
    desiredRevision: 'Желаемая ревизия', newRevision: 'Создать ревизию', refresh: 'Обновить состояние',
    refreshed: (count: number) => `Узлов к повторной проверке: ${count}.`,
    promote: 'Сделать желаемой', promoted: 'Эта ревизия стала желаемой. Узлы с расхождениями показаны как DRIFTED.',
    notDesired: 'Не желаемая', isDesired: 'Желаемая', history: 'История ревизий', created: 'Создана',
    compliance: 'Соответствие', health: 'Здоровье', lastChecked: 'Последняя проверка', never: 'Ещё не проверялось',
    stale: 'Требуется обновление', staleDetail: 'Самые свежие данные старше допустимого окна, поэтому это состояние не актуально.',
    node: 'Узел', server: 'Сервер', location: 'Расположение', actual: 'Фактически', desired: 'Желаемое',
    rollout: 'Готовность к раскатке', eligible: 'Блокировок нет', blockers: 'Блокировки',
    localManagement: 'Локальная установка', managed: 'Управляется InfraDesk', unmanaged: 'Не управляется InfraDesk',
    unmanagedDetail: 'У InfraDesk нет записи об установке этого узла, поэтому локальное состояние и межсетевой экран подтвердить нельзя.',
    memberDetail: 'Желаемое и фактическое', remove: 'Убрать из fleet',
    removeDetail: 'Fleet перестаёт владеть этим желаемым состоянием. На узле и в Remnawave ничего не меняется.',
    addMember: 'Добавить узел', addMemberTitle: 'Добавление узла в fleet', noCandidates: 'Нет доступных узлов',
    noCandidatesDetail: 'Узел должен быть обнаружен, активен и связан с активным сервером InfraDesk.',
    impact: 'Влияние этой ревизии', impactDetail: 'Оценка по сохранённым данным. Этот предпросмотр ничего не применяет.',
    expectedDrift: 'Ожидаемые расхождения', compliantAfter: 'Будут соответствовать', unknownAfter: 'Неизвестно',
    blockedAfter: 'Заблокировано', affected: 'Затронуто узлов', noChange: 'Без изменений',
    noDesiredRevision: 'Желаемой ревизии нет',
    formError: 'Выберите серверный профиль и профиль конфигурации, хотя бы одно входящее подключение, порт от 1 до 65535 и корректные CIDR.',
    requestError: 'Не удалось выполнить запрос.',
    createdDetail: 'Fleet создан. Узлы, которые уже отличаются от желаемой конфигурации, показаны как DRIFTED; ничего не применялось.',
    complianceNames: { COMPLIANT: 'Соответствует', DRIFTED: 'Расхождение', UNKNOWN: 'Неизвестно', BLOCKED: 'Заблокировано' } as Record<string, string>,
    healthNames: { HEALTHY: 'Здоров', DEGRADED: 'Деградация', UNKNOWN: 'Неизвестно' } as Record<string, string>,
    stateNames: { ENABLED: 'Включён', DISABLED: 'Выключен' } as Record<string, string>,
    reasons: {
      SERVER_PROFILE_ASSIGNMENT_MISMATCH: 'Назначена другая ревизия серверного профиля',
      SERVER_PROFILE_CONTENT_DRIFT: 'Сервер отличается от желаемого профиля',
      SERVER_PROFILE_UNOBSERVED: 'Сервер не наблюдался',
      CONFIG_PROFILE_MISMATCH: 'Узел использует другой профиль конфигурации',
      CONFIG_REVISION_DRIFT: 'Удалённая конфигурация отличается от желаемой ревизии',
      CONFIG_REVISION_UNOBSERVED: 'Удалённая конфигурация не наблюдалась',
      ACTIVE_INBOUNDS_DRIFT: 'Активные входящие подключения отличаются',
      ACTIVE_INBOUNDS_UNOBSERVED: 'Активные входящие подключения не наблюдались',
      DESIRED_NODE_STATE_DRIFT: 'Сохранённое намерение отличается от fleet',
      ACTUAL_NODE_STATE_DRIFT: 'Состояние узла отличается от fleet',
      NODE_PORT_DRIFT: 'Порт управления узла отличается',
      PANEL_CIDR_DRIFT: 'Разрешённые диапазоны панели отличаются',
      LOCAL_INSTALLATION_DRIFT: 'Управляемая установка на сервере отличается',
      LOCAL_OBSERVATION_UNAVAILABLE: 'Сервер не удалось опросить',
      LOCAL_MANAGEMENT_UNAVAILABLE: 'InfraDesk не управляет этой локальной установкой',
      BINDING_MISSING: 'Узел не связан с этим сервером',
      INVENTORY_MISSING: 'Узла больше нет в инвентаре',
      INVENTORY_STALE: 'Инвентарь устарел',
      RESOURCE_UNAVAILABLE: 'Связанный сервер недоступен',
      SERVER_PROFILE_UNAVAILABLE: 'Желаемый серверный профиль недоступен',
      CONFIG_PROFILE_UNAVAILABLE: 'Желаемый профиль конфигурации недоступен',
      API_CONTRACT_UNCONFIRMED: 'Проверенный контракт Remnawave не подтверждён',
    } as Record<string, string>,
    healthReasons: {
      NODE_DISCONNECTED: 'Узел не подключён', NODE_DISABLED_UNEXPECTEDLY: 'Узел выключен',
      NODE_ENABLED_UNEXPECTEDLY: 'Узел включён, хотя fleet требует выключения',
      LOCAL_CONTAINER_NOT_RUNNING: 'Контейнер не запущен',
      LOCAL_PORT_NOT_LISTENING: 'Порт управления не слушает',
      LOCAL_CONTAINER_UNSTABLE: 'Контейнер перезапускается',
      LOCAL_OBSERVATION_FAILED: 'Сервер не удалось опросить',
      INVENTORY_STALE: 'Инвентарь устарел', INVENTORY_MISSING: 'Узла нет в инвентаре',
    } as Record<string, string>,
    blockerNames: {
      NO_TRUSTED_SSH: 'Нет доверенного SSH-источника', NO_MANAGED_LOCAL_INSTALLATION: 'Нет управляемой локальной установки',
      API_CONTRACT_UNCONFIRMED: 'Контракт Remnawave не подтверждён', BINDING_INVALID: 'Связь с сервером некорректна',
      UNKNOWN_REMOTE_STATE: 'Удалённое состояние неизвестно', ACTIVE_CONFLICTING_OPERATION: 'Выполняется другая операция',
      REFERENCED_OBJECT_UNAVAILABLE: 'Связанный объект недоступен',
    } as Record<string, string>,
  },
}

type Copy = typeof texts['en']

const complianceTone = (value: FleetCompliance): StatusTone =>
  value === 'COMPLIANT' ? 'success' : value === 'DRIFTED' ? 'warning' : value === 'BLOCKED' ? 'danger' : 'neutral'
const healthTone = (value: FleetHealth): StatusTone =>
  value === 'HEALTHY' ? 'success' : value === 'DEGRADED' ? 'warning' : 'neutral'

function validCidr(value: string) {
  const [ip, mask, ...rest] = value.split('/')
  if (rest.length || !ip || !mask) return false
  const bits = Number(mask)
  if (!Number.isInteger(bits) || bits < 1) return false
  if (ip.includes(':')) return /^[0-9a-fA-F:]+$/.test(ip) && bits <= 128
  return /^(\d{1,3}\.){3}\d{1,3}$/.test(ip) && ip.split('.').every(part => Number(part) <= 255) && bits <= 32
}

export function FleetSection({ organizationId, integrationId }: { organizationId: string; integrationId: string }) {
  const i18n = useI18n(); const copy = texts[i18n.locale]
  const permissions = useOrganizationPermissions(organizationId)
  const canRead = permissions.can('readOrganization')
  const canManage = permissions.can('manageIntegrations') && permissions.can('manageConfigurations')
  const canObserve = canManage && permissions.can('executeOperations')
  const fleets = useFleets(organizationId, integrationId, canRead)
  const [params, setParams] = useSearchParams()
  const selected = params.get('fleet')
  const detail = useFleet(organizationId, integrationId, selected)
  const [creating, setCreating] = useState(false)
  const select = (id: string | null) => setParams(previous => {
    const next = new URLSearchParams(previous)
    if (id) next.set('fleet', id); else next.delete('fleet')
    return next
  }, { replace: true })

  if (!canRead) return null
  if (selected && detail.data) {
    return <FleetDetailView copy={copy} organizationId={organizationId} integrationId={integrationId}
      detail={detail.data} canManage={canManage} canObserve={canObserve} onBack={() => select(null)} />
  }
  return <WorkspaceSection title={copy.title} description={copy.detail}
    actions={canManage ? <button className="primary-button" type="button" onClick={() => setCreating(true)}>
      {copy.create}</button> : undefined}>
    {fleets.isPending ? <p className="muted">{copy.loading}</p> : null}
    {fleets.data && fleets.data.items.length === 0
      ? <EmptyWorkspaceState title={copy.empty} detail={copy.emptyDetail} /> : null}
    {fleets.data && fleets.data.items.length > 0 ? <ul className="card-list">
      {fleets.data.items.map(fleet => <li key={fleet.id}>
        <FleetCard copy={copy} fleet={fleet} onOpen={() => select(fleet.id)} />
      </li>)}
    </ul> : null}
    {creating ? <CreateFleetDialog copy={copy} organizationId={organizationId} integrationId={integrationId}
      onClose={() => setCreating(false)} onCreated={id => { setCreating(false); select(id) }} /> : null}
  </WorkspaceSection>
}

function FleetCard({ copy, fleet, onOpen }: { copy: Copy; fleet: Fleet; onOpen: () => void }) {
  const s = fleet.summary
  return <article className="fleet-card">
    <button className="link-button" type="button" onClick={onOpen}><strong>{fleet.name}</strong></button>
    <p className="muted">{fleet.desiredRevisionNumber === null ? copy.noDesiredRevision
      : `${copy.desiredRevision}: ${fleet.desiredRevisionNumber}`}</p>
    <dl className="fleet-counts">
      <div><dt>{copy.members}</dt><dd>{s.totalNodes}</dd></div>
      <div><dt>{copy.complianceNames.COMPLIANT}</dt><dd>{s.compliant}</dd></div>
      <div><dt>{copy.complianceNames.DRIFTED}</dt><dd>{s.drifted}</dd></div>
      <div><dt>{copy.complianceNames.UNKNOWN}</dt><dd>{s.unknown}</dd></div>
      <div><dt>{copy.complianceNames.BLOCKED}</dt><dd>{s.blocked}</dd></div>
      <div><dt>{copy.healthNames.HEALTHY}</dt><dd>{s.healthy}</dd></div>
      <div><dt>{copy.healthNames.DEGRADED}</dt><dd>{s.degraded}</dd></div>
    </dl>
    <p className="muted">{copy.lastChecked}: {s.lastAssessmentAt
      ? new Date(s.lastAssessmentAt).toLocaleString() : copy.never}</p>
  </article>
}

function FleetDetailView({ copy, organizationId, integrationId, detail, canManage, canObserve, onBack }: {
  copy: Copy; organizationId: string; integrationId: string; detail: FleetDetail
  canManage: boolean; canObserve: boolean; onBack: () => void
}) {
  const fleet = detail.fleet
  const refresh = useRefreshFleet(organizationId, integrationId, fleet.id)
  const promote = usePromoteFleetRevision(organizationId, integrationId, fleet.id)
  const preview = usePreviewFleetRevision(organizationId, integrationId, fleet.id)
  const [impact, setImpact] = useState<FleetRevisionImpact | null>(null)
  const [revising, setRevising] = useState(false)
  const [adding, setAdding] = useState(false)
  const [member, setMember] = useState<FleetMember | null>(null)
  const desired = detail.desiredRevision

  return <WorkspaceSection title={`${copy.title}: ${fleet.name}`}
    description={desired ? `${copy.desiredRevision} ${desired.number}` : copy.noDesiredRevision}
    actions={<>
      <button className="secondary-button" type="button" onClick={onBack}>{copy.back}</button>
      {canManage ? <button className="secondary-button" type="button" onClick={() => setRevising(true)}>
        {copy.newRevision}</button> : null}
      {canObserve ? <button className="secondary-button" type="button" disabled={refresh.isPending}
        onClick={() => refresh.mutate()}>{copy.refresh}</button> : null}
    </>}>
    {refresh.data ? <InlineAlert tone="info" title={copy.refresh}>
      {copy.refreshed(refresh.data.membersDue)}</InlineAlert> : null}
    {promote.isSuccess ? <InlineAlert tone="info" title={copy.promote}>{copy.promoted}</InlineAlert> : null}
    {promote.isError || refresh.isError ? <InlineAlert tone="danger" title={copy.requestError}>
      {copy.requestError}</InlineAlert> : null}

    <FleetHealthSummary copy={copy} fleet={fleet} />

    {!fleet.archived ? <FleetRolloutPanel organizationId={organizationId} integrationId={integrationId}
      fleetId={fleet.id} desiredRevisionId={fleet.desiredRevisionId} members={detail.members}
      canControl={canObserve} /> : null}
    {!fleet.archived ? <NodeVersionsPanel organizationId={organizationId} integrationId={integrationId}
      fleetId={fleet.id} canManage={canManage} canControl={canObserve} /> : null}

    {desired ? <WorkspaceSection title={copy.desired}>
      <PropertyGrid columns={2} items={[
        { label: copy.serverProfile, value: `${detail.serverProfileName ?? ''} · ${copy.revision} ${desired.desiredConfiguration.serverProfileRevisionNumber}` },
        { label: copy.configProfile, value: `${detail.configurationProfileName ?? ''} · ${copy.revision} ${desired.desiredConfiguration.configRevisionNumber}` },
        { label: copy.port, value: String(desired.desiredConfiguration.nodePort) },
        { label: copy.cidrs, value: desired.desiredConfiguration.panelCidrs.join(', ') },
        { label: copy.inbounds, value: String(desired.desiredConfiguration.activeInboundIds.length) },
        { label: copy.desiredState, value: copy.stateNames[desired.desiredConfiguration.desiredNodeState] },
      ]} />
    </WorkspaceSection> : null}

    <WorkspaceSection title={copy.members} actions={canManage
      ? <button className="secondary-button" type="button" onClick={() => setAdding(true)}>{copy.addMember}</button>
      : undefined}>
      {detail.members.length === 0 ? <EmptyWorkspaceState title={copy.members} detail={copy.noCandidatesDetail} />
        : <div className="table-scroll"><table className="data-grid integration-grid">
          <thead><tr>
            <th>{copy.node}</th><th>{copy.server}</th><th>{copy.location}</th>
            <th>{copy.compliance}</th><th>{copy.health}</th><th>{copy.serverProfile}</th>
            <th>{copy.configProfile}</th><th>{copy.lastChecked}</th><th>{copy.rollout}</th>
          </tr></thead>
          <tbody>{detail.members.map(row => <tr key={row.membershipId}>
            <td><button className="link-button" type="button" onClick={() => setMember(row)}>{row.nodeName}</button></td>
            <td>{row.resourceName}</td>
            <td>{row.countryCode ?? '—'}</td>
            <td>{row.assessment
              ? <StatusIndicator label={copy.complianceNames[row.assessment.compliance]}
                tone={complianceTone(row.assessment.compliance)} />
              : <StatusIndicator label={copy.never} tone="neutral" />}</td>
            <td>{row.assessment
              ? <StatusIndicator label={copy.healthNames[row.assessment.health]}
                tone={healthTone(row.assessment.health)} />
              : <StatusIndicator label={copy.never} tone="neutral" />}</td>
            <td>{row.actualServerProfileName
              ? `${row.actualServerProfileName} · ${row.actualServerRevisionNumber ?? '—'}` : '—'}
              {desired ? ` → ${copy.revision} ${desired.desiredConfiguration.serverProfileRevisionNumber}` : ''}</td>
            <td>{row.actualConfigProfileName ?? '—'}
              {desired ? ` → ${detail.configurationProfileName ?? ''} ${desired.desiredConfiguration.configRevisionNumber}` : ''}</td>
            <td>{row.assessment ? new Date(row.assessment.computedAt).toLocaleString() : copy.never}</td>
            <td>{row.assessment
              ? (row.assessment.rolloutBlockers.length === 0 ? copy.eligible
                : row.assessment.rolloutBlockers.map(code => copy.blockerNames[code] ?? code).join('; '))
              : '—'}</td>
          </tr>)}</tbody></table></div>}
    </WorkspaceSection>

    <WorkspaceSection title={copy.history}>
      <div className="table-scroll"><table className="data-grid integration-grid">
        <thead><tr>
          <th>{copy.revision}</th><th>{copy.created}</th><th>{copy.serverProfile}</th>
          <th>{copy.configProfile}</th><th>{copy.port}</th><th>{copy.desiredState}</th><th></th>
        </tr></thead>
        <tbody>{detail.revisions.map(revision => <tr key={revision.id}>
          <td>{revision.number} {revision.desired
            ? <StatusIndicator label={copy.isDesired} tone="success" size="small" />
            : <span className="muted">{copy.notDesired}</span>}</td>
          <td>{new Date(revision.createdAt).toLocaleString()}</td>
          <td>{copy.revision} {revision.desiredConfiguration.serverProfileRevisionNumber}</td>
          <td>{copy.revision} {revision.desiredConfiguration.configRevisionNumber}</td>
          <td>{revision.desiredConfiguration.nodePort}</td>
          <td>{copy.stateNames[revision.desiredConfiguration.desiredNodeState]}</td>
          <td>{revision.desired ? null : <>
            <button className="secondary-button" type="button" disabled={preview.isPending}
              onClick={() => preview.mutateAsync(revision.id).then(setImpact).catch(() => setImpact(null))}>
              {copy.impact}</button>
            {canManage ? <button className="secondary-button" type="button" disabled={promote.isPending}
              onClick={() => promote.mutate({ revisionId: revision.id, expectedVersion: fleet.version })}>
              {copy.promote}</button> : null}
          </>}</td>
        </tr>)}</tbody></table></div>
    </WorkspaceSection>

    {impact ? <ImpactDialog copy={copy} impact={impact} onClose={() => setImpact(null)} /> : null}
    {revising && desired ? <RevisionDialog copy={copy} organizationId={organizationId}
      integrationId={integrationId} fleet={fleet} current={desired} onClose={() => setRevising(false)} /> : null}
    {adding ? <AddMemberDialog copy={copy} organizationId={organizationId} integrationId={integrationId}
      fleet={fleet} onClose={() => setAdding(false)} /> : null}
    {member ? <MemberDialog copy={copy} organizationId={organizationId} integrationId={integrationId}
      fleet={fleet} member={member} desired={desired} configProfileName={detail.configurationProfileName}
      canManage={canManage} onClose={() => setMember(null)} /> : null}
  </WorkspaceSection>
}

function FleetHealthSummary({ copy, fleet }: { copy: Copy; fleet: Fleet }) {
  const s = fleet.summary
  // Evidence that is older than the assessment itself is what makes the whole view out of date.
  const stale = s.oldestEvidenceAt !== null && s.assessed < s.totalNodes
  return <>
    {stale ? <InlineAlert tone="warning" title={copy.stale}>{copy.staleDetail}</InlineAlert> : null}
    <PropertyGrid columns={2} items={[
      { label: copy.members, value: String(s.totalNodes) },
      { label: copy.complianceNames.COMPLIANT, value: String(s.compliant) },
      { label: copy.complianceNames.DRIFTED, value: String(s.drifted) },
      { label: copy.complianceNames.UNKNOWN, value: String(s.unknown) },
      { label: copy.complianceNames.BLOCKED, value: String(s.blocked) },
      { label: copy.healthNames.HEALTHY, value: String(s.healthy) },
      { label: copy.healthNames.DEGRADED, value: String(s.degraded) },
      { label: copy.healthNames.UNKNOWN, value: String(s.healthUnknown) },
      { label: copy.lastChecked, value: s.lastAssessmentAt
        ? new Date(s.lastAssessmentAt).toLocaleString() : copy.never },
    ]} />
  </>
}

function ImpactDialog({ copy, impact, onClose }: { copy: Copy; impact: FleetRevisionImpact; onClose: () => void }) {
  const change = (before: string | number | null, after: string | number) =>
    before === null || String(before) === String(after) ? String(after) : `${before} → ${after}`
  return <IntegrationDialog title={copy.impact} onClose={onClose}
    actions={<button className="secondary-button" type="button" onClick={onClose}>{copy.close}</button>}>
    <p className="muted">{copy.impactDetail}</p>
    <PropertyGrid columns={2} items={[
      { label: copy.revision, value: change(impact.currentRevisionNumber, impact.candidateRevisionNumber) },
      { label: copy.serverProfile, value: change(impact.currentServerRevisionNumber, impact.candidateServerRevisionNumber) },
      { label: copy.configProfile, value: change(impact.currentConfigRevisionNumber, impact.candidateConfigRevisionNumber) },
      { label: copy.port, value: change(impact.currentNodePort, impact.candidateNodePort) },
      { label: copy.cidrs, value: change(impact.currentPanelCidrs?.length ?? null, impact.candidatePanelCidrs.length) },
      { label: copy.desiredState, value: change(
        impact.currentDesiredNodeState ? copy.stateNames[impact.currentDesiredNodeState] : null,
        copy.stateNames[impact.candidateDesiredNodeState]) },
      { label: copy.affected, value: String(impact.members) },
      { label: copy.compliantAfter, value: String(impact.compliantAfter) },
      { label: copy.expectedDrift, value: String(impact.expectedDrift) },
      { label: copy.unknownAfter, value: String(impact.unknown) },
      { label: copy.blockedAfter, value: String(impact.blocked) },
    ]} />
  </IntegrationDialog>
}

function MemberDialog({ copy, organizationId, integrationId, fleet, member, desired, configProfileName,
  canManage, onClose }: {
  copy: Copy; organizationId: string; integrationId: string; fleet: Fleet; member: FleetMember
  desired: FleetRevision | null; configProfileName: string | null; canManage: boolean; onClose: () => void
}) {
  const remove = useRemoveFleetMember(organizationId, integrationId, fleet.id)
  const assessment = member.assessment
  return <IntegrationDialog title={`${member.nodeName}: ${copy.memberDetail}`} onClose={onClose}
    busy={remove.isPending}
    actions={<>
      {canManage ? <button className="secondary-button" type="button" disabled={remove.isPending}
        onClick={() => remove.mutateAsync({ membershipId: member.membershipId, expectedVersion: fleet.version })
          .then(onClose).catch(() => undefined)}>{copy.remove}</button> : null}
      <button className="secondary-button" type="button" onClick={onClose}>{copy.close}</button>
    </>}>
    {!member.localManaged ? <InlineAlert tone="warning" title={copy.unmanaged}>
      {copy.unmanagedDetail}</InlineAlert> : null}
    <PropertyGrid columns={2} items={[
      { label: copy.server, value: member.resourceName },
      { label: copy.localManagement, value: member.localManaged ? copy.managed : copy.unmanaged },
      { label: copy.compliance, value: assessment ? copy.complianceNames[assessment.compliance] : copy.never },
      { label: copy.health, value: assessment ? copy.healthNames[assessment.health] : copy.never },
      { label: `${copy.serverProfile} · ${copy.actual}`, value: member.actualServerProfileName
        ? `${member.actualServerProfileName} · ${copy.revision} ${member.actualServerRevisionNumber ?? '—'}` : '—' },
      { label: `${copy.serverProfile} · ${copy.desired}`, value: desired
        ? `${copy.revision} ${desired.desiredConfiguration.serverProfileRevisionNumber}` : '—' },
      { label: `${copy.configProfile} · ${copy.actual}`, value: member.actualConfigProfileName ?? '—' },
      { label: `${copy.configProfile} · ${copy.desired}`, value: desired
        ? `${configProfileName ?? ''} · ${copy.revision} ${desired.desiredConfiguration.configRevisionNumber}` : '—' },
      { label: `${copy.inbounds} · ${copy.actual}`, value: member.actualInboundIds
        ? String(member.actualInboundIds.length) : '—' },
      { label: `${copy.inbounds} · ${copy.desired}`, value: desired
        ? String(desired.desiredConfiguration.activeInboundIds.length) : '—' },
      { label: `${copy.desiredState} · ${copy.actual}`, value: member.actualDesiredNodeState
        ? copy.stateNames[member.actualDesiredNodeState] : '—' },
      { label: `${copy.desiredState} · ${copy.desired}`, value: desired
        ? copy.stateNames[desired.desiredConfiguration.desiredNodeState] : '—' },
      { label: `${copy.port} · ${copy.desired}`, value: desired
        ? String(desired.desiredConfiguration.nodePort) : '—' },
      { label: `${copy.cidrs} · ${copy.desired}`, value: desired
        ? desired.desiredConfiguration.panelCidrs.join(', ') : '—' },
      { label: copy.lastChecked, value: assessment ? new Date(assessment.computedAt).toLocaleString() : copy.never },
    ]} />
    {assessment && assessment.driftReasons.length > 0 ? <section>
      <h4>{copy.compliance}</h4>
      <ul>{assessment.driftReasons.map(code =>
        <li key={code}>{copy.reasons[code] ?? code} <span className="muted">({code})</span></li>)}</ul>
    </section> : null}
    {assessment && assessment.healthReasons.length > 0 ? <section>
      <h4>{copy.health}</h4>
      <ul>{assessment.healthReasons.map(code =>
        <li key={code}>{copy.healthReasons[code] ?? code} <span className="muted">({code})</span></li>)}</ul>
    </section> : null}
    {assessment && assessment.rolloutBlockers.length > 0 ? <section>
      <h4>{copy.blockers}</h4>
      <ul>{assessment.rolloutBlockers.map(code =>
        <li key={code}>{copy.blockerNames[code] ?? code} <span className="muted">({code})</span></li>)}</ul>
    </section> : null}
    {canManage ? <p className="muted">{copy.removeDetail}</p> : null}
  </IntegrationDialog>
}

function AddMemberDialog({ copy, organizationId, integrationId, fleet, onClose }: {
  copy: Copy; organizationId: string; integrationId: string; fleet: Fleet; onClose: () => void
}) {
  const candidates = useFleetCandidates(organizationId, integrationId, true)
  const add = useAddFleetMember(organizationId, integrationId, fleet.id)
  const [node, setNode] = useState('')
  const eligible = candidates.data?.items.filter(item => item.eligible) ?? []
  return <IntegrationDialog title={copy.addMemberTitle} onClose={onClose} busy={add.isPending}
    actions={<>
      <button className="primary-button" type="button" disabled={!node || add.isPending}
        onClick={() => add.mutateAsync({ inventoryNodeId: node, expectedVersion: fleet.version })
          .then(onClose).catch(() => undefined)}>{copy.addMember}</button>
      <button className="secondary-button" type="button" onClick={onClose}>{copy.cancel}</button>
    </>}>
    {add.isError ? <InlineAlert tone="danger" title={copy.requestError}>{copy.requestError}</InlineAlert> : null}
    {eligible.length === 0 ? <EmptyWorkspaceState title={copy.noCandidates} detail={copy.noCandidatesDetail} />
      : <label>{copy.node}
        <select value={node} onChange={event => setNode(event.target.value)}>
          <option value="">—</option>
          {eligible.map(item => <option key={item.inventoryNodeId} value={item.inventoryNodeId}>
            {item.nodeName} · {item.resourceName}</option>)}
        </select>
      </label>}
    {(candidates.data?.items ?? []).filter(item => !item.eligible).length > 0 ? <ul className="muted">
      {(candidates.data?.items ?? []).filter(item => !item.eligible).map(item =>
        <li key={item.inventoryNodeId}>{item.nodeName}: {item.currentFleetName ?? item.blockedBy}</li>)}
    </ul> : null}
  </IntegrationDialog>
}

/** The desired configuration form, shared by creating a fleet and creating a later revision. */
function DesiredForm({ copy, organizationId, integrationId, value, onChange }: {
  copy: Copy; organizationId: string; integrationId: string
  value: FleetDesiredRequest; onChange: (next: FleetDesiredRequest) => void
}) {
  const profiles = useServerProfiles(organizationId, false, true)
  const options = useNodeOnboardingOptions(organizationId, integrationId, true)
  const inbounds = useMemo(() => options.data?.profiles.find(
    profile => profile.id === value.inventoryConfigProfileId)?.inbounds ?? [], [options.data, value.inventoryConfigProfileId])
  return <>
    <label>{copy.serverProfile}
      <select value={value.serverProfileId} onChange={event => onChange({ ...value,
        serverProfileId: event.target.value })}>
        <option value="">—</option>
        {(profiles.data?.items ?? []).map(profile => <option key={profile.profile.id} value={profile.profile.id}>
          {profile.profile.name}</option>)}
      </select>
    </label>
    <label>{`${copy.serverProfile} · ${copy.revision}`}
      <input type="number" min={1} value={value.serverProfileRevisionNumber}
        onChange={event => onChange({ ...value, serverProfileRevisionNumber: Number(event.target.value) })} />
    </label>
    <label>{copy.configProfile}
      <select value={value.inventoryConfigProfileId} onChange={event => onChange({ ...value,
        inventoryConfigProfileId: event.target.value, activeInboundIds: [] })}>
        <option value="">—</option>
        {(options.data?.profiles ?? []).map(profile => <option key={profile.id} value={profile.id}>
          {profile.name}</option>)}
      </select>
    </label>
    <label>{`${copy.configProfile} · ${copy.revision}`}
      <input type="number" min={1} value={value.configRevisionNumber}
        onChange={event => onChange({ ...value, configRevisionNumber: Number(event.target.value) })} />
    </label>
    <fieldset><legend>{copy.inbounds}</legend>
      {inbounds.map(inbound => <label key={inbound.id}>
        <input type="checkbox" checked={value.activeInboundIds.includes(inbound.id)}
          onChange={event => onChange({ ...value, activeInboundIds: event.target.checked
            ? [...value.activeInboundIds, inbound.id]
            : value.activeInboundIds.filter(id => id !== inbound.id) })} />
        {inbound.name}
      </label>)}
    </fieldset>
    <label>{copy.port}
      <input type="number" min={1} max={65535} value={value.nodePort}
        onChange={event => onChange({ ...value, nodePort: Number(event.target.value) })} />
    </label>
    <label>{copy.cidrs}
      <input value={value.panelCidrs.join(', ')} onChange={event => onChange({ ...value,
        panelCidrs: event.target.value.split(',').map(part => part.trim()).filter(Boolean) })} />
      <span className="muted">{copy.cidrHint}</span>
    </label>
    <label>{copy.desiredState}
      <select value={value.desiredNodeState} onChange={event => onChange({ ...value,
        desiredNodeState: event.target.value === 'DISABLED' ? 'DISABLED' : 'ENABLED' })}>
        <option value="ENABLED">{copy.stateNames.ENABLED}</option>
        <option value="DISABLED">{copy.stateNames.DISABLED}</option>
      </select>
    </label>
  </>
}

const emptyDesired: FleetDesiredRequest = { serverProfileId: '', serverProfileRevisionNumber: 1,
  inventoryConfigProfileId: '', configRevisionNumber: 1, activeInboundIds: [], nodePort: 2222,
  panelCidrs: [], desiredNodeState: 'ENABLED' }

function validDesired(value: FleetDesiredRequest) {
  return value.serverProfileId !== '' && value.inventoryConfigProfileId !== '' &&
    value.activeInboundIds.length > 0 && value.nodePort >= 1 && value.nodePort <= 65535 &&
    value.serverProfileRevisionNumber >= 1 && value.configRevisionNumber >= 1 &&
    value.panelCidrs.length > 0 && value.panelCidrs.every(validCidr)
}

function CreateFleetDialog({ copy, organizationId, integrationId, onClose, onCreated }: {
  copy: Copy; organizationId: string; integrationId: string; onClose: () => void
  onCreated: (id: string) => void
}) {
  const create = useCreateFleet(organizationId, integrationId)
  const candidates = useFleetCandidates(organizationId, integrationId, true)
  const [name, setName] = useState(''); const [code, setCode] = useState('')
  const [description, setDescription] = useState('')
  const [desired, setDesired] = useState<FleetDesiredRequest>(emptyDesired)
  const [members, setMembers] = useState<string[]>([])
  const valid = name.trim().length >= 2 && /^[a-z0-9][a-z0-9-]{1,62}$/.test(code) && validDesired(desired)
  return <IntegrationDialog title={copy.createTitle} onClose={onClose} busy={create.isPending}
    actions={<>
      <button className="primary-button" type="button" disabled={!valid || create.isPending}
        onClick={() => create.mutateAsync({ code, name: name.trim(),
          description: description.trim() === '' ? null : description.trim(),
          desiredConfiguration: desired, memberNodeIds: members })
          .then(fleet => onCreated(fleet.id)).catch(() => undefined)}>{copy.create}</button>
      <button className="secondary-button" type="button" onClick={onClose}>{copy.cancel}</button>
    </>}>
    {create.isError ? <InlineAlert tone="danger" title={copy.requestError}>{copy.requestError}</InlineAlert> : null}
    {!valid ? <p className="muted">{copy.formError}</p> : null}
    <label>{copy.name}<input value={name} onChange={event => setName(event.target.value)} /></label>
    <label>{copy.code}<input value={code} onChange={event => setCode(event.target.value)} /></label>
    <label>{copy.description}
      <input value={description} onChange={event => setDescription(event.target.value)} /></label>
    <DesiredForm copy={copy} organizationId={organizationId} integrationId={integrationId}
      value={desired} onChange={setDesired} />
    <fieldset><legend>{copy.selectMembers}</legend>
      {(candidates.data?.items ?? []).filter(item => item.eligible).map(item =>
        <label key={item.inventoryNodeId}>
          <input type="checkbox" checked={members.includes(item.inventoryNodeId)}
            onChange={event => setMembers(event.target.checked
              ? [...members, item.inventoryNodeId]
              : members.filter(id => id !== item.inventoryNodeId))} />
          {item.nodeName} · {item.resourceName}
        </label>)}
    </fieldset>
    <p className="muted">{copy.createdDetail}</p>
  </IntegrationDialog>
}

function RevisionDialog({ copy, organizationId, integrationId, fleet, current, onClose }: {
  copy: Copy; organizationId: string; integrationId: string; fleet: Fleet; current: FleetRevision
  onClose: () => void
}) {
  const create = useCreateFleetRevision(organizationId, integrationId, fleet.id)
  const [desired, setDesired] = useState<FleetDesiredRequest>({
    serverProfileId: current.desiredConfiguration.serverProfileId,
    serverProfileRevisionNumber: current.desiredConfiguration.serverProfileRevisionNumber,
    inventoryConfigProfileId: current.desiredConfiguration.inventoryConfigProfileId,
    configRevisionNumber: current.desiredConfiguration.configRevisionNumber,
    activeInboundIds: [...current.desiredConfiguration.activeInboundIds],
    nodePort: current.desiredConfiguration.nodePort,
    panelCidrs: [...current.desiredConfiguration.panelCidrs],
    desiredNodeState: current.desiredConfiguration.desiredNodeState,
  })
  return <IntegrationDialog title={copy.newRevision} onClose={onClose} busy={create.isPending}
    actions={<>
      <button className="primary-button" type="button" disabled={!validDesired(desired) || create.isPending}
        onClick={() => create.mutateAsync({ desiredConfiguration: desired, expectedVersion: fleet.version })
          .then(onClose).catch(() => undefined)}>{copy.newRevision}</button>
      <button className="secondary-button" type="button" onClick={onClose}>{copy.cancel}</button>
    </>}>
    {create.isError ? <InlineAlert tone="danger" title={copy.requestError}>{copy.requestError}</InlineAlert> : null}
    <p className="muted">{copy.impactDetail}</p>
    <DesiredForm copy={copy} organizationId={organizationId} integrationId={integrationId}
      value={desired} onChange={setDesired} />
  </IntegrationDialog>
}
