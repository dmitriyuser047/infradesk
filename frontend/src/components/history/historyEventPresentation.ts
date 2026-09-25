import type { I18n, Messages } from '../../i18n'
import { describeFailure } from '../../i18n/errors'
import type { HistoryEventResponse, HistoryEventType } from '../../types/historyEvent'

export type HistoryTone = 'neutral' | 'positive' | 'warning' | 'critical'

export interface HistoryEventPresentation {
  title: string
  detail: string | null
  tone: HistoryTone
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

/**
 * One place where an event type becomes something to read, in the active language.
 *
 * The backend sends typed references and safe codes only, so the timeline never displays a
 * message the server rendered, and never a raw technical detail.
 */
export function getHistoryEventPresentation(event: HistoryEventResponse, i18n: I18n): HistoryEventPresentation {
  return {
    title: title(event, i18n.t),
    detail: detail(event, i18n),
    tone: tones[event.eventType] ?? 'neutral',
  }
}

/** How an operation code reads, wherever an operation is mentioned. */
export function getOperationLabel(operationCode: string, t: Messages): string {
  return t.operations.labels[operationCode] ?? operationCode
}

/** How an incident reason reads as a sentence, wherever an incident is described. */
export function getIncidentReasonLabel(reason: string, t: Messages): string {
  return t.incidents.reasonDetails[reason] ?? reason
}

export function getHistoryActorLabel(event: HistoryEventResponse, i18n: I18n): string {
  return event.actor === null ? i18n.t.history.system : event.actor.displayName
}

function title(event: HistoryEventResponse, t: Messages): string {
  const base = t.history.events[event.eventType] ?? event.eventType
  return event.operation === null ? base : t.history.withOperation(getOperationLabel(event.operation.operationCode, t), base)
}

/**
 * Only the events that report an outcome may show one.
 *
 * The execution an entry points at keeps changing after the entry was written, so a request
 * recorded at 15:40 must not display the failure its execution reached at 15:41.
 */
const outcomeEvents: ReadonlySet<HistoryEventType> = new Set(['OPERATION_FAILED', 'OPERATION_UNKNOWN'])

function detail(event: HistoryEventResponse, i18n: I18n): string | null {
  if (outcomeEvents.has(event.eventType) && event.operation !== null &&
    (event.operation.errorCode !== null || event.operation.errorMessage !== null)) {
    return describeFailure(event.operation.errorCode, event.operation.errorMessage, i18n,
      i18n.t.history.events[event.eventType] ?? event.eventType)
  }

  if (event.incident !== null) {
    return getIncidentReasonLabel(event.incident.reason, i18n.t)
  }

  if (event.sync?.errorCode != null) {
    return describeFailure(event.sync.errorCode, null, i18n, i18n.t.history.errorCode(event.sync.errorCode))
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
