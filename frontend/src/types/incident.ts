export const IncidentStatus = {
  open: 'OPEN',
  resolved: 'RESOLVED',
} as const

export type KnownIncidentStatus = typeof IncidentStatus[keyof typeof IncidentStatus]

export interface IncidentResponse {
  id: string
  monitorRuleId: string
  resourceId: string
  status: string
  startedAt: string
  openedAt: string
  resolvedAt: string | null
  createdAt: string
  updatedAt: string
}
