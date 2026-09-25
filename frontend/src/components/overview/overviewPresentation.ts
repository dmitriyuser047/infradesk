import { getSyncFailureMessage } from '../connections/connectionPresentation'
import { getIncidentReasonLabel, getOperationLabel } from '../history/historyEventPresentation'
import type { StatusTone } from '../layout/WorkspacePrimitives'
import { getMetricLabel } from '../monitoring/monitorRulePresentation'
import type { AttentionItemResponse, OverviewSummaryResponse } from '../../types/overview'

export interface AttentionPresentation {
  title: string
  subject: string
  detail: string | null
  status: string
  tone: StatusTone
  to: string | null
}

const syncFailureTitles: Record<string, string> = {
  SSH_HOST_KEY_MISMATCH: 'Host identity changed',
  SSH_HOST_KEY_NOT_TRUSTED: 'Host key not trusted',
  SSH_AUTH_FAILED: 'SSH authentication failed',
  SSH_AUTHENTICATION_FAILED: 'SSH authentication failed',
  SSH_CONNECTION_REFUSED: 'SSH connection refused',
  SSH_CONNECT_TIMEOUT: 'Host unreachable',
}

/**
 * How one current problem reads and where it leads.
 *
 * The backend sends typed facts in priority order; this only words them. A result the server does
 * not know stays "unknown" and is never shown as a failure.
 */
export function getAttentionPresentation(organizationId: string, item: AttentionItemResponse): AttentionPresentation {
  const base = `/organizations/${encodeURIComponent(organizationId)}`
  const resourcePath = item.resource === null ? null
    : `${base}/environments/${encodeURIComponent(item.resource.environmentId)}/resources/${encodeURIComponent(item.resource.id)}`
  const resourceName = item.resource?.name ?? 'Unknown resource'

  switch (item.kind) {
    case 'OPERATION_UNKNOWN':
      return {
        title: `${operationLabel(item)}: result unknown`,
        subject: resourceName,
        detail: 'The command may or may not have run. Check the container before retrying.',
        status: 'Result unknown',
        tone: 'warning',
        to: resourcePath,
      }
    case 'INCIDENT':
      return {
        title: getIncidentReasonLabel(item.incident?.reason ?? ''),
        subject: resourceName,
        detail: item.incident === null ? null : getMetricLabel(item.incident.metricCode),
        status: 'Incident open',
        tone: 'danger',
        to: `${base}/incidents/${encodeURIComponent(item.id)}`,
      }
    case 'NODE_OFFLINE':
      return {
        title: 'Node offline',
        subject: resourceName,
        detail: 'Reported offline by the latest inventory',
        status: 'Offline',
        tone: 'danger',
        to: resourcePath,
      }
    case 'SYNC_FAILED': {
      const code = item.sync?.errorCode ?? ''
      const connectionPath = item.connection === null ? null
        : `${base}/connections/${encodeURIComponent(item.connection.id)}`
      return {
        title: syncFailureTitles[code] ?? 'Synchronization failed',
        subject: item.connection?.name ?? 'Unknown connection',
        detail: getSyncFailureMessage(item.sync?.errorMessage ?? null),
        status: 'Sync failed',
        tone: 'danger',
        to: connectionPath === null ? null : `${connectionPath}/sync-sessions/${encodeURIComponent(item.id)}`,
      }
    }
    case 'OPERATION_FAILED':
      return {
        title: `${operationLabel(item)}: operation failed`,
        subject: resourceName,
        detail: item.operation?.errorMessage?.trim() || 'Operation failed',
        status: 'Failed',
        tone: 'danger',
        to: resourcePath,
      }
    default:
      return { title: item.kind, subject: resourceName, detail: null, status: 'Needs attention', tone: 'neutral', to: resourcePath }
  }
}

function operationLabel(item: AttentionItemResponse): string {
  return item.operation === null ? 'Operation' : getOperationLabel(item.operation.operationCode)
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
): SummaryCard[] {
  const { nodes, containers, incidents, connections, operations } = summary
  const operationProblems = operations.failed + operations.unknown
  return [
    {
      id: 'nodes', label: 'Nodes', value: count(nodes.total),
      detail: join([`${count(nodes.online)} online`, `${count(nodes.offline)} offline`]),
      tone: nodes.offline > 0 ? 'danger' : 'neutral', to: links.infrastructure,
    },
    {
      id: 'containers', label: 'Containers', value: count(containers.total),
      detail: join([`${count(containers.running)} running`, `${count(containers.stopped)} stopped`]),
      tone: 'neutral', to: links.infrastructure,
    },
    {
      id: 'incidents', label: 'Open incidents', value: count(incidents.open),
      detail: incidents.open === 0 ? 'None open'
        : join([`${count(incidents.threshold)} threshold`, `${count(incidents.noData)} no data`]),
      tone: incidents.open > 0 ? 'danger' : 'success', to: links.incidents,
    },
    {
      id: 'connections', label: 'Connections', value: count(connections.total),
      detail: connections.failing > 0 ? `${count(connections.failing)} failing`
        : connections.neverSynced > 0 ? `${count(connections.neverSynced)} never synchronized`
          : connections.total === 0 ? 'None configured' : 'All synchronized',
      tone: connections.failing > 0 ? 'danger' : 'neutral', to: links.connections,
    },
    {
      id: 'operations', label: `Operations · ${operationsHorizonHours}h`, value: count(operationProblems),
      detail: operationProblems === 0 ? 'No problems'
        : join([`${count(operations.unknown)} unknown`, `${count(operations.failed)} failed`]),
      tone: operations.unknown > 0 ? 'warning' : operations.failed > 0 ? 'danger' : 'neutral', to: null,
    },
  ]
}

/** No inventory yet: nothing has been discovered and no connection has been added. */
export function isEmptyInfrastructure(summary: OverviewSummaryResponse): boolean {
  return summary.nodes.total === 0 && summary.containers.total === 0 && summary.connections.total === 0
}

function count(value: number): string {
  return value.toLocaleString()
}

function join(parts: string[]): string {
  return parts.join(' · ')
}
