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

export function getHistoryActorLabel(event: HistoryEventResponse): string {
  return event.actor === null ? 'System' : event.actor.displayName
}

function title(event: HistoryEventResponse): string {
  const base = titles[event.eventType] ?? event.eventType
  const operation = event.operation === null ? null : operationLabels[event.operation.operationCode]

  return operation === null || operation === undefined ? base : `${operation}: ${base.toLowerCase()}`
}

function detail(event: HistoryEventResponse): string | null {
  if (event.operation?.errorMessage != null) {
    return event.operation.errorMessage
  }

  if (event.incident !== null) {
    return incidentReasons[event.incident.reason] ?? event.incident.reason
  }

  if (event.sync?.errorCode != null) {
    return `Error code ${event.sync.errorCode}`
  }

  return null
}
