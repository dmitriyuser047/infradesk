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
