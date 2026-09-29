package ru.bitec.app.ops
package integration.remnawave

import application.integration.{IntegrationProvider, IntegrationRuntimeContext, IntegrationTestResult}
import cats.effect.IO
import domain.integration.{IntegrationActionCode, IntegrationActionRemoteOutcome, IntegrationCapability,
  IntegrationObservation, IntegrationProviderType, RemnawaveCredential}

/** Remnawave, observed read-only: a connectivity check and one inventory snapshot. */
final class RemnawaveProvider(client: RemnawaveClient) extends IntegrationProvider[IO] {
  override val providerType: IntegrationProviderType = IntegrationProviderType.Remnawave
  override val displayName = "Remnawave"
  override val capabilities: Set[IntegrationCapability] = Set(
    IntegrationCapability.ConnectivityTest, IntegrationCapability.NodeDiscovery, IntegrationCapability.HostDiscovery,
    IntegrationCapability.ConfigProfileDiscovery, IntegrationCapability.MetricsRead,
    IntegrationCapability.SafeActions)

  override def testConnection(context: IntegrationRuntimeContext): IO[IntegrationTestResult] =
    credentialOf(context).flatMap(credential => IO.monotonic.flatMap(started =>
      client.probe(context.baseUrl, credential) *> IO.monotonic.map(finished =>
        IntegrationTestResult(ok = true, providerType, (finished - started).toMillis))))

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
