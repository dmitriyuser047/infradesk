import type { HistoryEventResponse, HistoryEventType } from '../../types/historyEvent'

export type HistoryTone = 'neutral' | 'positive' | 'warning' | 'critical'

export interface HistoryEventPresentation {
  title: string
  detail: string | null
  tone: HistoryTone
}

const titles: Record<HistoryEventType, string> = {
  RESOURCE_DISCOVERED: 'Resource discovered',
  RESOURCE_DEACTIVATED: 'Resource deactivated',
  INCIDENT_OPENED: 'Incident opened',
  INCIDENT_RESOLVED: 'Incident resolved',
  OPERATION_REQUESTED: 'Operation requested',
  OPERATION_SUCCEEDED: 'Operation succeeded',
  OPERATION_FAILED: 'Operation failed',
  OPERATION_UNKNOWN: 'Operation result unknown',
  SYNC_FAILED: 'Synchronization failed',
}

const tones: Record<HistoryEventType, HistoryTone> = {
  RESOURCE_DISCOVERED: 'neutral',
  RESOURCE_DEACTIVATED: 'warning',
  INCIDENT_OPENED: 'critical',
  INCIDENT_RESOLVED: 'positive',
  OPERATION_REQUESTED: 'neutral',
  OPERATION_SUCCEEDED: 'positive',
  OPERATION_FAILED: 'critical',
  OPERATION_UNKNOWN: 'warning',
  SYNC_FAILED: 'critical',
}

const operationLabels: Record<string, string> = {
  CONTAINER_START: 'Start',
  CONTAINER_STOP: 'Stop',
  CONTAINER_RESTART: 'Restart',
}

const incidentReasons: Record<string, string> = {
  THRESHOLD: 'Metric threshold exceeded',
  NO_DATA: 'No metrics received',
}

/**
 * One place where an event type becomes something to read.
 *
 * The backend sends typed references and safe codes only, so the timeline never displays a
 * message the server rendered, and never a raw technical detail.
 */
export function getHistoryEventPresentation(event: HistoryEventResponse): HistoryEventPresentation {
  return {
    title: title(event),
    detail: detail(event),
    tone: tones[event.eventType] ?? 'neutral',
  }
}

/** How an operation code reads, wherever an operation is mentioned. */
export function getOperationLabel(operationCode: string): string {
  return operationLabels[operationCode] ?? operationCode
}

/** How an incident reason reads, wherever an incident is mentioned. */
export function getIncidentReasonLabel(reason: string): string {
  return incidentReasons[reason] ?? reason
}

export function getHistoryActorLabel(event: HistoryEventResponse): string {
  return event.actor === null ? 'System' : event.actor.displayName
}

function title(event: HistoryEventResponse): string {
  const base = titles[event.eventType] ?? event.eventType
  const operation = event.operation === null ? null : operationLabels[event.operation.operationCode]

  return operation === null || operation === undefined ? base : `${operation}: ${base.toLowerCase()}`
}

/**
 * Only the events that report an outcome may show one.
 *
 * The execution an entry points at keeps changing after the entry was written, so a request
 * recorded at 15:40 must not display the failure its execution reached at 15:41.
 */
const outcomeEvents: ReadonlySet<HistoryEventType> = new Set(['OPERATION_FAILED', 'OPERATION_UNKNOWN'])

function detail(event: HistoryEventResponse): string | null {
  if (outcomeEvents.has(event.eventType) && event.operation?.errorMessage != null) {
    return event.operation.errorMessage
  }

  if (event.incident !== null) {
    return getIncidentReasonLabel(event.incident.reason)
  }

  if (event.sync?.errorCode != null) {
    return `Error code ${event.sync.errorCode}`
  }

  return null
}

/** The object an entry is about, as a link to its existing page. */
export function getHistorySubjectLink(
  organizationId: string,
  event: HistoryEventResponse,
): { label: string; to: string } | null {
  const base = `/organizations/${encodeURIComponent(organizationId)}`
  if (event.resource !== null) {
    return {
      label: event.resource.name,
      to: `${base}/environments/${encodeURIComponent(event.resource.environmentId)}` +
        `/resources/${encodeURIComponent(event.resource.id)}`,
    }
  }
  if (event.connection !== null) {
    return { label: event.connection.name, to: `${base}/connections/${encodeURIComponent(event.connection.id)}` }
  }
  return null
}
