export interface IntegrationResponse {
  id: string
  name: string
  providerType: 'REMNAWAVE'
  baseUrl: string
  enabled: boolean
  credential: { apiTokenConfigured: boolean; caddyApiKeyConfigured: boolean }
  createdAt: string
  updatedAt: string
  /** Present on list and detail responses; create and update responses do not carry it. */
  overview?: IntegrationOverview
}

export interface IntegrationProviderResponse {
  type: 'REMNAWAVE'
  displayName: string
  capabilities: string[]
}

export interface IntegrationTestResponse { ok: boolean; providerType: 'REMNAWAVE'; latencyMs: number }
export interface IntegrationCredentials { apiToken: string; caddyApiKey: string | null }
export interface CreateIntegrationRequest { name: string; providerType: 'REMNAWAVE'; baseUrl: string; credentials: IntegrationCredentials }
export interface UpdateIntegrationRequest { name: string; baseUrl: string; credentials?: IntegrationCredentials }

export type IntegrationSyncStatus = 'RUNNING' | 'COMPLETED' | 'FAILED'
export interface IntegrationSyncSession {
  id: string
  integrationId: string
  trigger: 'MANUAL' | 'SCHEDULED'
  status: IntegrationSyncStatus
  startedAt: string
  finishedAt: string | null
  errorCode: string | null
  errorMessage: string | null
  counts: { nodes: number; hosts: number; configProfiles: number; deactivated: number } | null
}
export type IntegrationActionCode = 'NODE_ENABLE' | 'NODE_DISABLE' | 'NODE_RESTART'
export type IntegrationActionStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'UNKNOWN'
export interface IntegrationActionExecution {
  id: string; requestId: string; integrationId: string; inventoryObjectId: string
  displayName: string; requestedByUserId: string; requestedByName: string | null; action: IntegrationActionCode
  status: IntegrationActionStatus; createdAt: string; startedAt: string | null
  finishedAt: string | null; errorCode: string | null
}
export interface InventoryTypeCounts { active: number; inactive: number }
export interface IntegrationOverview {
  lastSync: IntegrationSyncSession | null
  lastSuccessfulSyncAt: string | null
  nextRunAt: string | null
  inventory: { nodes: InventoryTypeCounts; hosts: InventoryTypeCounts; configProfiles: InventoryTypeCounts }
}

export type RemnawaveNodeState = 'CONNECTED' | 'CONNECTING' | 'DISCONNECTED' | 'DISABLED'
export interface RemnawaveNodeSummary {
  address: string; port: number | null; state: RemnawaveNodeState
  isConnected: boolean; isConnecting: boolean; isDisabled: boolean; lastStatusChange: string | null
  xrayVersion: string | null; nodeVersion: string | null; xrayUptimeSeconds: number
  trafficTrackingActive: boolean; trafficLimitBytes: number | null; trafficUsedBytes: number | null
  usersOnline: number; countryCode: string; cpuCount: number | null; cpuModel: string | null
  memoryTotalBytes: number | null; activeConfigProfileUuid: string | null; tags: string[]
  providerUuid: string | null; providerName: string | null
}
export interface RemnawaveHostSummary {
  address: string; port: number; isDisabled: boolean; isHidden: boolean
  configProfileUuid: string | null; configProfileInboundUuid: string | null; nodeUuids: string[]; tags: string[]
  securityLayer: string; serverDescription: string | null
}
export interface RemnawaveInboundSummary {
  uuid: string; tag: string; type: string; network: string | null; security: string | null; port: number | null
}
export interface RemnawaveConfigProfileSummary {
  viewPosition: number; createdAt: string; updatedAt: string
  nodeUuids: string[]; inbounds: RemnawaveInboundSummary[]
}
export interface NamedRef { id: string; name: string }
export interface BoundResource { resource: { id: string; code: string; name: string }; environment: NamedRef; project: NamedRef }
export interface InventoryObject<S> {
  id: string
  objectType: 'NODE' | 'HOST' | 'CONFIG_PROFILE'
  externalId: string
  displayName: string
  active: boolean
  firstSeenAt: string
  lastSeenAt: string
  summary: S
  binding?: BoundResource | null
}
export interface InventoryPage<S> { items: InventoryObject<S>[]; total: number; limit: number; offset: number }
export interface BindingCandidate { id: string; code: string; name: string; environment: NamedRef; project: NamedRef }
export interface ResourceIntegrationBinding {
  integration: { id: string; name: string; providerType: 'REMNAWAVE' }
  object: InventoryObject<RemnawaveNodeSummary>
}
