package ru.bitec.app.ops
package integration.remnawave

import application.integration.{IntegrationConfigProfileDocument, IntegrationConfigProfileTransport,
  IntegrationProvider, IntegrationRuntimeContext, IntegrationTestResult}
import cats.effect.IO
import cats.syntax.all._
import domain.integration.{IntegrationActionCode, IntegrationActionRemoteOutcome, IntegrationCapability,
  IntegrationObservation, IntegrationProviderType, RemnawaveCredential}

/** Remnawave: a connectivity check, one inventory snapshot, and one node action at a time. */
final class RemnawaveProvider(client: RemnawaveClient)
  extends IntegrationProvider[IO] with IntegrationConfigProfileTransport[IO] {
  private val nodes = new RemnawaveNodeProvisioning(client)
  override val providerType: IntegrationProviderType = IntegrationProviderType.Remnawave
  override val displayName = "Remnawave"
  override val capabilities: Set[IntegrationCapability] = Set(
    IntegrationCapability.ConnectivityTest, IntegrationCapability.NodeDiscovery, IntegrationCapability.HostDiscovery,
    IntegrationCapability.ConfigProfileDiscovery, IntegrationCapability.MetricsRead,
    IntegrationCapability.SafeActions, IntegrationCapability.DesiredState,
    IntegrationCapability.ConfigProfileManagement)

  override def configProfiles: Option[IntegrationConfigProfileTransport[IO]] = Some(this)
  override def nodeProvisioning: Option[application.port.NodeProvisioningTransport[IO]] = Some(nodes)

  override def fetchConfigProfile(context: IntegrationRuntimeContext,
    externalId: String): IO[IntegrationConfigProfileDocument] =
    credentialOf(context).flatMap(client.fetchConfigProfile(context.baseUrl, _, externalId))

  override def updateConfigProfile(context: IntegrationRuntimeContext, externalId: String,
    config: io.circe.Json, desiredSha256: String): IO[IntegrationActionRemoteOutcome] =
    credentialOf(context).flatMap(client.updateConfigProfile(context.baseUrl, _, externalId, config, desiredSha256))

  override def testConnection(context: IntegrationRuntimeContext): IO[IntegrationTestResult] =
    credentialOf(context).flatMap(credential => IO.monotonic.flatMap(started =>
      client.probe(context.baseUrl, credential) *> nodes.inspect(context).flatMap(compatibility =>
        IO.monotonic.map(finished => IntegrationTestResult(ok = true, providerType,
          (finished - started).toMillis, Some(compatibility))))))

  override def observe(context: IntegrationRuntimeContext): IO[IntegrationObservation] =
    credentialOf(context).flatMap(client.inventory(context.baseUrl, _))

  override def executeAction(context: IntegrationRuntimeContext, externalId: String,
    action: IntegrationActionCode): IO[IntegrationActionRemoteOutcome] =
    credentialOf(context).flatMap(client.action(context.baseUrl, _, externalId, action))

  private def credentialOf(context: IntegrationRuntimeContext): IO[RemnawaveCredential] = context.credential match {
    case credential: RemnawaveCredential => IO.pure(credential)
    case _ => IO.raiseError(RemnawaveErrors.error("INTEGRATION_PROVIDER_UNSUPPORTED"))
  }
}
