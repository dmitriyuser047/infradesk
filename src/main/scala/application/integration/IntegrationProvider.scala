package ru.bitec.app.ops
package application.integration

import domain.integration.{IntegrationActionCode, IntegrationActionRemoteOutcome, IntegrationBaseUrl,
  IntegrationCapability, IntegrationCredential, IntegrationObservation, IntegrationProviderType}
import java.util.UUID
import java.time.Instant
import io.circe.Json

final case class IntegrationRuntimeContext(id: UUID, organizationId: UUID,
  baseUrl: IntegrationBaseUrl, credential: IntegrationCredential)
final case class IntegrationTestResult(ok: Boolean, providerType: IntegrationProviderType, latencyMs: Long,
  nodeApi: Option[domain.integration.NodeApiCompatibility] = None)

/** Full config exists only during an explicit operation. Inventory and list DTOs contain hashes. */
final case class IntegrationConfigProfileDocument(externalId: String, config: Json, updatedAt: Option[Instant]) {
  override def toString: String = s"IntegrationConfigProfileDocument($externalId,<redacted>,$updatedAt)"
}

trait IntegrationConfigProfileTransport[F[_]] {
  def fetchConfigProfile(context: IntegrationRuntimeContext, externalId: String): F[IntegrationConfigProfileDocument]
  def updateConfigProfile(context: IntegrationRuntimeContext, externalId: String,
    config: Json, desiredSha256: String): F[domain.integration.IntegrationActionRemoteOutcome]
}

trait IntegrationProvider[F[_]] {
  def providerType: IntegrationProviderType
  def displayName: String
  def capabilities: Set[IntegrationCapability]
  def testConnection(context: IntegrationRuntimeContext): F[IntegrationTestResult]
  /** Reads one complete, sanitized snapshot of the provider's inventory. Read-only: no write request
    * ever reaches the provider, and nothing here touches the database.
    */
  def observe(context: IntegrationRuntimeContext): F[IntegrationObservation]
  def executeAction(context: IntegrationRuntimeContext, externalId: String,
    action: IntegrationActionCode): F[IntegrationActionRemoteOutcome]
  def configProfiles: Option[IntegrationConfigProfileTransport[F]] = None
  def nodeProvisioning: Option[application.port.NodeProvisioningTransport[F]] = None
}

final class IntegrationProviderRegistry[F[_]](providers: List[IntegrationProvider[F]]) {
  private val grouped = providers.groupBy(_.providerType)
  require(grouped.forall(_._2.size == 1), "Duplicate integration provider types")
  private val byType = grouped.view.mapValues(_.head).toMap
  def find(providerType: IntegrationProviderType): Option[IntegrationProvider[F]] = byType.get(providerType)
  def all: List[IntegrationProvider[F]] = providers
}
