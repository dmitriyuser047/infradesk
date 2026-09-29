package ru.bitec.app.ops
package infrastructure.http.dto

import java.time.Instant
import java.util.UUID

final case class RemnawaveCredentialRequest(apiToken: String, caddyApiKey: Option[String])
final case class CreateIntegrationRequest(name: String, providerType: String, baseUrl: String,
  credentials: RemnawaveCredentialRequest)
final case class UpdateIntegrationRequest(name: String, baseUrl: String,
  credentials: Option[RemnawaveCredentialRequest])
final case class IntegrationCredentialStatus(apiTokenConfigured: Boolean, caddyApiKeyConfigured: Boolean)
final case class IntegrationResponse(id: UUID, name: String, providerType: String, baseUrl: String,
  enabled: Boolean, credential: IntegrationCredentialStatus, createdAt: Instant, updatedAt: Instant)
final case class IntegrationProviderResponse(`type`: String, displayName: String, capabilities: List[String])
final case class IntegrationTestResponse(ok: Boolean, providerType: String, latencyMs: Long)
