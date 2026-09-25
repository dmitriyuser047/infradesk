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

/** An incident as the list returns it: complete for its row, with no further request per row. */
export interface IncidentListItemResponse extends IncidentResponse {
  resource: IncidentResourceReference
}
