import type { I18n } from '../../i18n'
import { describeFailure } from '../../i18n/errors'
import { getIncidentReasonLabel, getOperationLabel } from '../history/historyEventPresentation'
import type { StatusTone } from '../layout/WorkspacePrimitives'
import { getMetricLabel } from '../monitoring/monitorRulePresentation'
import type { AttentionItemResponse, OverviewSummaryResponse, OperationsOverviewResponse } from '../../types/overview'

export interface OverviewHealthPresentation {
  state: 'normal' | 'warning' | 'empty' | 'unknown'
  tone: StatusTone
  title: string
  detail: string
}

/** Words only the facts of this scope. No metric evaluation or invented critical severity. */
export function getOverviewHealth(overview: OperationsOverviewResponse, i18n: I18n): OverviewHealthPresentation {
  const t = i18n.t.overview.health
  const { nodes, containers, connections, incidents, operations } = overview.summary
  const problems = [
    nodes.offline > 0 ? t.offline(nodes.offline) : null,
    incidents.open > 0 ? t.incidents(incidents.open) : null,
    connections.failing > 0 ? t.syncFailures(connections.failing) : null,
    operations.failed > 0 ? t.failedOperations(operations.failed, overview.operationsHorizonHours) : null,
    operations.unknown > 0 ? t.unknownOperations(operations.unknown, overview.operationsHorizonHours) : null,
  ].filter(Boolean)
  if (problems.length > 0 || overview.attention.total > 0 || overview.attention.items.length > 0) {
    return { state: 'warning', tone: 'warning', title: t.warning, detail: problems.join(' · ') || t.review }
  }
  if (isEmptyInfrastructure(overview.summary)) {
    return { state: 'empty', tone: 'neutral', title: t.empty, detail: t.emptyDetail }
  }
  const inventoryMissing = nodes.total + containers.total === 0
  const statusIncomplete = nodes.online < nodes.total || containers.running < containers.total ||
    connections.healthy < connections.total
  if (inventoryMissing || statusIncomplete) {
    return { state: 'unknown', tone: 'neutral', title: t.unknown,
      detail: connections.neverSynced > 0 ? t.notSynchronized(connections.neverSynced) : t.unknownDetail }
  }
  const detail = [nodes.total > 0 ? t.online(nodes.online) : null,
    containers.total > 0 ? t.running(containers.running) : null, t.noIncidents].filter(Boolean).join(' · ')
  return { state: 'normal', tone: 'success', title: t.normal, detail }
}

export interface AttentionPresentation {
  title: string
  subject: string
  detail: string | null
  status: string
  tone: StatusTone
  to: string | null
}

/**
 * How one current problem reads and where it leads.
 *
 * The backend sends typed facts in priority order; this only words them. A result the server does
 * not know stays "unknown" and is never shown as a failure.
 */
export function getAttentionPresentation(organizationId: string, item: AttentionItemResponse, i18n: I18n): AttentionPresentation {
  const t = i18n.t.overview.items
  const base = `/organizations/${encodeURIComponent(organizationId)}`
  const resourcePath = item.resource === null ? null
    : `${base}/environments/${encodeURIComponent(item.resource.environmentId)}/resources/${encodeURIComponent(item.resource.id)}`
  const resourceName = item.resource?.name ?? t.unknownResource

  switch (item.kind) {
    case 'OPERATION_UNKNOWN':
      return {
        title: t.unknownTitle(operationLabel(item, i18n)),
        subject: resourceName,
        detail: t.unknownDetail,
        status: t.unknownStatus,
        tone: 'warning',
        to: resourcePath,
      }
    case 'INCIDENT':
      return {
        title: getIncidentReasonLabel(item.incident?.reason ?? '', i18n.t),
        subject: resourceName,
        detail: item.incident === null ? null : getMetricLabel(item.incident.metricCode, i18n),
        status: t.incidentStatus,
        tone: item.incident?.reason === 'NO_DATA' ? 'warning' : 'danger',
        to: `${base}/incidents/${encodeURIComponent(item.id)}`,
      }
    case 'NODE_OFFLINE':
      return {
        title: t.nodeOfflineTitle,
        subject: resourceName,
        detail: t.nodeOfflineDetail,
        status: t.nodeOfflineStatus,
        tone: 'danger',
        to: resourcePath,
      }
    case 'SYNC_FAILED': {
      const code = item.sync?.errorCode ?? ''
      const connectionPath = item.connection === null ? null
        : `${base}/connections/${encodeURIComponent(item.connection.id)}`
      return {
        title: t.syncTitles[code] ?? i18n.t.connections.syncFailed,
        subject: item.connection?.name ?? t.unknownConnection,
        detail: describeFailure(item.sync?.errorCode, item.sync?.errorMessage, i18n, i18n.t.connections.syncFailed),
        status: t.syncStatus,
        tone: 'danger',
        to: connectionPath,
      }
    }
    case 'OPERATION_FAILED':
      return {
        title: t.failedTitle(operationLabel(item, i18n)),
        subject: resourceName,
        detail: describeFailure(item.operation?.errorCode, item.operation?.errorMessage, i18n,
          i18n.t.errors.codes.OPERATION_EXECUTION_FAILED),
        status: t.failedStatus,
        tone: 'danger',
        to: resourcePath,
      }
    default:
      return { title: item.kind, subject: resourceName, detail: null, status: t.needsAttention, tone: 'neutral', to: resourcePath }
  }
}

function operationLabel(item: AttentionItemResponse, i18n: I18n): string {
  return item.operation === null ? i18n.t.overview.items.operation : getOperationLabel(item.operation.operationCode, i18n.t)
}

export interface SummaryCard {
  id: 'nodes' | 'containers' | 'incidents' | 'connections' | 'operations'
  label: string
  value: string
  detail: string
  tone: StatusTone
  to: string | null
}

/**
 * The fleet summary, card by card. Cards lead to the existing page of the same objects; none
 * of them invents a filter those pages do not have.
 */
export function getSummaryCards(
  summary: OverviewSummaryResponse,
  links: { infrastructure: string | null; incidents: string; connections: string },
  operationsHorizonHours: number,
  i18n: I18n,
): SummaryCard[] {
  const t = i18n.t.overview.cards
  const count = i18n.format.number
  const { nodes, containers, incidents, connections, operations } = summary
  const operationProblems = operations.failed + operations.unknown
  return [
    {
      id: 'nodes', label: t.nodes, value: count(nodes.total),
      detail: nodes.total === 0 ? t.nothing
        : nodes.offline === 0 && nodes.online === nodes.total ? t.allOnline
          : t.nodesDetail(nodes.online, nodes.offline),
      tone: nodes.offline > 0 ? 'danger' : 'neutral', to: links.infrastructure,
    },
    {
      id: 'containers', label: t.containers, value: count(containers.total),
      detail: containers.total === 0 ? t.nothing : t.containersDetail(containers.running, containers.stopped),
      tone: 'neutral', to: links.infrastructure,
    },
    {
      id: 'incidents', label: t.incidents, value: count(incidents.open),
      detail: incidents.open === 0 ? t.noIncidents : t.incidentsDetail(count(incidents.threshold), count(incidents.noData)),
      tone: incidents.open > 0 ? 'warning' : 'neutral', to: links.incidents,
    },
    {
      id: 'connections', label: t.connections, value: count(connections.total),
      detail: connections.failing > 0 ? t.connectionsFailing(count(connections.failing))
        : connections.neverSynced > 0 ? t.connectionsNeverSynced(count(connections.neverSynced))
          : connections.total === 0 ? t.connectionsNone : t.connectionsOk,
      tone: connections.failing > 0 ? 'warning' : 'neutral', to: links.connections,
    },
    {
      id: 'operations', label: t.operations(operationsHorizonHours), value: count(operationProblems),
      detail: operationProblems === 0 ? t.operationsOk : t.operationsDetail(count(operations.unknown), count(operations.failed)),
      tone: operations.unknown > 0 ? 'warning' : operations.failed > 0 ? 'danger' : 'neutral', to: null,
    },
  ]
}

/** No inventory yet: nothing has been discovered and no connection has been added. */
export function isEmptyInfrastructure(summary: OverviewSummaryResponse): boolean {
  return summary.nodes.total === 0 && summary.containers.total === 0 && summary.connections.total === 0
}
