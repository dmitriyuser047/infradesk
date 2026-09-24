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
  errorCode: string | null
  errorMessage: string | null
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
  ssh: {
    host: string
    port: number
    username: string
    hostKeyFingerprint: string | null
    credentialConfigured: boolean
    authenticationType: SshAuthenticationType
    hostTrusted: boolean
  } | null
}

export const SshAuthenticationType = {
  password: 'PASSWORD',
  privateKey: 'PRIVATE_KEY',
} as const

export type SshAuthenticationType =
  (typeof SshAuthenticationType)[keyof typeof SshAuthenticationType]

/** What the browser submits. The server never sends any of this back. */
export type SshCredentialsRequest =
  | { type: 'PASSWORD'; password: string }
  | { type: 'PRIVATE_KEY'; privateKey: string; passphrase?: string }

export interface SaveSshConnectionRequest {
  connectorType: 'SSH'
  code: string
  name: string
  scope: ConnectionScopeResponse
  ssh: {
    host: string
    port: number
    username: string
    authenticationType: SshAuthenticationType
    hostKeyFingerprint?: string
  }
  credentials?: SshCredentialsRequest
  schedule: { enabled: boolean; intervalSeconds: number }
}
