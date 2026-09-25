import type { ConnectionScopeResponse } from './connection'
import type { HistoryEventResponse } from './historyEvent'

export interface OverviewSummaryResponse {
  nodes: { total: number; online: number; offline: number }
  containers: { total: number; running: number; stopped: number }
  connections: { total: number; healthy: number; failing: number; neverSynced: number }
  incidents: { open: number; threshold: number; noData: number }
  operations: { failed: number; unknown: number }
}

export type AttentionKind =
  | 'OPERATION_UNKNOWN'
  | 'INCIDENT'
  | 'NODE_OFFLINE'
  | 'SYNC_FAILED'
  | 'OPERATION_FAILED'

export interface OverviewResourceResponse {
  id: string
  name: string
  resourceTypeCode: string
  environmentId: string
}

/** One current problem. `kind` says which of the optional parts are present. */
export interface AttentionItemResponse {
  kind: AttentionKind
  priority: number
  id: string
  occurredAt: string
  resource: OverviewResourceResponse | null
  connection: { id: string; name: string } | null
  incident: { reason: string; metricCode: string } | null
  operation: { operationCode: string; errorCode: string | null; errorMessage: string | null } | null
  sync: { errorCode: string | null; errorMessage: string | null } | null
}

export interface OperationsOverviewResponse {
  scope: ConnectionScopeResponse
  summary: OverviewSummaryResponse
  attention: { items: AttentionItemResponse[]; total: number }
  recentActivity: HistoryEventResponse[]
  operationsHorizonHours: number
}
