export type HistoryEventType =
  | 'RESOURCE_DISCOVERED'
  | 'RESOURCE_DEACTIVATED'
  | 'INCIDENT_OPENED'
  | 'INCIDENT_RESOLVED'
  | 'OPERATION_REQUESTED'
  | 'OPERATION_SUCCEEDED'
  | 'OPERATION_FAILED'
  | 'OPERATION_UNKNOWN'
  | 'SYNC_FAILED'

export type HistoryEventSource = 'USER' | 'SYSTEM'

export interface HistoryEventResponse {
  id: string
  eventType: HistoryEventType
  source: HistoryEventSource
  occurredAt: string
  resource: { id: string; name: string; resourceTypeCode: string } | null
  connection: { id: string; name: string } | null
  actor: { id: string; displayName: string } | null
  incident: { id: string; status: string; reason: string; monitorRuleId: string } | null
  operation: {
    id: string
    operationCode: string
    status: string
    errorCode: string | null
    errorMessage: string | null
  } | null
  sync: { id: string; status: string; errorCode: string | null } | null
}
