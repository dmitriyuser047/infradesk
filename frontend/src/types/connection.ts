export const ConnectionScopeType = {
  organization: 'ORGANIZATION',
  project: 'PROJECT',
  environment: 'ENVIRONMENT',
} as const

export interface OrganizationConnectionScopeResponse {
  type: typeof ConnectionScopeType.organization
}

export interface ProjectConnectionScopeResponse {
  type: typeof ConnectionScopeType.project
  projectId: string
}

export interface EnvironmentConnectionScopeResponse {
  type: typeof ConnectionScopeType.environment
  projectId: string
  environmentId: string
}

export type ConnectionScopeResponse =
  | OrganizationConnectionScopeResponse
  | ProjectConnectionScopeResponse
  | EnvironmentConnectionScopeResponse

export const SyncStatus = {
  running: 'RUNNING',
  completed: 'COMPLETED',
  failed: 'FAILED',
} as const

export interface SyncSessionResponse {
  id: string
  status: string
  startedAt: string
  finishedAt: string | null
}

export interface ConnectionScheduleResponse {
  enabled: boolean
  intervalSeconds: number
  nextRunAt: string
}

export interface ConnectionResponse {
  id: string
  connectorType: string
  code: string
  name: string
  scope: ConnectionScopeResponse
  active: boolean
  schedule: ConnectionScheduleResponse | null
  lastSync: SyncSessionResponse | null
  createdAt: string
  updatedAt: string
}
