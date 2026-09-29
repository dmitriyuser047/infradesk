export interface IntegrationResponse {
  id: string
  name: string
  providerType: 'REMNAWAVE'
  baseUrl: string
  enabled: boolean
  credential: { apiTokenConfigured: boolean; caddyApiKeyConfigured: boolean }
  createdAt: string
  updatedAt: string
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
