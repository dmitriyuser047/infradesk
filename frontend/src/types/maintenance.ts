export const MaintenanceWindowState = {
  scheduled: 'SCHEDULED',
  active: 'ACTIVE',
  finished: 'FINISHED',
  cancelled: 'CANCELLED',
} as const

export interface MaintenanceWindowResponse {
  id: string
  resource: { id: string; name: string; resourceTypeCode: string }
  startsAt: string
  endsAt: string
  /** The end, or the cancellation when that came first. */
  effectiveEnd: string
  state: string
  reason: string
  createdByName: string
  createdAt: string
  cancelledAt: string | null
  cancelledByName: string | null
}

export interface MaintenanceWindowListResponse {
  now: string
  windows: MaintenanceWindowResponse[]
}

export interface CreateMaintenanceWindowRequest {
  resourceId: string
  startsAt: string
  endsAt: string
  reason: string
}
