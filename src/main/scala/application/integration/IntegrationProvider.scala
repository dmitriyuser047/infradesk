package ru.bitec.app.ops
package application.integration

import domain.integration.{IntegrationBaseUrl, IntegrationCapability, IntegrationCredential, IntegrationObservation,
  IntegrationProviderType}
import java.util.UUID

final case class IntegrationRuntimeContext(id: UUID, organizationId: UUID,
  baseUrl: IntegrationBaseUrl, credential: IntegrationCredential)
final case class IntegrationTestResult(ok: Boolean, providerType: IntegrationProviderType, latencyMs: Long)

trait IntegrationProvider[F[_]] {
  def providerType: IntegrationProviderType
  def displayName: String
  def capabilities: Set[IntegrationCapability]
  def testConnection(context: IntegrationRuntimeContext): F[IntegrationTestResult]
  /** Reads one complete, sanitized snapshot of the provider's inventory. Read-only: no write request
    * ever reaches the provider, and nothing here touches the database.
    */
  def observe(context: IntegrationRuntimeContext): F[IntegrationObservation]
}

final class IntegrationProviderRegistry[F[_]](providers: List[IntegrationProvider[F]]) {
  private val grouped = providers.groupBy(_.providerType)
  require(grouped.forall(_._2.size == 1), "Duplicate integration provider types")
  private val byType = grouped.view.mapValues(_.head).toMap
  def find(providerType: IntegrationProviderType): Option[IntegrationProvider[F]] = byType.get(providerType)
  def all: List[IntegrationProvider[F]] = providers
}
