import type { EnvironmentReference, ProjectReference, ResourceReference, SourceConnection } from './infrastructure'

export const IncidentStatus = {
  open: 'OPEN',
  resolved: 'RESOLVED',
} as const

export type KnownIncidentStatus = typeof IncidentStatus[keyof typeof IncidentStatus]

export const IncidentReason = {
  threshold: 'THRESHOLD',
  noData: 'NO_DATA',
} as const

export type KnownIncidentReason = typeof IncidentReason[keyof typeof IncidentReason]

export interface IncidentResponse {
  id: string
  monitorRuleId: string
  resourceId: string
  status: string
  reason: string
  startedAt: string
  openedAt: string
  resolvedAt: string | null
  createdAt: string
  updatedAt: string
}

/** The resource an incident is about: identity only, enough to name and link it in a list. */
export interface IncidentResourceReference {
  id: string
  name: string
  resourceTypeCode: string
}

/** The typed condition of the rule that opened an incident. */
export interface MonitorCondition {
  id: string
  metricCode: string
  operator: string
  threshold: number
  forSeconds: number
  noDataSeconds: number
}

/**
 * An incident as every list and the detail return it: complete for its row or page — its resource,
 * where that resource lives, the rule and every source connection — with no further request.
 */
export interface IncidentListItemResponse extends IncidentResponse {
  resource: IncidentResourceReference
  project: ProjectReference
  environment: EnvironmentReference
  monitorRule: MonitorCondition
  parentResource: ResourceReference | null
  sourceConnections: SourceConnection[]
}
